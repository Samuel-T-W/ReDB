package buffer;

import catalog.CatalogEntry;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;
import storage.*;

public class BufferManager {

	// configurable size of buffer cache.
	final int bufferSize;

	private Map<String, CatalogEntry> catalog;
	private Map<String, FileState> fileStates = new HashMap<>();
	private final ReentrantLock fileStatesLock = new ReentrantLock();
	private Map<PageKey, Integer> pageTable;
	private Frame[] bufferPool;
	private Queue<Integer> freeFrameIndices;
	private BufferTraceListener traceListener;

	// I/O counters: read = disk loads (cache misses), write = pages written to disk
	private long readIOCount = 0;
	private long writeIOCount = 0;

	public BufferManager(int bufferSize) {
		this.bufferSize = bufferSize;
		this.pageTable = new LinkedHashMap<>();
		this.bufferPool = new Frame[bufferSize];
		this.freeFrameIndices = new LinkedList<>();

		this.catalog = new HashMap<>();

		initializeBufferManager();
	}

	private void initializeBufferManager() {
		// add all free indices
		for (int i = 0; i < bufferSize; i++) {
			freeFrameIndices.add(i);
		}
	}

	// Register a table or index in the system catalog
	public void register(CatalogEntry entry) {
		catalog.put(entry.fileName(), entry);
	}

	public CatalogEntry getCatalogEntry(String fileName) {
		return catalog.get(fileName);
	}

	public void setTraceListener(BufferTraceListener traceListener) {
		this.traceListener = traceListener;
	}

	/**
	 * Fetches a page from memory if available; otherwise, loads it from disk. The
	 * page is immediately pinned.
	 *
	 * @param fileId
	 *            The file identifier / file name.
	 * @param pageId
	 *            The ID of the page to fetch.
	 * @return The Page object whose content is stored in a frame of the buffer pool
	 *         manager.
	 */
	public Page getPage(String fileId, int pageId) throws IOException {
		PageKey pageKey = new PageKey(fileId, pageId);

		// get from buffer pool
		if (pageTable.containsKey(pageKey)) {
			movePageToBottomOfLru(pageKey);
			Frame frame = this.bufferPool[pageTable.get(pageKey)];
			frame.pinCount++;
			if (traceListener != null) {
				traceListener.onBufferHit(fileId, pageId, frame.frameIndex, frame.isDirty, frame.pinCount);
			}
			return frame.page;
		}

		// cache miss → disk load
		readIOCount++;
		if (traceListener != null) {
			traceListener.onBufferMiss(fileId, pageId);
		}

		// load from file
		byte[] loaded_data = null;

		try (RandomAccessFile raf = new RandomAccessFile(fileId, "r")) {
			int offset = RawPage.getOffset(pageId);
			raf.seek(offset);
			loaded_data = new byte[RawPage.MAX_PAGE_LEN];
			raf.readFully(loaded_data);
		}

		RawPage page = new RawPage(pageId);
		page.fillPageData(loaded_data);

		return addToFrame(pageKey, page, true);
	}

	/** Returns the FileState for the given file, creating it on first use. */
	// package-private so concurrency tests can exercise the fileStatesLock directly
	FileState getOrCreateFileState(String fileId) {
		fileStatesLock.lock();
		try {
			FileState fileState = fileStates.get(fileId);
			if (fileState == null) {
				fileState = new FileState(fileId);
				fileStates.put(fileId, fileState);
			}
			return fileState;
		} finally {
			fileStatesLock.unlock();
		}
	}

	/**
	 * Creates a new RawPage in the buffer pool. The page is immediately pinned.
	 * Callers can optionally pass in a byte array to initialize the page data (e.g.
	 * serialized from a GenericPage or IndexPage). If null, the page starts with a
	 * zeroed byte array.
	 *
	 * @param fileId
	 *            The file identifier / file name.
	 * @param data
	 *            Optional byte array to initialize the page, or null for empty.
	 * @return The RawPage whose content is stored in a frame of the buffer pool.
	 */
	public RawPage createPage(String fileId, byte[] data) throws IOException {
		int nextPageId = getOrCreateFileState(fileId).allocatePageId();
		PageKey pageKey = new PageKey(fileId, nextPageId);

		RawPage page = new RawPage(nextPageId);
		if (data != null) {
			page.fillPageData(data);
		}

		addToFrame(pageKey, page, true);
		return page;
	}

	/**
	 * Marks a page as dirty, indicating it needs to be written to disk before
	 * eviction.
	 *
	 * @param fileId
	 *            The file identifier / file name.
	 * @param pageId
	 *            The ID of the page to mark as dirty.
	 */
	public void markDirty(String fileId, int pageId) {
		PageKey pageKey = new PageKey(fileId, pageId);
		Integer frameIndex = pageTable.get(pageKey);
		if (frameIndex == null) {
			throw new IllegalArgumentException("Page not in buffer: " + pageKey);
		}
		Frame frame = bufferPool[frameIndex];
		if (frame.hasPage()) {
			frame.isDirty = true;
		}
	}

