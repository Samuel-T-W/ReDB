package buffer;

import java.util.concurrent.atomic.AtomicBoolean;
import storage.Page;

/**
 * One pin taken on one incarnation of a frame.
 *
 * <p>The (frameIndex, version) pair names the pin: {@code finishEvict} bumps
 * the version on every return to FREE, so a late or duplicated unpin of this
 * handle cannot decrement a pin that now belongs to a recycled page. The
 * handle is single-use — {@link #markReleased()} succeeds once — because two
 * unpins of the same incarnation would otherwise drop a sibling holder's pin.
 */
public final class PageHandle {

	private final int frameIndex;
	private final long version;
	private final PageKey key;
	private final Page page;
	private final AtomicBoolean released = new AtomicBoolean(false);

	PageHandle(int frameIndex, long version, PageKey key, Page page) {
		if (frameIndex < 0) {
			throw new IllegalArgumentException("frameIndex must be non-negative");
		}
		if (version < 0) {
			throw new IllegalArgumentException("version must be non-negative");
		}
		if (key == null || page == null) {
			throw new NullPointerException("page handle requires a key and a page");
		}
		this.frameIndex = frameIndex;
		this.version = version;
		this.key = key;
		this.page = page;
	}

	public Page page() {
		return page;
	}

	public PageKey key() {
		return key;
	}

	public String fileId() {
		return key.fileId();
	}

	public int pageId() {
		return key.pageId();
	}

	int frameIndex() {
		return frameIndex;
	}

	long version() {
		return version;
	}

	/**
	 * Claims the single unpin this handle represents.
	 *
	 * @return true on the first call, false if the pin was already released
	 */
	boolean markReleased() {
		return released.compareAndSet(false, true);
	}

	boolean isReleased() {
		return released.get();
	}

	@Override
	public String toString() {
		return "PageHandle[frame=" + frameIndex + " version=" + version + " " + key + "]";
	}
}
