package buffer;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

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

	public ClockReplacer(FrameState[] frames) {
		if (frames == null) {
			throw new NullPointerException("frames");
		}
		this.frames = frames;
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
				// that was touched since the hand last passed.
				frame.clearReferenced();
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
