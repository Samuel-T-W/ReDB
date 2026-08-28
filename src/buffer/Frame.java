package buffer;

import storage.*;

public class Frame {
	// Pin count and page validity live in this packed atomic word, not in plain
	// fields: it is the same object the pool keeps in its index-aligned
	// FrameState[], so every pin/unpin is one CAS on shared state.
	public final FrameState state;
	public Page page;
	public boolean isDirty;
	public PageKey pageKey;

	public int frameIndex; // value persisted through clear's as it's attached to the index in buffer pool

	public Frame(int frameIndex, FrameState state) {
		this.frameIndex = frameIndex;
		this.state = state;
	}

	public boolean hasPage() {
		return this.state.state() == FrameState.State.VALID;
	}

	/**
	 * Publishes a filled frame: LOADING to VALID. The caller reaches LOADING by
	 * claiming the frame out of FREE, which is what makes the fill exclusive.
	 */
	public void markValid() {
		if (!state.finishLoad()) {
			throw new IllegalStateException("frame " + frameIndex + " is not claimed for filling: " + describeState());
		}
	}

	/**
	 * Takes one pin. A refusal means the frame is not VALID or the pin count is
	 * saturated; either way the caller would otherwise silently lose a pin and
	 * read a frame it does not own, so fail loudly instead.
	 */
	public void pin() {
		if (!state.tryPin()) {
			throw new IllegalStateException("cannot pin frame " + frameIndex + ": " + describeState());
		}
	}

	/**
	 * Empties a frame this caller does not yet own, by taking the eviction claim
	 * first and then finishing it.
	 *
	 * <p>A frame already in EVICTING or FLUSHING belongs to whoever claimed it.
	 * Treating any non-FREE state as this caller's own — which is what reaching
	 * straight for finishEvict amounts to — hands one frame to two owners: the
	 * claimant writing the page back to disk, and this caller freeing it out
	 * from underneath. So ownership is proved by winning the claim here, and a
	 * frame that is anyone else's is refused.
	 */
	public void clear() {
		FrameState.State current = state.state();
		if (current == FrameState.State.FREE) {
			// Nobody owns a FREE frame, so there are no fields to erase and
			// nothing here may touch them.
			return;
		}
		if (current != FrameState.State.VALID) {
			throw new IllegalStateException(
					"frame " + frameIndex + " is not this caller's to clear: " + describeState());
		}
		// Force the reference bit down so the claim's precondition can be met.
		// Unlike the clock sweeper this caller is not spending a second chance
		// it observed, so it retries over whatever the word currently reads.
		for (long snap = state.snapshot(); FrameState.decodeReferenced(snap); snap = state.snapshot()) {
			state.clearReferenced(snap);
		}
		if (!state.tryClaimForEviction()) {
			throw new IllegalStateException("cannot claim frame " + frameIndex + " to clear: " + describeState());
		}
		clearOwned();
	}

	/**
	 * Empties a frame this caller already holds in one of the exclusive states,
	 * EVICTING or FLUSHING, and publishes it FREE.
	 *
	 * <p>The fields are erased before the transition, not after. FREE is the
	 * advertisement that a frame may be claimed and refilled, so a frame that
	 * reaches FREE still holding its predecessor's page hands that page to its
	 * next owner, and the previous owner's trailing writes then land on top of
	 * the new owner's. Holding the exclusive claim is what makes erasing first
	 * safe: no sweeper can select the frame and no reader can pin it, so the
	 * half-erased frame is visible to nobody.
	 */
	public void clearOwned() {
		FrameState.State current = state.state();
		if (current != FrameState.State.EVICTING && current != FrameState.State.FLUSHING) {
			throw new IllegalStateException(
					"frame " + frameIndex + " is not claimed by this caller: " + describeState());
		}
		if (!state.finishEvict()) {
			throw new IllegalStateException("cannot clear frame " + frameIndex + ": " + describeState());
		}
		this.page = null;
		this.isDirty = false;
		this.pageKey = null;
	}

	private String describeState() {
		return FrameState.describe(state.snapshot());
	}
}