	/**
	 * Unpins a page in the buffer pool, allowing it to be evicted if necessary.
	 *
	 * @param fileId
	 *            The file identifier / file name.
	 * @param pageId
	 *            The ID of the page to unpin.
	 */
	public void unpinPage(String fileId, int pageId) {
		PageKey pageKey = new PageKey(fileId, pageId);
		Integer frameIndex = pageTable.get(pageKey);
		if (frameIndex == null) {
			throw new IllegalArgumentException("Page not in buffer: " + pageKey);
		}
		Frame frame = bufferPool[frameIndex];
		if (frame.pinCount > 0)
			frame.pinCount--;
	}

	/** Forces all dirty pages currently in memory to be written back to disk. */
	public void force() throws IOException {
		Iterator<Map.Entry<PageKey, Integer>> iter = pageTable.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<PageKey, Integer> entry = iter.next();
			Frame frame = bufferPool[entry.getValue()];
			if (!frame.isDirty)
				continue;

			// if dirty: write to disk and clear dirty flag
			PageKey pageKey = entry.getKey();
			writePageToDisk(pageKey.fileId(), frame.page);
			frame.isDirty = false;
		}
	}

	/** HELPER FUNCTIONS SECTIONS */

	/** Evicts a page from the buffer pool and throws if all frames are pinned. */
	private void evict() throws RuntimeException, IOException {

		// loop and grab lru page thats unpinned
		Frame evictFrame = null;
		for (Map.Entry<PageKey, Integer> entry : pageTable.entrySet()) {
			Frame frame = bufferPool[entry.getValue()];
			if (frame.pinCount == 0) {
				evictFrame = frame;
				break;
			}
		}

		if (evictFrame == null)
			throw new RuntimeException("All frames are pinned, cannot evict");

		// if frame dirty write to disk first
		if (evictFrame.isDirty) {
			writePageToDisk(evictFrame.pageKey.fileId(), evictFrame.page);
		}
		if (traceListener != null) {
			traceListener.onPageEvict(
					evictFrame.pageKey.fileId(),
					evictFrame.pageKey.pageId(),
					evictFrame.frameIndex,
					evictFrame.isDirty,
					evictFrame.pinCount);
		}

		// evict frame content
		evictCleanup(evictFrame);
	}

	/** Write a page to disk. */
	private void writePageToDisk(String fileId, Page page) throws IOException {
		writeIOCount++;
		try (RandomAccessFile raf = new RandomAccessFile(fileId, "rw")) {
			int offset = RawPage.getOffset(page.getPid());
			raf.seek(offset);
			raf.write(page.getByteArray());
		}
		if (traceListener != null) {
			traceListener.onBufferFlush(fileId, page.getPid());
		}
	}

	private Page addToFrame(PageKey pageKey, Page page, boolean is_pinned) throws IOException, IllegalStateException {

		// attempt eviction if buffer is full
		if (freeFrameIndices.isEmpty()) {
			evict();
		}

		// get a free frame index
		int freeFrameIndex = freeFrameIndices.poll();

		// load if frame object instantiated otherwise create a new one
		if (bufferPool[freeFrameIndex] == null) {
			bufferPool[freeFrameIndex] = new Frame(freeFrameIndex);
		}
		Frame frame = bufferPool[freeFrameIndex];

		// assert page is empty
		if (frame.hasPage()) {
			throw new IllegalStateException("Expected Free Frame object");
		}

		// assign page to frame
		frame.page = page;
		frame.pageKey = pageKey;
		if (is_pinned) {
			frame.pinCount++;
		}

		// add page to page table and automatically moves it to the bottom of the lru
		pageTable.put(pageKey, freeFrameIndex);
		if (traceListener != null) {
			traceListener.onPageLoad(pageKey.fileId(), pageKey.pageId(), frame.frameIndex, frame.isDirty, frame.pinCount);
		}

		return page;
	}

	private void evictCleanup(Frame evictFrame) {
		pageTable.remove(evictFrame.pageKey);
		evictFrame.clear();
		freeFrameIndices.add(evictFrame.frameIndex);
	}

	private void movePageToBottomOfLru(PageKey pageKey) {
		// by removing and re-inserting in a linkedHashMap pageTable we position the
		// page at the bottom of lru
		int index = pageTable.remove(pageKey);
		pageTable.put(pageKey, index);
	}

	public void resetIOCounts() { readIOCount = 0; writeIOCount = 0; }
	public long getReadIOCount()  { return readIOCount;  }
	public long getWriteIOCount() { return writeIOCount; }
	public long getTotalIOCount() { return readIOCount + writeIOCount; }

	// For testing only
	public int[] listPageID() {
		int[] pageID = new int[pageTable.size()];
		Iterator<Map.Entry<PageKey, Integer>> iter = pageTable.entrySet().iterator();
		int i = 0;
		while (iter.hasNext()) {
			Map.Entry<PageKey, Integer> entry = iter.next();
			pageID[i] = this.bufferPool[entry.getValue()].page.getPid();
			i++;
		}
		return pageID;
	}

	// For testing only
	public int getPinCount(String fileId, int pid) {
		PageKey pageKey = new PageKey(fileId, pid);

		// get from buffer pool
		if (pageTable.containsKey(pageKey)) {
			Frame frame = this.bufferPool[pageTable.get(pageKey)];
			return frame.pinCount;
		} else {
			return -1;
		}
	}
}
