package buffer;

import static org.junit.jupiter.api.Assertions.*;

import buffer.FrameState.State;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Exercises the packed frame state word: every legal transition, every illegal
 * transition (which must leave the word byte-for-byte unchanged), field
 * isolation across boundary values, and the CAS behaviour under contention.
 */
public class FrameStateTest {

	private static FrameState at(State state) {
		return new FrameState(state, 0L, false, 0L);
	}

	/** Asserts the call returns false and does not disturb a single bit of the word. */
	private static void assertRejectedAndUnchanged(FrameState fs, java.util.function.Predicate<FrameState> op) {
		long before = fs.snapshot();
		assertFalse(op.test(fs), "operation should have been rejected from " + fs);
		assertEquals(before, fs.snapshot(), "rejected operation mutated the word: " + fs);
	}

	// ------------------------------------------------------- legal transitions

	@Test
	public void fullLifecycleTransitionsSucceed() {
		FrameState fs = new FrameState();
		assertEquals(State.FREE, fs.state());

		assertTrue(fs.tryBeginLoad());
		assertEquals(State.LOADING, fs.state());

		assertTrue(fs.finishLoad());
		assertEquals(State.VALID, fs.state());

		assertTrue(fs.tryPin());
		assertEquals(1L, fs.pinCount());
		assertTrue(fs.isReferenced(), "tryPin must set the reference bit in the same CAS");

		fs.unpin();
		assertEquals(0L, fs.pinCount());

		assertTrue(fs.clearReferenced());
		assertFalse(fs.isReferenced());

		assertTrue(fs.tryClaimForEviction());
		assertEquals(State.EVICTING, fs.state());

		assertTrue(fs.beginFlush());
		assertEquals(State.FLUSHING, fs.state());

		assertTrue(fs.finishEvict());
		assertEquals(State.FREE, fs.state());
		assertEquals(1L, fs.version());
	}

	@Test
	public void finishEvictSucceedsDirectlyFromEvicting() {
		FrameState fs = at(State.EVICTING);
		assertTrue(fs.finishEvict());
		assertEquals(State.FREE, fs.state());
		assertEquals(1L, fs.version());
	}

	@Test
	public void abortEvictReturnsFrameToValid() {
		FrameState fs = at(State.EVICTING);
		assertTrue(fs.abortEvict());
		assertEquals(State.VALID, fs.state());
		assertEquals(0L, fs.version(), "aborting is not a recycle, version must not move");
	}

	@Test
	public void finishEvictResetsPinCountAndBumpsVersion() {
		FrameState fs = new FrameState(State.EVICTING, 5L, true, 41L);
		assertTrue(fs.finishEvict());
		assertEquals(State.FREE, fs.state());
		assertEquals(0L, fs.pinCount());
		assertFalse(fs.isReferenced());
		assertEquals(42L, fs.version());
	}

	// ----------------------------------------------------- illegal transitions

	@Test
	public void tryPinRejectedFromEveryNonValidState() {
		for (State s : new State[] {State.FREE, State.LOADING, State.EVICTING, State.FLUSHING}) {
			assertRejectedAndUnchanged(at(s), FrameState::tryPin);
		}
	}

	@Test
	public void tryClaimForEvictionRejectedFromEveryNonValidState() {
		for (State s : new State[] {State.FREE, State.LOADING, State.EVICTING, State.FLUSHING}) {
			assertRejectedAndUnchanged(at(s), FrameState::tryClaimForEviction);
		}
	}

	@Test
	public void tryClaimForEvictionRejectedOnPinnedFrame() {
		FrameState fs = new FrameState(State.VALID, 1L, false, 3L);
		assertRejectedAndUnchanged(fs, FrameState::tryClaimForEviction);
	}

	@Test
	public void tryClaimForEvictionRejectedOnReferencedFrame() {
		FrameState fs = new FrameState(State.VALID, 0L, true, 3L);
		assertRejectedAndUnchanged(fs, FrameState::tryClaimForEviction);
	}

	@Test
	public void tryBeginLoadRejectedFromEveryNonFreeState() {
		for (State s : new State[] {State.LOADING, State.VALID, State.EVICTING, State.FLUSHING}) {
			assertRejectedAndUnchanged(at(s), FrameState::tryBeginLoad);
		}
	}

	@Test
	public void finishLoadRejectedFromEveryNonLoadingState() {
		for (State s : new State[] {State.FREE, State.VALID, State.EVICTING, State.FLUSHING}) {
			assertRejectedAndUnchanged(at(s), FrameState::finishLoad);
		}
	}

	@Test
	public void beginFlushRejectedFromEveryNonEvictingState() {
		for (State s : new State[] {State.FREE, State.LOADING, State.VALID, State.FLUSHING}) {
			assertRejectedAndUnchanged(at(s), FrameState::beginFlush);
		}
	}

