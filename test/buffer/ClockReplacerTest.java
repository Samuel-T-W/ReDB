package buffer;

import static org.junit.jupiter.api.Assertions.*;

import buffer.FrameState.State;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Covers the clock sweeper's selection rules (skip, second chance, claim), its
 * bounded-sweep termination contract when nothing is evictable, and the
 * exclusivity guarantee that no frame is ever handed to two callers.
 */
public class ClockReplacerTest {

	private static FrameState evictable() {
		return new FrameState(State.VALID, 0L, false, 0L);
	}

	private static FrameState referenced() {
		return new FrameState(State.VALID, 0L, true, 0L);
	}

	private static FrameState pinned(long pins) {
		return new FrameState(State.VALID, pins, false, 0L);
	}

	private static FrameState[] pool(FrameState... states) {
		return states;
	}

	// -------------------------------------------------------------- selection

	@Test
	public void selectsUnreferencedUnpinnedValidFrameAndReturnsItEvicting() {
		FrameState[] frames = pool(evictable());
		ClockReplacer clock = new ClockReplacer(frames);

		OptionalInt victim = clock.findVictim();

		assertTrue(victim.isPresent());
		assertEquals(0, victim.getAsInt());
		assertEquals(State.EVICTING, frames[0].state(),
				"the winner must receive the frame already claimed for eviction");
	}

	@Test
	public void referencedFrameGetsSecondChanceBeforeBeingEvicted() {
		// Hand starts at 0: frame 0 is referenced, frame 1 is immediately evictable.
		FrameState[] frames = pool(referenced(), evictable());
		ClockReplacer clock = new ClockReplacer(frames);

		OptionalInt first = clock.findVictim();
		assertEquals(OptionalInt.of(1), first, "a referenced frame must not be evicted ahead of a cold one");
		assertEquals(State.VALID, frames[0].state(), "the referenced frame must survive the first pass");
		assertFalse(frames[0].isReferenced(), "its reference bit must have been cleared on the way past");

		OptionalInt second = clock.findVictim();
		assertEquals(OptionalInt.of(0), second, "having lost its reference bit, the frame is now evictable");
		assertEquals(State.EVICTING, frames[0].state());
	}

	@Test
	public void everyFrameReferencedStillYieldsAVictimAfterClearingBits() {
		FrameState[] frames = pool(referenced(), referenced(), referenced());
		ClockReplacer clock = new ClockReplacer(frames);

		OptionalInt victim = clock.findVictim();

		assertTrue(victim.isPresent(), "the second pass of the sweep should find the bits it just cleared");
		assertEquals(State.EVICTING, frames[victim.getAsInt()].state());
	}

	@Test
	public void pinnedFrameIsNeverReturned() {
		FrameState[] frames = pool(pinned(1L), pinned(7L), pinned(FrameState.MAX_PIN_COUNT));
		ClockReplacer clock = new ClockReplacer(frames);

		for (int sweep = 0; sweep < 50; sweep++) {
			assertEquals(OptionalInt.empty(), clock.findVictim());
		}
		for (FrameState frame : frames) {
			assertEquals(State.VALID, frame.state());
		}
	}

	@Test
	public void pinnedFrameIsSkippedWhileItsNeighbourIsEvicted() {
		FrameState[] frames = pool(pinned(3L), evictable(), pinned(1L));
		ClockReplacer clock = new ClockReplacer(frames);

		assertEquals(OptionalInt.of(1), clock.findVictim());
		for (int sweep = 0; sweep < 20; sweep++) {
			assertEquals(OptionalInt.empty(), clock.findVictim(),
					"only the unpinned frame was ever a candidate");
		}
	}

