package buffer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import storage.RawPage;

/**
 * Covers the ownership rules around emptying a frame: who is allowed to make
 * the transition, and the order in which the fields and the state word move.
 */
public class FrameTest {

	private static Frame freeFrame() {
		return new Frame(0, new FrameState());
	}

	/** Drives a fresh frame up to VALID with a page installed. */
	private static void fill(Frame frame) {
		assertTrue(frame.state.tryBeginLoad());
		frame.page = new RawPage(1);
		frame.pageKey = new PageKey("f", 1);
		frame.isDirty = true;
		frame.markValid();
	}

	@Test
	public void aFrameIsNeverPublishedFreeWhileItStillHoldsAPage() throws Exception {
		// FREE is the advertisement that a frame may be claimed and refilled. A
		// frame that reaches FREE still holding its predecessor's page hands
		// that page to its next owner, and the previous owner's trailing field
		// writes then land on top of the new owner's.
		final int cycles = 200_000;
		Frame frame = freeFrame();
		AtomicBoolean stop = new AtomicBoolean();
		AtomicLong violations = new AtomicLong();
		CountDownLatch done = new CountDownLatch(1);

		Thread owner = new Thread(() -> {
			try {
				for (int i = 0; i < cycles; i++) {
					fill(frame);
					frame.clear();
				}
			} finally {
				done.countDown();
			}
		});
		Thread observer = new Thread(() -> {
			while (!stop.get()) {
				// Bracket the field read with two snapshots of the whole state
				// word. An unchanged word means the frame did not move at all
				// across the read: it cannot have returned to FREE behind our
				// back, because every return to FREE goes through finishEvict,
				// which bumps the version. Without the bracket the observer
				// reports the owner's *next* fill as a violation of this one.
				long before = frame.state.snapshot();
				Object page = frame.page;
				long after = frame.state.snapshot();
				if (before == after
						&& FrameState.decodeState(before) == FrameState.State.FREE
						&& page != null) {
					violations.incrementAndGet();
				}
			}
		});

		owner.start();
		observer.start();
		assertTrue(done.await(120, TimeUnit.SECONDS), "the owner never finished its cycles");
		stop.set(true);
		owner.join(60_000);
		observer.join(60_000);

		assertEquals(0L, violations.get(),
				violations + " times a frame advertised itself as FREE while still holding a page");
	}

	@Test
	public void clearRefusesAFrameAnotherCallerHasClaimed() {
		Frame frame = freeFrame();
		fill(frame);

		// Someone else won the eviction claim; this frame is now theirs to
		// finish, not ours to free out from underneath them.
		assertTrue(frame.state.tryClaimForEviction());

		assertThrows(IllegalStateException.class, frame::clear);
		assertEquals(FrameState.State.EVICTING, frame.state.state(),
				"a refused clear must leave the other caller's claim standing");
		assertNotNull(frame.page, "a refused clear must not erase the frame's fields");
	}

	@Test
	public void clearRefusesAFrameBeingFilledByAnotherCaller() {
		Frame frame = freeFrame();
		assertTrue(frame.state.tryBeginLoad());

		assertThrows(IllegalStateException.class, frame::clear);
		assertEquals(FrameState.State.LOADING, frame.state.state());
	}

	@Test
	public void clearOnAFreeFrameIsANoOp() {
		Frame frame = freeFrame();
		frame.clear();
		assertEquals(FrameState.State.FREE, frame.state.state());
		assertEquals(0L, frame.state.version(), "a frame that was already FREE must not be recycled again");
	}

	@Test
	public void clearOwnedRefusesAFrameThisCallerNeverClaimed() {
		Frame frame = freeFrame();
		fill(frame);

		assertThrows(IllegalStateException.class, frame::clearOwned,
				"a VALID frame has not been claimed by anyone; clearing it needs the claim first");
		assertEquals(FrameState.State.VALID, frame.state.state());
		assertNotNull(frame.page);
	}

	@Test
	public void clearOwnedFinishesAClaimAndErasesTheFrame() {
		Frame frame = freeFrame();
		fill(frame);
		assertTrue(frame.state.tryClaimForEviction());

		frame.clearOwned();

		assertEquals(FrameState.State.FREE, frame.state.state());
		assertEquals(1L, frame.state.version(), "freeing a frame is a recycle and must move the version");
		assertNull(frame.page);
		assertNull(frame.pageKey);
		assertFalse(frame.isDirty);
	}
}