	@Test
	public void finishEvictRejectedFromFreeLoadingAndValid() {
		for (State s : new State[] {State.FREE, State.LOADING, State.VALID}) {
			assertRejectedAndUnchanged(at(s), FrameState::finishEvict);
		}
	}

	@Test
	public void abortEvictRejectedFromEveryNonEvictingState() {
		for (State s : new State[] {State.FREE, State.LOADING, State.VALID, State.FLUSHING}) {
			assertRejectedAndUnchanged(at(s), FrameState::abortEvict);
		}
	}

	@Test
	public void clearReferencedRejectedWhenNotValidOrNotReferenced() {
		for (State s : new State[] {State.FREE, State.LOADING, State.EVICTING, State.FLUSHING}) {
			assertRejectedAndUnchanged(new FrameState(s, 0L, true, 0L), FrameState::clearReferenced);
		}
		assertRejectedAndUnchanged(new FrameState(State.VALID, 0L, false, 0L), FrameState::clearReferenced);
	}

	// --------------------------------------------------------- encode / decode

	@Test
	public void encodeDecodeRoundTripsAcrossBoundaryValues() {
		long[] pins = {0L, 1L, 2L, FrameState.MAX_PIN_COUNT - 1, FrameState.MAX_PIN_COUNT};
		long[] versions = {0L, 1L, 2L, FrameState.MAX_VERSION - 1, FrameState.MAX_VERSION};
		for (State state : State.values()) {
			for (long pin : pins) {
				for (long version : versions) {
					for (boolean ref : new boolean[] {false, true}) {
						long word = FrameState.encode(state, pin, ref, version);
						assertEquals(state, FrameState.decodeState(word));
						assertEquals(pin, FrameState.decodePinCount(word));
						assertEquals(ref, FrameState.decodeReferenced(word));
						assertEquals(version, FrameState.decodeVersion(word));

						FrameState fs = new FrameState(state, pin, ref, version);
						assertEquals(word, fs.snapshot());
						assertEquals(state, fs.state());
						assertEquals(pin, fs.pinCount());
						assertEquals(ref, fs.isReferenced());
						assertEquals(version, fs.version());
					}
				}
			}
		}
	}

	@Test
	public void maxPinCountDoesNotBleedIntoOtherFields() {
		long word = FrameState.encode(State.FLUSHING, FrameState.MAX_PIN_COUNT, true, FrameState.MAX_VERSION);
		assertEquals(State.FLUSHING, FrameState.decodeState(word));
		assertTrue(FrameState.decodeReferenced(word));
		assertEquals(FrameState.MAX_VERSION, FrameState.decodeVersion(word));
		assertEquals(FrameState.MAX_PIN_COUNT, FrameState.decodePinCount(word));
	}

	@Test
	public void maxVersionDoesNotBleedIntoOtherFields() {
		long word = FrameState.encode(State.LOADING, 7L, false, FrameState.MAX_VERSION);
		assertEquals(State.LOADING, FrameState.decodeState(word));
		assertEquals(7L, FrameState.decodePinCount(word));
		assertFalse(FrameState.decodeReferenced(word));
	}

	@Test
	public void referenceBitDoesNotDisturbNeighbouringFields() {
		FrameState fs = new FrameState(State.VALID, 123L, false, 456L);
		assertTrue(fs.tryPin());
		assertTrue(fs.isReferenced());
		assertEquals(124L, fs.pinCount());
		assertEquals(State.VALID, fs.state());
		assertEquals(456L, fs.version());
	}

	@Test
	public void versionWrapsAroundAtTwentyEightBits() {
		FrameState fs = new FrameState(State.EVICTING, 0L, false, FrameState.MAX_VERSION);
		assertTrue(fs.finishEvict());
		assertEquals(0L, fs.version(), "version must wrap inside its 28 bits");
		assertEquals(State.FREE, fs.state());
		assertEquals(0L, fs.pinCount());
		assertFalse(fs.isReferenced());
	}

	@Test
	public void encodeRejectsOutOfRangeFields() {
		assertThrows(IllegalArgumentException.class,
				() -> FrameState.encode(State.VALID, FrameState.MAX_PIN_COUNT + 1, false, 0L));
		assertThrows(IllegalArgumentException.class, () -> FrameState.encode(State.VALID, -1L, false, 0L));
		assertThrows(IllegalArgumentException.class,
				() -> FrameState.encode(State.VALID, 0L, false, FrameState.MAX_VERSION + 1));
	}

	@Test
	public void toStringDecodesIntoReadableForm() {
		FrameState fs = new FrameState(State.VALID, 3L, true, 7L);
		assertEquals("FrameState[state=VALID, pin=3, ref=1, ver=7]", fs.toString());
		assertEquals("FrameState[state=FREE, pin=0, ref=0, ver=0]", new FrameState().toString());
	}

