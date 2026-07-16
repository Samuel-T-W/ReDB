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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import storage.*;

public class BufferManager {

	// configurable size of buffer cache.
	final int bufferSize;

	private final Map<String, CatalogEntry> catalog;
	private Map<String, FileState> fileStates = new HashMap<>();
	private final ReentrantLock fileStatesLock = new ReentrantLock();
	private Map<PageKey, Integer> pageTable;
	private Frame[] bufferPool;
	private Queue<Integer> freeFrameIndices;

	// Global lock guarding all buffer pool state: pageTable (including its LRU
	// order), bufferPool frames (pin counts, dirty flags, contents),
	// freeFrameIndices, and the in-flight maps below. Page loads and dirty
	// eviction flushes happen OUTSIDE the lock so one thread's disk I/O never
	// blocks another thread's cache hits; force() keeps the coarse lock (rare).
	private final ReentrantLock globalLock = new ReentrantLock();

	// In-flight disk I/O markers, guarded by globalLock. A load marker means
	// some thread is reading that page from disk; a flush marker means an
	// evicted dirty page is being written back. getPage waits on these futures
	// outside the lock and then retries the lookup, so each caller pins for
	// itself under the lock and a page mid-flush is never re-read from disk
	// before its latest bytes have landed (stale-read prevention).
	private final Map<PageKey, CompletableFuture<Page>> inFlightLoads = new HashMap<>();
	private final Map<PageKey, CompletableFuture<Void>> inFlightFlushes = new HashMap<>();

	// I/O counters: read = disk loads (cache misses), write = pages written to disk
	private final LongAdder readIOCount = new LongAdder();
	private final LongAdder writeIOCount = new LongAdder();

	public BufferManager(int bufferSize) {
		this.bufferSize = bufferSize;
		this.pageTable = new LinkedHashMap<>();
		this.bufferPool = new Frame[bufferSize];
		this.freeFrameIndices = new LinkedList<>();

		this.catalog = new ConcurrentHashMap<>();

		initializeBufferManager();
	}

	private void initializeBufferManager() {
		// add all free indices
		for (int i = 0; i < bufferSize; i++) {
			freeFrameIndices.add(i);
		}
	}

	// Register a table or index in the system catalog. Idempotent: re-registering
	// a file just overwrites its entry, so shared-manager callers can register
	// the same catalog more than once without harm.
	public void register(CatalogEntry entry) {
		catalog.put(entry.fileName(), entry);
	}

	public CatalogEntry getCatalogEntry(String fileName) {
		return catalog.get(fileName);
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

		while (true) {
			CompletableFuture<Page> loadInProgress;
			CompletableFuture<Void> flushInProgress = null;
			CompletableFuture<Page> myLoad = null;

			globalLock.lock();
			try {
				// get from buffer pool
				if (pageTable.containsKey(pageKey)) {
					movePageToBottomOfLru(pageKey);
					Frame frame = this.bufferPool[pageTable.get(pageKey)];
					frame.pinCount++;
					return frame.page;
				}

				loadInProgress = inFlightLoads.get(pageKey);
				if (loadInProgress == null) {
					// reading disk while this page's eviction flush is still in
					// flight would return stale bytes; the flush must finish first
					flushInProgress = inFlightFlushes.get(pageKey);
					if (flushInProgress == null) {
						// genuine miss: claim the load so racing threads wait on it
						// instead of issuing duplicate disk reads
						myLoad = new CompletableFuture<>();
						inFlightLoads.put(pageKey, myLoad);
					}
				}
			} finally {
				globalLock.unlock();
			}

			// wait outside the lock, then retry the lookup: each caller must pin
			// for itself under the lock, and the page may have been evicted again
			// by the time the future completes
			if (loadInProgress != null) {
				awaitQuietly(loadInProgress);
				continue;
			}
			if (flushInProgress != null) {
				awaitQuietly(flushInProgress);
				continue;
			}

			// this thread owns the load; the disk read happens outside the lock
			try {
				readIOCount.increment();
				byte[] loaded_data = new byte[RawPage.MAX_PAGE_LEN];
				try (RandomAccessFile raf = new RandomAccessFile(fileId, "r")) {
					raf.seek(RawPage.getOffset(pageId));
					raf.readFully(loaded_data);
				}
				RawPage page = new RawPage(pageId);
				page.fillPageData(loaded_data);

				globalLock.lock();
				try {
					Page pinned = addToFrame(pageKey, page, true);
					inFlightLoads.remove(pageKey);
					myLoad.complete(pinned);
					return pinned;
				} finally {
					globalLock.unlock();
				}
			} catch (IOException | RuntimeException e) {
				globalLock.lock();
				try {
					inFlightLoads.remove(pageKey);
				} finally {
					globalLock.unlock();
				}
				// waiters retry and surface their own error from their own attempt
				myLoad.completeExceptionally(e);
				throw e;
			}
		}
	}

