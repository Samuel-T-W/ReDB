package buffer;

import static org.junit.jupiter.api.Assertions.*;

import buffer.FrameState.State;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

	@Test
	public void concurrentSweepersNeverReceiveTheSameFrameTwice() throws Exception {
		final int poolSize = 64;
		final int evictableCount = 16;
		final int threads = 32;

		for (int round = 0; round < 20; round++) {
			FrameState[] frames = new FrameState[poolSize];
			for (int i = 0; i < poolSize; i++) {
				// Scatter the evictable frames through a pool of pinned ones.
				frames[i] = (i % (poolSize / evictableCount) == 0) ? evictable() : pinned(1L);
			}
			ClockReplacer clock = new ClockReplacer(frames);

			CountDownLatch ready = new CountDownLatch(threads);
			CountDownLatch go = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			List<Integer> claimed = new ArrayList<>();
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
					}
				}
			} finally {
				pool.shutdownNow();
			}

			Set<Integer> distinct = new HashSet<>(claimed);
			assertEquals(claimed.size(), distinct.size(),
					"a frame was handed to more than one sweeper: " + claimed);
			assertTrue(claimed.size() <= evictableCount,
					"more victims than there were evictable frames: " + claimed.size());
			for (int index : claimed) {
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

		assertEquals(claimed.size(), new HashSet<>(claimed).size(), "double handout: " + claimed);
		assertTrue(claimed.size() <= poolSize);
	}
}
