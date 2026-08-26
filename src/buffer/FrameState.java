package buffer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A frame's entire mutable coordination state packed into a single 64-bit
 * atomic word, so pin count, lifecycle state, the clock's reference bit and a
 * recycle version can all be read and updated in one CAS. This is what lets
 * the buffer pool drop its global lock: every transition below is a guarded
 * compare-and-swap over the whole word, never a lock.
 *
 * <p>Bit layout (LSB first):
 *
 * <pre>
 *   bits  0..31  pinCount   (unsigned 32-bit)
 *   bits 32..34  state ordinal (FREE=0, LOADING=1, VALID=2, EVICTING=3, FLUSHING=4)
 *   bit  35      referenced (the clock sweeper's second-chance bit)
 *   bits 36..63  version    (28-bit recycle counter, bumped on every return to FREE)
 * </pre>
 *
 * <p>The version counter exists to defeat ABA: a frame that is evicted and
 * refilled while another thread held a stale observation will not compare
 * equal, because the version moved.
 *
 * <p>All bit twiddling is confined to this class. Callers see enums, longs and
 * booleans, never a mask or a shift.
 */
public final class FrameState {

	/** Lifecycle state of a buffer frame. Ordinals are part of the packed layout. */
	public enum State {
		FREE, LOADING, VALID, EVICTING, FLUSHING
	}

	private static final State[] STATES = State.values();

	private static final int STATE_SHIFT = 32;
	private static final int REF_SHIFT = 35;
	private static final int VERSION_SHIFT = 36;

	private static final long PIN_MASK = 0xFFFF_FFFFL;
	private static final long STATE_MASK = 0x7L;
	private static final long REF_BIT = 1L << REF_SHIFT;
	private static final long VERSION_MASK = 0x0FFF_FFFFL;

	/** Largest representable pin count; {@link #tryPin()} refuses to exceed it. */
	public static final long MAX_PIN_COUNT = PIN_MASK;

	/** Largest representable version; the counter wraps to 0 past this. */
	public static final long MAX_VERSION = VERSION_MASK;

	// ----------------------------------------------------- encode and decode

	public static long encode(State state, long pinCount, boolean referenced, long version) {
		if (state == null) {
			throw new NullPointerException("state");
		}
		if (pinCount < 0 || pinCount > MAX_PIN_COUNT) {
			throw new IllegalArgumentException("pinCount out of range: " + pinCount);
		}
		if (version < 0 || version > MAX_VERSION) {
			throw new IllegalArgumentException("version out of range: " + version);
		}
		return (pinCount & PIN_MASK)
				| ((long) state.ordinal() << STATE_SHIFT)
				| (referenced ? REF_BIT : 0L)
				| ((version & VERSION_MASK) << VERSION_SHIFT);
	}

	public static State decodeState(long word) {
		return STATES[(int) ((word >>> STATE_SHIFT) & STATE_MASK)];
	}

	public static long decodePinCount(long word) {
		return word & PIN_MASK;
	}

	public static boolean decodeReferenced(long word) {
		return (word & REF_BIT) != 0L;
	}

	public static long decodeVersion(long word) {
		return (word >>> VERSION_SHIFT) & VERSION_MASK;
	}

	/** Human-readable rendering of a packed word, e.g. {@code FrameState[state=VALID, pin=3, ref=1, ver=7]}. */
	public static String describe(long word) {
		return "FrameState[state=" + decodeState(word)
				+ ", pin=" + decodePinCount(word)
				+ ", ref=" + (decodeReferenced(word) ? 1 : 0)
				+ ", ver=" + decodeVersion(word) + "]";
	}
}
