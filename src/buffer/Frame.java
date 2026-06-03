package buffer;

import storage.*;

public class Frame {
	public int pinCount;
	public Page page;
	public boolean isDirty;
	public PageKey pageKey;

	public int frameIndex; // value persisted through clear's as it's attached to the index in buffer pool

	public Frame(int frameIndex) {
		this.frameIndex = frameIndex;
	}

	public boolean hasPage() {
		return this.page != null;
	}

	public void clear() {
		this.page = null;
		this.pinCount = 0;
		this.isDirty = false;
		this.pageKey = null;
	}
}
