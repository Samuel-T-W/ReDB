package buffer;

import java.io.File;
import storage.RawPage;

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
		int pageId = Math.max(diskPageCount(), nextPageId);
		nextPageId = pageId + 1;
		return pageId;
	}

	private int diskPageCount() throws ArithmeticException, IllegalStateException {
		File file = new File(fileId);

		// don't expect long amount of bytes for this project, throw if encountered
		int fileLength = Math.toIntExact(file.length());

		// all pages should be exactly RawPage.MAX_PAGE_LEN long
		if (fileLength % RawPage.MAX_PAGE_LEN != 0) {
			throw new IllegalStateException("File size is not a multiple of pages");
		}

		return fileLength / RawPage.MAX_PAGE_LEN;
	}
}
