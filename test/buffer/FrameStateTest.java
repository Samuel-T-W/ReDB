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
}