	// ------------------------------------------------------------ pin counting

	@Test
	public void unpinBelowZeroThrows() {
		FrameState fs = at(State.VALID);
		long before = fs.snapshot();
		assertThrows(IllegalStateException.class, fs::unpin);
		assertEquals(before, fs.snapshot());
	}

	@Test
	public void pinCountOverflowIsRefusedWithoutCorruptingOtherFields() {
		FrameState fs = new FrameState(State.VALID, FrameState.MAX_PIN_COUNT, true, 99L);
		long before = fs.snapshot();
		assertFalse(fs.tryPin(), "pinning at the maximum must fail rather than wrap");
		assertEquals(before, fs.snapshot());
		assertEquals(State.VALID, fs.state());
		assertEquals(FrameState.MAX_PIN_COUNT, fs.pinCount());
		assertTrue(fs.isReferenced());
		assertEquals(99L, fs.version());

		fs.unpin();
		assertEquals(FrameState.MAX_PIN_COUNT - 1, fs.pinCount());
		assertTrue(fs.tryPin());
		assertEquals(FrameState.MAX_PIN_COUNT, fs.pinCount());
	}

	// -------------------------------------------------------------- contention

	@Test
	public void concurrentPinUnpinPairsLeaveCountAtZero() throws Exception {
		final int threads = 8;
		final int iterations = 20_000;
		FrameState fs = at(State.VALID);
		CyclicBarrier start = new CyclicBarrier(threads);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				futures.add(pool.submit(() -> {
					start.await();
					int pinned = 0;
					for (int i = 0; i < iterations; i++) {
						if (fs.tryPin()) {
							pinned++;
							fs.unpin();
						}
					}
					return pinned;
				}));
			}
			for (Future<Integer> f : futures) {
				assertEquals(iterations, f.get(60, TimeUnit.SECONDS).intValue());
			}
		} finally {
			pool.shutdownNow();
		}
		assertEquals(0L, fs.pinCount(), "lost or doubled update in the CAS loop");
		assertEquals(State.VALID, fs.state());
	}

	@Test
	public void exactlyOneThreadWinsTheEvictionClaim() throws Exception {
		final int threads = 16;
		for (int round = 0; round < 50; round++) {
			FrameState fs = at(State.VALID);
			CountDownLatch ready = new CountDownLatch(threads);
			CountDownLatch go = new CountDownLatch(1);
			AtomicInteger winners = new AtomicInteger();
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			try {
				for (int t = 0; t < threads; t++) {
					pool.submit(() -> {
						ready.countDown();
						go.await();
						if (fs.tryClaimForEviction()) {
							winners.incrementAndGet();
						}
						return null;
					});
				}
				assertTrue(ready.await(30, TimeUnit.SECONDS));
				go.countDown();
				pool.shutdown();
				assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
			} finally {
				pool.shutdownNow();
			}
			assertEquals(1, winners.get(), "eviction claim must hand ownership to exactly one thread");
			assertEquals(State.EVICTING, fs.state());
		}
	}

	@Test
	public void evictionWinnerExcludesAllSubsequentPinners() throws Exception {
		final int pinners = 8;
		for (int round = 0; round < 100; round++) {
			FrameState fs = at(State.VALID);
			CyclicBarrier start = new CyclicBarrier(pinners + 1);
			AtomicBoolean violation = new AtomicBoolean(false);
			ExecutorService pool = Executors.newFixedThreadPool(pinners + 1);
			try {
				for (int t = 0; t < pinners; t++) {
					pool.submit(() -> {
						start.await();
						for (int i = 0; i < 200; i++) {
							if (fs.tryPin()) {
								// A successful pin proves the frame was VALID at CAS time,
								// so it cannot have been claimed for eviction beforehand.
								if (fs.state() == State.EVICTING) {
									violation.set(true);
								}
								fs.unpin();
							}
						}
						return null;
					});
				}
				Future<Boolean> evictor = pool.submit(() -> {
					start.await();
					boolean claimed = false;
					for (int i = 0; i < 200 && !claimed; i++) {
						fs.clearReferenced();
						claimed = fs.tryClaimForEviction();
					}
					return claimed;
				});
				boolean claimed = evictor.get(60, TimeUnit.SECONDS);
				pool.shutdown();
				assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
				if (claimed) {
					assertEquals(State.EVICTING, fs.state());
					assertEquals(0L, fs.pinCount(),
							"an evicted frame must never gain pins after the claim");
				}
			} finally {
				pool.shutdownNow();
			}
			assertFalse(violation.get(), "a pin succeeded on a frame already claimed for eviction");
		}
	}
}
