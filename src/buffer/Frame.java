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

	/** Publishes a newly filled frame: FREE to LOADING to VALID. */
	public void markValid() {
		if (!state.tryBeginLoad() || !state.finishLoad()) {
			throw new IllegalStateException("frame " + frameIndex + " is not free to fill: " + describeState());
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

	public void clear() {
		this.page = null;
		this.isDirty = false;
		this.pageKey = null;
		resetState();
	}

	/** Drives the state word back to FREE with pin count 0 and a bumped version. */
	private void resetState() {
		if (state.state() == FrameState.State.FREE) {
			return;
		}
		state.clearReferenced();
		if (!state.tryClaimForEviction() || !state.finishEvict()) {
			throw new IllegalStateException("cannot clear frame " + frameIndex + ": " + describeState());
		}
	}

	private String describeState() {
		return FrameState.describe(state.snapshot());
	}
}
