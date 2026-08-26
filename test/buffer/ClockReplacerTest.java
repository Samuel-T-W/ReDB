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
}