	@Test
	public void allFramesPinnedReturnsEmptyWithoutHangingOrThrowing() {
		FrameState[] frames = new FrameState[64];
		for (int i = 0; i < frames.length; i++) {
			frames[i] = pinned(1L);
		}
		ClockReplacer clock = new ClockReplacer(frames);

		assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> assertEquals(OptionalInt.empty(), clock.findVictim()),
				"the sweep must be bounded, not spin until a frame frees up");
	}

	@Test
	public void nonValidStatesAreSkipped() {
		FrameState[] frames = pool(
				new FrameState(State.FREE, 0L, false, 0L),
				new FrameState(State.LOADING, 0L, false, 0L),
				new FrameState(State.EVICTING, 0L, false, 0L),
				new FrameState(State.FLUSHING, 0L, false, 0L));
		ClockReplacer clock = new ClockReplacer(frames);

		assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> assertEquals(OptionalInt.empty(), clock.findVictim()));

		assertEquals(State.FREE, frames[0].state());
		assertEquals(State.LOADING, frames[1].state());
		assertEquals(State.EVICTING, frames[2].state());
		assertEquals(State.FLUSHING, frames[3].state());
	}

	@Test
	public void emptyPoolReturnsEmpty() {
		ClockReplacer clock = new ClockReplacer(new FrameState[0]);
		assertEquals(OptionalInt.empty(), clock.findVictim());
		assertEquals(0, clock.hand());
	}

	// ------------------------------------------------------------------ hand

	@Test
	public void handAdvancesAndWrapsAcrossTheArrayBoundary() {
		FrameState[] frames = pool(evictable(), evictable(), evictable());
		ClockReplacer clock = new ClockReplacer(frames);
		assertEquals(0, clock.hand());

		assertEquals(OptionalInt.of(0), clock.findVictim());
		assertEquals(1, clock.hand());

		assertEquals(OptionalInt.of(1), clock.findVictim());
		assertEquals(2, clock.hand());

		assertEquals(OptionalInt.of(2), clock.findVictim());
		assertEquals(0, clock.hand(), "the hand must wrap back to the start of the pool");
	}

	@Test
	public void handKeepsSweepingPastNonCandidates() {
		FrameState[] frames = pool(
				new FrameState(State.FREE, 0L, false, 0L),
				new FrameState(State.FREE, 0L, false, 0L),
				evictable(),
				new FrameState(State.FREE, 0L, false, 0L));
		ClockReplacer clock = new ClockReplacer(frames);

		assertEquals(OptionalInt.of(2), clock.findVictim());
		assertEquals(3, clock.hand(), "the hand rests just past the frame it claimed");
	}

	// ----------------------------------------------------------- concurrency

	/**
	 * A sweep is budgeted at two passes over the pool, so a sweeper that comes
	 * back empty is asserting "nothing here is evictable". These tests therefore
	 * pin down both halves of the contract: no frame reaches two callers, and
	 * every evictable frame reaches one. The second half is what makes them
	 * fail against a {@code findVictim()} stubbed to return empty — without it
	 * an all-empty run satisfies every uniqueness assertion trivially.
	 */
	@Test
	public void concurrentSweepersNeverReceiveTheSameFrameTwice() throws Exception {
		final int poolSize = 64;
		final int evictableCount = 16;
		final int threads = 32;
		final int stride = poolSize / evictableCount;

		for (int round = 0; round < 20; round++) {
			FrameState[] frames = new FrameState[poolSize];
			Set<Integer> evictableIndexes = new TreeSet<>();
			for (int i = 0; i < poolSize; i++) {
				// Scatter the evictable frames through a pool of pinned ones.
				boolean free = i % stride == 0;
				frames[i] = free ? evictable() : pinned(1L);
				if (free) {
					evictableIndexes.add(i);
				}
			}
			ClockReplacer clock = new ClockReplacer(frames);

			CountDownLatch ready = new CountDownLatch(threads);
			CountDownLatch go = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			List<Integer> claimed = new ArrayList<>();
			int emptyHanded = 0;
			try {
				List<Future<OptionalInt>> futures = new ArrayList<>();
				for (int t = 0; t < threads; t++) {
					futures.add(pool.submit(() -> {
						ready.countDown();
						go.await();
						return clock.findVictim();
					}));
				}
				assertTrue(ready.await(30, TimeUnit.SECONDS));
				go.countDown();
				for (Future<OptionalInt> f : futures) {
					OptionalInt victim = f.get(60, TimeUnit.SECONDS);
					if (victim.isPresent()) {
						claimed.add(victim.getAsInt());
					} else {
						emptyHanded++;
					}
				}
			} finally {
				pool.shutdownNow();
			}

			Set<Integer> distinct = new TreeSet<>(claimed);
			assertEquals(claimed.size(), distinct.size(),
					"a frame was handed to more than one sweeper: " + claimed);
			// Every sweeper covers the whole pool twice, so a frame can only go
			// unclaimed if someone else took it first. Anything left over means a
			// sweeper reported "nothing evictable" over a pool that still had
			// cold frames in it.
			assertEquals(evictableIndexes, distinct,
					"round " + round + ": " + emptyHanded + " of " + threads
							+ " sweepers came back empty, yet these evictable frames were never"
							+ " handed out: " + missing(evictableIndexes, distinct));
			for (int index : distinct) {
				assertEquals(State.EVICTING, frames[index].state());
			}
			for (int i = 0; i < poolSize; i++) {
				if (!distinct.contains(i)) {
					assertEquals(State.VALID, frames[i].state(),
							"frame " + i + " changed state without being handed to anyone");
				}
			}
		}
	}

	@Test
	public void concurrentSweepersDrainAPoolWithoutDoubleHandout() throws Exception {
		final int poolSize = 32;
		final int threads = 8;

		FrameState[] frames = new FrameState[poolSize];
		for (int i = 0; i < poolSize; i++) {
			frames[i] = evictable();
		}
		ClockReplacer clock = new ClockReplacer(frames);

		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Integer> claimed = new ArrayList<>();
		try {
			List<Future<List<Integer>>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				futures.add(pool.submit(() -> {
					go.await();
					List<Integer> mine = new ArrayList<>();
					for (int i = 0; i < poolSize; i++) {
						OptionalInt victim = clock.findVictim();
						if (victim.isEmpty()) {
							break;
						}
						mine.add(victim.getAsInt());
					}
					return mine;
				}));
			}
			go.countDown();
			for (Future<List<Integer>> f : futures) {
				claimed.addAll(f.get(60, TimeUnit.SECONDS));
			}
		} finally {
			pool.shutdownNow();
		}

		Set<Integer> distinct = new TreeSet<>(claimed);
		assertEquals(claimed.size(), distinct.size(), "double handout: " + claimed);
		// Nothing here is pinned or referenced, so the only reason to stop is an
		// empty pool. A sweeper that quits early leaves evictable frames behind.
		Set<Integer> everyFrame = new TreeSet<>();
		for (int i = 0; i < poolSize; i++) {
			everyFrame.add(i);
		}
		assertEquals(everyFrame, distinct,
				"the sweepers gave up with these frames still evictable: "
						+ missing(everyFrame, distinct));
		for (FrameState frame : frames) {
			assertEquals(State.EVICTING, frame.state());
		}
	}

	/**
	 * findVictim() skips any frame it observes as pinned, so it only ever spends
	 * a second chance on a frame it saw at pin 0 — and tryPin() raises the
	 * reference bit in the same CAS that raises the pin. "Pinned but
	 * unreferenced" is therefore a state the sweeper must never be able to
	 * produce. Observing it means a pin landed between the sweeper's snapshot
	 * and its clearReferenced(), and the sweeper cleared a reference bit newer
	 * than the observation it acted on: the page loses the second chance it had
	 * just earned and falls to the very next pass of the hand.
	 */
	@Test
	public void secondChanceNeverSpendsAReferenceSetAfterTheSnapshot() throws Exception {
		final int poolSize = 8;
		final int sweepers = 4;
		final int pinners = 4;
		final int iterations = 5_000;

		FrameState[] frames = new FrameState[poolSize];
		for (int i = 0; i < poolSize; i++) {
			frames[i] = referenced();
		}
		ClockReplacer clock = new ClockReplacer(frames);

		AtomicBoolean stop = new AtomicBoolean();
		AtomicLong pinsTaken = new AtomicLong();
		AtomicLong referencesLost = new AtomicLong();
		CountDownLatch done = new CountDownLatch(pinners);
		List<Thread> threads = new ArrayList<>();

		for (int s = 0; s < sweepers; s++) {
			threads.add(new Thread(() -> {
				while (!stop.get()) {
					// Hand every claim straight back, so the pool never drains
					// and the sweepers keep passing over live frames.
					clock.findVictim().ifPresent(index -> frames[index].abortEvict());
				}
			}));
		}
		for (int p = 0; p < pinners; p++) {
			final int offset = p;
			threads.add(new Thread(() -> {
				try {
					for (int i = 0; i < iterations; i++) {
						for (int f = 0; f < poolSize; f++) {
							FrameState frame = frames[(offset + f) % poolSize];
							if (frame.tryPin()) {
								pinsTaken.incrementAndGet();
								if (!frame.isReferenced()) {
									referencesLost.incrementAndGet();
								}
								frame.unpin(frame.version());
							}
						}
					}
				} finally {
					done.countDown();
				}
			}));
		}

		for (Thread t : threads) {
			t.start();
		}
		assertTrue(done.await(120, TimeUnit.SECONDS), "the pinners never finished");
		stop.set(true);
		for (Thread t : threads) {
			t.join(60_000);
		}

		assertTrue(pinsTaken.get() > 0, "no pin ever succeeded, so this run proved nothing");
		assertEquals(0L, referencesLost.get(),
				referencesLost + " of " + pinsTaken + " successful pins found their own"
						+ " reference bit already cleared: the sweeper spent a second chance"
						+ " it never observed");
	}

	/** The elements of {@code expected} that {@code actual} never produced. */
	private static Set<Integer> missing(Set<Integer> expected, Set<Integer> actual) {
		Set<Integer> gap = new TreeSet<>(expected);
		gap.removeAll(actual);
		return gap;
	}

	// ------------------------------------------------------ free-frame count

	private static FrameState[] countedFreePool(int size, AtomicInteger freeFrames) {
		FrameState[] frames = new FrameState[size];
		for (int i = 0; i < size; i++) {
			frames[i] = new FrameState(freeFrames);
		}
		return frames;
	}

	@Test
	public void claimFreeHandsOutEveryFreeFrameThenStopsSweepingOnceThePoolIsFull() {
		AtomicInteger freeFrames = new AtomicInteger();
		FrameState[] frames = countedFreePool(3, freeFrames);
		ClockReplacer clock = new ClockReplacer(frames, freeFrames);
		assertEquals(3, freeFrames.get());

		assertEquals(OptionalInt.of(0), clock.claimFree());
		assertEquals(OptionalInt.of(1), clock.claimFree());
		assertEquals(OptionalInt.of(2), clock.claimFree());
		assertEquals(0, freeFrames.get());
		assertEquals(3, clock.freeSweeps(), "one sweep per frame handed out");

		for (int miss = 0; miss < 1000; miss++) {
			assertEquals(OptionalInt.empty(), clock.claimFree());
		}
		assertEquals(3, clock.freeSweeps(), "a full pool must be answered without probing a single frame");
	}

	@Test
	public void frameReturnedToFreeAfterThePoolFilledIsFoundByTheNextClaim() {
		AtomicInteger freeFrames = new AtomicInteger();
		FrameState[] frames = countedFreePool(3, freeFrames);
		ClockReplacer clock = new ClockReplacer(frames, freeFrames);
		for (int i = 0; i < 3; i++) {
			assertTrue(clock.claimFree().isPresent());
		}
		assertEquals(OptionalInt.empty(), clock.claimFree());

		// frame 1 goes through its whole life and is recycled back to FREE
		assertTrue(frames[1].finishLoad());
		assertTrue(frames[1].tryClaimForEviction());
		assertTrue(frames[1].finishEvict());
		assertEquals(1, freeFrames.get());

		assertEquals(OptionalInt.of(1), clock.claimFree(), "the recycled frame must never be stranded");
		assertEquals(0, freeFrames.get());
		assertEquals(4, clock.freeSweeps());
	}

	@Test
	public void replacerWithoutASharedCountSweepsOnEveryClaim() {
		FrameState[] frames = pool(new FrameState(), new FrameState());
		ClockReplacer clock = new ClockReplacer(frames);

		assertTrue(clock.claimFree().isPresent());
		assertTrue(clock.claimFree().isPresent());
		assertEquals(OptionalInt.empty(), clock.claimFree());
		assertEquals(OptionalInt.empty(), clock.claimFree());

		assertEquals(4, clock.freeSweeps(), "with nothing to consult, every call has to look");
	}
}
