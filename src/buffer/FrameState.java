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

	private final AtomicLong word;

	/** Creates a frame state at FREE, pin 0, unreferenced, version 0. */
	public FrameState() {
		this(State.FREE, 0L, false, 0L);
	}

	/** Creates a frame state with the given fields already set. */
	public FrameState(State state, long pinCount, boolean referenced, long version) {
		this.word = new AtomicLong(encode(state, pinCount, referenced, version));
	}

	// ---------------------------------------------------------------- pinning

	/**
	 * Pins the frame and sets the reference bit in one atomic step. Succeeds
	 * only from {@link State#VALID}; returns false from any other state, and
	 * also returns false rather than wrapping when the pin count is already at
	 * {@link #MAX_PIN_COUNT} (a wrap would silently corrupt the state bits).
	 */
	public boolean tryPin() {
		for (;;) {
			long cur = word.get();
			if (decodeState(cur) != State.VALID) {
				return false;
			}
			long pins = decodePinCount(cur);
			if (pins == MAX_PIN_COUNT) {
				return false;
			}
			long next = withReferenced(withPinCount(cur, pins + 1), true);
			if (word.compareAndSet(cur, next)) {
				return true;
			}
		}
	}

	/** Releases one pin. Throws {@link IllegalStateException} if none is held. */
	public void unpin() {
		for (;;) {
			long cur = word.get();
			long pins = decodePinCount(cur);
			if (pins == 0) {
				throw new IllegalStateException("unpin() with pinCount already 0: " + describe(cur));
			}
			if (word.compareAndSet(cur, withPinCount(cur, pins - 1))) {
				return;
			}
		}
	}

	// --------------------------------------------------------------- eviction

	/**
	 * Claims exclusive ownership of the frame for eviction: VALID to EVICTING,
	 * permitted only when the frame is unpinned and unreferenced. Exactly one
	 * thread can win this handoff; everyone else, including concurrent pinners,
	 * is locked out because {@link #tryPin()} requires VALID.
	 */
	public boolean tryClaimForEviction() {
		for (;;) {
			long cur = word.get();
			if (decodeState(cur) != State.VALID || decodePinCount(cur) != 0 || decodeReferenced(cur)) {
				return false;
			}
			if (word.compareAndSet(cur, withState(cur, State.EVICTING))) {
				return true;
			}
		}
	}

	/** Gives a referenced VALID frame its second chance by clearing the bit. */
	public boolean clearReferenced() {
		for (;;) {
			long cur = word.get();
			if (decodeState(cur) != State.VALID || !decodeReferenced(cur)) {
				return false;
			}
			if (word.compareAndSet(cur, withReferenced(cur, false))) {
				return true;
			}
		}
	}

	// ------------------------------------------------------------ transitions

	/** FREE to LOADING. */
	public boolean tryBeginLoad() {
		return transition(State.FREE, State.LOADING);
	}

	/** LOADING to VALID. */
	public boolean finishLoad() {
		return transition(State.LOADING, State.VALID);
	}

	/** EVICTING to FLUSHING, for a victim whose page must be written back. */
	public boolean beginFlush() {
		return transition(State.EVICTING, State.FLUSHING);
	}

	/** LOADING to FREE, for a fill that failed before the frame was published. */
	public boolean abortLoad() {
		return recycle(State.LOADING);
	}

	/** EVICTING or FLUSHING to FREE, resetting the pin count and bumping the version. */
	public boolean finishEvict() {
		for (;;) {
			long cur = word.get();
			State state = decodeState(cur);
			if (state != State.EVICTING && state != State.FLUSHING) {
				return false;
			}
			if (word.compareAndSet(cur, recycled(cur, State.FREE))) {
				return true;
			}
		}
	}

	/** EVICTING or FLUSHING to LOADING, so the evictor keeps exclusive ownership. */
	public boolean reuseAfterEvict() {
		for (;;) {
			long cur = word.get();
			State state = decodeState(cur);
			if (state != State.EVICTING && state != State.FLUSHING) {
				return false;
			}
			if (word.compareAndSet(cur, recycled(cur, State.LOADING))) {
				return true;
			}
		}
	}

	/** EVICTING back to VALID, for an evictor that bails out. */
	public boolean abortEvict() {
		return transition(State.EVICTING, State.VALID);
	}

	/** FLUSHING back to VALID, for a flush that failed to land. Never transits FREE. */
	public boolean abortFlush() {
		return transition(State.FLUSHING, State.VALID);
	}

	private boolean transition(State expected, State target) {
		for (;;) {
			long cur = word.get();
			if (decodeState(cur) != expected) {
				return false;
			}
			if (word.compareAndSet(cur, withState(cur, target))) {
				return true;
			}
		}
	}

	private boolean recycle(State expected) {
		for (;;) {
			long cur = word.get();
			if (decodeState(cur) != expected) {
				return false;
			}
			if (word.compareAndSet(cur, recycled(cur, State.FREE))) {
				return true;
			}
		}
	}

	private static long recycled(long cur, State target) {
		return encode(target, 0L, false, (decodeVersion(cur) + 1) & VERSION_MASK);
	}

	// -------------------------------------------------------------- accessors

	public State state() {
		return decodeState(word.get());
	}

	/** Pin count as an unsigned value; widened to long because 32 bits do not fit an int. */
	public long pinCount() {
		return decodePinCount(word.get());
	}

	public boolean isReferenced() {
		return decodeReferenced(word.get());
	}

	public long version() {
		return decodeVersion(word.get());
	}

	/** The raw packed word, for callers that need a single consistent observation. */
	public long snapshot() {
		return word.get();
	}

	@Override
	public String toString() {
		return describe(word.get());
	}

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

	private static long withPinCount(long word, long pinCount) {
		return (word & ~PIN_MASK) | (pinCount & PIN_MASK);
	}

	private static long withState(long word, State state) {
		return (word & ~(STATE_MASK << STATE_SHIFT)) | ((long) state.ordinal() << STATE_SHIFT);
	}

	private static long withReferenced(long word, boolean referenced) {
		return referenced ? (word | REF_BIT) : (word & ~REF_BIT);
	}
}