	/**
	 * Waits for an in-flight disk operation owned by another thread. Failures
	 * are deliberately swallowed: the caller retries the lookup and either
	 * succeeds or raises its own exception from its own attempt.
	 */
	private static void awaitQuietly(CompletableFuture<?> future) {
		try {
			future.join();
		} catch (CompletionException | CancellationException ignored) {
		}
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

		// freshly allocated page id: no other thread can reference this key yet,
		// so no in-flight load marker is needed around addToFrame
		globalLock.lock();
		try {
			addToFrame(pageKey, page, true);
		} finally {
			globalLock.unlock();
		}
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
		globalLock.lock();
		try {
			Integer frameIndex = pageTable.get(pageKey);
			if (frameIndex == null) {
				throw new IllegalArgumentException("Page not in buffer: " + pageKey);
			}
			Frame frame = bufferPool[frameIndex];
			if (frame.hasPage()) {
				frame.isDirty = true;
			}
		} finally {
			globalLock.unlock();
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
		globalLock.lock();
		try {
			Integer frameIndex = pageTable.get(pageKey);
			if (frameIndex == null) {
				throw new IllegalArgumentException("Page not in buffer: " + pageKey);
			}
			Frame frame = bufferPool[frameIndex];
			if (frame.pinCount > 0)
				frame.pinCount--;
		} finally {
			globalLock.unlock();
		}
	}

	/** Forces all dirty pages currently in memory to be written back to disk. */
	public void force() throws IOException {
		globalLock.lock();
		try {
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
		} finally {
			globalLock.unlock();
		}
	}

	/** HELPER FUNCTIONS SECTIONS */

	/**
	 * Evicts the LRU unpinned page and returns its frame index for immediate
	 * reuse by the caller; throws if all frames are pinned. Must be called with
	 * globalLock held.
	 *
	 * <p>If the victim is dirty, the victim is removed from the page table and
	 * an in-flight flush marker is installed BEFORE the lock is released for
	 * the disk write, so a concurrent getPage for the victim's key waits for
	 * the flush instead of reading stale bytes from disk. The reclaimed frame
	 * is handed straight to the caller (never through freeFrameIndices), so no
	 * other thread can claim it while the lock is dropped.
	 */
	private int evict() throws RuntimeException, IOException {

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

		PageKey victimKey = evictFrame.pageKey;
		pageTable.remove(victimKey);

		// if frame dirty write to disk first, outside the lock
		if (evictFrame.isDirty) {
			CompletableFuture<Void> flush = new CompletableFuture<>();
			inFlightFlushes.put(victimKey, flush);
			globalLock.unlock();
			IOException failure = null;
			try {
				writePageToDisk(victimKey.fileId(), evictFrame.page);
			} catch (IOException e) {
				failure = e;
			} finally {
				globalLock.lock();
				inFlightFlushes.remove(victimKey);
			}
			if (failure != null) {
				// put the victim back so its latest bytes stay reachable in
				// memory; waiters retry and find it in the page table
				pageTable.put(victimKey, evictFrame.frameIndex);
				flush.completeExceptionally(failure);
				throw failure;
			}
			flush.complete(null);
		}

		// evict frame content and hand the frame to the caller
		int frameIndex = evictFrame.frameIndex;
		evictFrame.clear();
		return frameIndex;
	}

	/** Write a page to disk. */
	private void writePageToDisk(String fileId, Page page) throws IOException {
		writeIOCount.increment();
		try (RandomAccessFile raf = new RandomAccessFile(fileId, "rw")) {
			int offset = RawPage.getOffset(page.getPid());
			raf.seek(offset);
			raf.write(page.getByteArray());
		}
	}

	/**
	 * Places a page into a free frame, evicting if needed. Must be called with
	 * globalLock held. May temporarily release the lock while a dirty victim is
	 * flushed (see evict); the caller's in-flight load marker (or the fresh page
	 * id, for createPage) keeps pageKey invisible to other threads meanwhile.
	 */
	private Page addToFrame(PageKey pageKey, Page page, boolean is_pinned) throws IOException, IllegalStateException {

		// take a free frame, evicting to reclaim one if none are free
		Integer freeFrameIndex = freeFrameIndices.poll();
		if (freeFrameIndex == null) {
			freeFrameIndex = evict();
		}

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

		return page;
	}

	private void movePageToBottomOfLru(PageKey pageKey) {
		// by removing and re-inserting in a linkedHashMap pageTable we position the
		// page at the bottom of lru
		int index = pageTable.remove(pageKey);
		pageTable.put(pageKey, index);
	}

	public void resetIOCounts() { readIOCount.reset(); writeIOCount.reset(); }
	public long getReadIOCount()  { return readIOCount.sum();  }
	public long getWriteIOCount() { return writeIOCount.sum(); }
	public long getTotalIOCount() { return readIOCount.sum() + writeIOCount.sum(); }

	// For testing only
	public int[] listPageID() {
		globalLock.lock();
		try {
			int[] pageID = new int[pageTable.size()];
			Iterator<Map.Entry<PageKey, Integer>> iter = pageTable.entrySet().iterator();
			int i = 0;
			while (iter.hasNext()) {
				Map.Entry<PageKey, Integer> entry = iter.next();
				pageID[i] = this.bufferPool[entry.getValue()].page.getPid();
				i++;
			}
			return pageID;
		} finally {
			globalLock.unlock();
		}
	}

	// For testing only
	public int getTotalPinCount() {
		globalLock.lock();
		try {
			int total = 0;
			for (Integer frameIndex : pageTable.values()) {
				total += bufferPool[frameIndex].pinCount;
			}
			return total;
		} finally {
			globalLock.unlock();
		}
	}

	// package-private, for concurrency tests: frames currently in the free list.
	// In a quiescent pool, free frames + pageTable entries == bufferSize (a frame
	// mid-flush is briefly in neither).
	int getFreeFrameCount() {
		globalLock.lock();
		try {
			return freeFrameIndices.size();
		} finally {
			globalLock.unlock();
		}
	}

	// For testing only
	public int getPinCount(String fileId, int pid) {
		PageKey pageKey = new PageKey(fileId, pid);

		globalLock.lock();
		try {
			// get from buffer pool
			if (pageTable.containsKey(pageKey)) {
				Frame frame = this.bufferPool[pageTable.get(pageKey)];
				return frame.pinCount;
			} else {
				return -1;
			}
		} finally {
			globalLock.unlock();
		}
	}
}
