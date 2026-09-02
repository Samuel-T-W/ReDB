package buffer;

import java.io.File;
import java.util.concurrent.locks.ReentrantLock;
import storage.RawPage;
import storage.UnaddressablePageException;

/**
 * Per-file runtime state owned by the BufferManager. Currently tracks the next
 * page ID to hand out for a file.
 *
 * <p>The watermark covers page IDs that have been allocated into the buffer pool
 * but not yet flushed to disk — the on-disk file length alone cannot reflect
 * those uncommitted pages. This is the natural home for additional per-file
 * bookkeeping (e.g. latches) as concurrency is introduced.
 */
class FileState {

	private final String fileId;
	private final ReentrantLock lock = new ReentrantLock();
	private int nextPageId;

	FileState(String fileId) {
		this.fileId = fileId;
	}

	/**
	 * Returns the next available page ID for this file and advances the watermark.
	 * Takes the max of the on-disk page count and the in-memory watermark so IDs
	 * already durable on disk are never reused (e.g. on a fresh BufferManager over
	 * an existing file).
	 */
	int allocatePageId() {
		lock.lock();
		try {
			int pageId = Math.max(diskPageCount(), nextPageId);
			if (pageId >= RawPage.MAX_PAGE_COUNT) {
				throw new UnaddressablePageException("Cannot allocate page " + pageId + " in " + fileId + ": a file holds at "
						+ "most " + RawPage.MAX_PAGE_COUNT + " pages (" + RawPage.MAX_FILE_LEN + " bytes, ~8.8 TB). "
						+ "Page ids are signed 32-bit ints, so one more would wrap negative.");
			}
			nextPageId = pageId + 1;
			return pageId;
		} finally {
			lock.unlock();
		}
	}

	private int diskPageCount() throws IllegalStateException {
		return RawPage.pageCount(fileId, new File(fileId).length());
	}
}
