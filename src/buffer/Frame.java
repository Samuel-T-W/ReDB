package buffer;

import storage.*;

public class Frame {
	// Pin count and page validity live in this packed atomic word, not in plain
	// fields: it is the same object the pool keeps in its index-aligned
	// FrameState[], so every pin/unpin is one CAS on shared state.
	public final FrameState state;
	public Page page;
	public volatile boolean isDirty;
	public volatile PageKey pageKey;

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
		if (!state.finishLoadAndPin()) {
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
	 * Empties the frame. The state transition runs first so the operation is
	 * all-or-nothing: a refused transition throws with the frame's fields still
	 * intact, never leaving a frame that reports hasPage() while holding no page.
	 */
	public void clear() {
		resetState();
		this.page = null;
		this.isDirty = false;
		this.pageKey = null;
	}

	/** Drives the state word back to FREE with pin count 0 and a bumped version. */
	private void resetState() {
		FrameState.State current = state.state();
		if (current == FrameState.State.FREE) {
			return;
		}
		// A victim handed over by the clock replacer arrives already claimed in
		// EVICTING and exclusively owned by this caller, so it only needs
		// finishing. An unclaimed VALID frame must be claimed here first.
		if (current == FrameState.State.VALID) {
			state.clearReferenced();
			if (!state.tryClaimForEviction()) {
				throw new IllegalStateException("cannot claim frame " + frameIndex + " to clear: " + describeState());
			}
		}
		if (!state.finishEvict()) {
			throw new IllegalStateException("cannot clear frame " + frameIndex + ": " + describeState());
		}
	}

	private String describeState() {
		return FrameState.describe(state.snapshot());
	}
}
