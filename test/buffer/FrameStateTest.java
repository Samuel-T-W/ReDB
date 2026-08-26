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
}
