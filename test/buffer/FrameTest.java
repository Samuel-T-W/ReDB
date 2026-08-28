package buffer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import storage.RawPage;

/**
 * Covers the ownership rules around emptying a frame: which caller is allowed
 * to make the transition, and what a refused one must leave behind.
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
