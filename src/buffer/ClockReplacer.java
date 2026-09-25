package buffer;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free clock (second-chance) victim selector over an array of
 * {@link FrameState}s.
 *
 * <p>A single shared hand is advanced with {@code getAndIncrement}, so
 * concurrent sweepers hand out successive slots to each other and fan out
 * across the pool instead of colliding on the same frame. Each examined frame
 * is either skipped (not VALID, or pinned), given its second chance (reference
 * bit cleared), or claimed with {@link FrameState#tryClaimForEviction()} — the
 * exclusive handoff that guarantees a frame is never returned to two callers.
 *
 * <p>A sweep is bounded at roughly two passes over the pool. When everything is
 * pinned the selector returns {@link OptionalInt#empty()} rather than spinning
 * or throwing; deciding what to do about a full pool is the caller's business.
 */
public final class ClockReplacer {

	/** Frames examined per {@link #findVictim()} call, as a multiple of the pool size. */
	private static final int SWEEP_PASSES = 2;

	private final FrameState[] frames;
	private final AtomicInteger hand = new AtomicInteger();
	private final AtomicInteger freeHand = new AtomicInteger();
	// pool-wide FREE count kept by the FrameStates themselves, or null to
	// always sweep; see claimFree
	private final AtomicInteger freeFrames;
	private final LongAdder freeSweeps = new LongAdder();

	public ClockReplacer(FrameState[] frames) {
		this(frames, null);
	}

	/**
	 * @param freeFrames the count every frame in {@code frames} reports its FREE
	 *                   entries and exits to, so a full pool is known without a
	 *                   sweep; null makes every {@link #claimFree()} sweep
	 */
	public ClockReplacer(FrameState[] frames, AtomicInteger freeFrames) {
		if (frames == null) {
			throw new NullPointerException("frames");
		}
		this.frames = frames;
		this.freeFrames = freeFrames;
	}

	/**
	 * Sweeps for an evictable frame and claims it.
	 *
	 * @return the index of a frame now owned by this caller in state
	 *         {@link FrameState.State#EVICTING}, or empty if the bounded sweep
	 *         found nothing evictable.
	 */
	public OptionalInt findVictim() {
		final int n = frames.length;
		if (n == 0) {
			return OptionalInt.empty();
		}
		final int budget = SWEEP_PASSES * n;
		for (int examined = 0; examined < budget; examined++) {
			int index = position(hand.getAndIncrement(), n);
			FrameState frame = frames[index];

			// One consistent observation decides whether this frame is even a
			// candidate; the claim below re-validates it atomically.
			long word = frame.snapshot();
			if (FrameState.decodeState(word) != FrameState.State.VALID) {
				continue;
			}
			if (FrameState.decodePinCount(word) > 0) {
				continue;
			}
			if (FrameState.decodeReferenced(word)) {
				// Second chance: cost it a sweep rather than evicting a frame
				// that was touched since the hand last passed. Guarded by the
				// snapshot above, so a reader that pinned the frame in the gap
				// keeps the reference bit it just set.
				frame.clearReferenced(word);
				continue;
			}
			if (frame.tryClaimForEviction()) {
				return OptionalInt.of(index);
			}
			// Lost the race, or the frame was pinned or re-referenced in the
			// gap. Move on; the hand has already advanced past it.
		}
		return OptionalInt.empty();
	}

	/**
	 * Sweeps for a frame that is FREE and claims it for filling.
	 *
	 * <p>Kept separate from the victim sweep on purpose: it has its own hand, so
	 * looking for a free frame never moves the clock or costs a VALID frame its
	 * second chance. The claim is the single compare-and-swap inside
	 * {@link FrameState#tryBeginLoad()}, never a read followed by a write, so of
	 * two threads seeing one frame as FREE exactly one leaves with it.
	 *
	 * <p>Once the pool has filled, no frame is FREE for the rest of a run except
	 * the victim each eviction recycles, so a sweep here would probe every frame
	 * on every miss and find nothing: O(pool) work per page read. The shared
	 * count short-circuits that. It is exact whenever it reads zero, so a frame
	 * that returns to FREE is always found by the next claim, never stranded.
	 *
	 * @return the index of a frame now owned by this caller in state
	 *         {@link FrameState.State#LOADING}, or empty if no frame was free.
	 */
	public OptionalInt claimFree() {
		if (freeFrames != null && freeFrames.get() <= 0) {
			return OptionalInt.empty();
		}
		freeSweeps.increment();
		final int n = frames.length;
		int start = freeHand.get();
		for (int offset = 0; offset < n; offset++) {
			int index = position(start + offset, n);
			if (frames[index].tryBeginLoad()) {
				// purely a hint for where the next claim starts looking, so a
				// racy update costs at most a wasted probe
				freeHand.set(index + 1);
				return OptionalInt.of(index);
			}
		}
		return OptionalInt.empty();
	}

	/** Number of {@link #claimFree()} calls that swept the pool. Exposed for tests. */
	public long freeSweeps() {
		return freeSweeps.sum();
	}

	/** Current hand position, normalised into the pool's index range. Exposed for tests. */
	public int hand() {
		int n = frames.length;
		return n == 0 ? 0 : position(hand.get(), n);
	}

	/** Number of frames this replacer sweeps over. */
	public int size() {
		return frames.length;
	}

	/** Maps a monotonically increasing counter onto an index, tolerating int overflow. */
	private static int position(int counter, int size) {
		return Math.floorMod(counter, size);
	}
}
