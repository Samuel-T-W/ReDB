package buffer;

import catalog.CatalogEntry;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
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
	// Packed atomic state word per frame, index-aligned with bufferPool and
	// populated in full at construction: bufferPool entries are built lazily, so
	// the words cannot hang off the Frame objects without leaving holes. Each
	// lazily built Frame adopts the word already sitting at its index, which is
	// also what the clock replacer will sweep over.
	final FrameState[] frameStates;
	// Second-chance victim selector over frameStates. Replaces the old scan of
	// the page table's LRU order: it sweeps the frames themselves and hands back
	// a victim already claimed in EVICTING, so no other thread can pin or
	// re-evict it while this thread drops globalLock to flush.
	private final ClockReplacer clockReplacer;

	// force, discardFile, and parking LOADING/FLUSHING waiters. Hits, misses,
	// pins, and eviction claims do not take this lock.
	private final ReentrantLock globalLock = new ReentrantLock();

	// A LOADING or FLUSHING frame stays in the page table, so a reader finds it
	// and parks here instead of issuing a duplicate read or seeing stale disk
	// bytes. Signalled when the frame settles (VALID or gone).
	private final Condition flushSettled = globalLock.newCondition();

	// I/O counters: read = disk loads (cache misses), write = pages written to disk
	private final LongAdder readIOCount = new LongAdder();
	private final LongAdder writeIOCount = new LongAdder();

	public BufferManager(int bufferSize) {
		this.bufferSize = bufferSize;
		this.pageTable = new ConcurrentHashMap<>();
		this.bufferPool = new Frame[bufferSize];
		this.frameStates = new FrameState[bufferSize];

		this.catalog = new ConcurrentHashMap<>();

		initializeBufferManager();
		this.clockReplacer = new ClockReplacer(frameStates);
	}

	private void initializeBufferManager() {
		// FREE is the pool's only record of a frame being available
		for (int i = 0; i < bufferSize; i++) {
			frameStates[i] = new FrameState();
		}
	}

	// Register a table or index in the system catalog. Idempotent: re-registering
	// a file just overwrites its entry, so shared-manager callers can register
	// the same catalog more than once without harm.
	public void register(CatalogEntry entry) {
		catalog.put(entry.fileName(), entry);
	}

	// Remove a table or index from the system catalog. Per-query temp tables
	// are unregistered on the query's close path so a long-lived shared
	// manager's catalog does not accumulate dead entries. Removing an absent
	// entry is harmless.
	public void unregister(String fileName) {
		catalog.remove(fileName);
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
		for (;;) {
			Integer hot = pageTable.get(pageKey);
			if (hot != null) {
				Frame frame = bufferPool[hot];
				if (frame != null && frame.state.tryPin()) {
					if (pageKey.equals(frame.pageKey)) {
						return frame.page;
					}
					frame.state.unpin();
					pageTable.remove(pageKey, hot);
				} else {
					awaitIo();
				}
				continue;
			}
			int claimed = claimFrame();
			if (!installLoading(claimed, pageKey)) {
				releaseClaim(claimed, null);
				continue;
			}
			try {
				Page page = readPageFromDisk(pageKey);
				Frame frame = bufferPool[claimed];
				frame.page = page;
				frame.markValid();
				return page;
			} catch (IOException | RuntimeException e) {
				pageTable.remove(pageKey, claimed);
				releaseClaim(claimed, e);
				if (e instanceof IOException ioe) throw ioe;
				throw (RuntimeException) e;
			} finally {
				signalSettled();
			}
		}
	}

	private void awaitIo() {
		globalLock.lock();
		try {
			awaitFlushSettled();
		} finally {
			globalLock.unlock();
		}
	}

	private void signalSettled() {
		globalLock.lock();
		try {
			flushSettled.signalAll();
		} finally {
			globalLock.unlock();
		}
	}

	private boolean installLoading(int claimed, PageKey pageKey) {
		if (bufferPool[claimed] == null) {
			try {
				bufferPool[claimed] = new Frame(claimed, frameStates[claimed]);
			} catch (RuntimeException | Error t) {
				frameStates[claimed].abortLoad();
				throw t;
			}
		}
		Frame frame = bufferPool[claimed];
		if (frame.page != null)
			throw new IllegalStateException("Expected Free Frame object");
		frame.pageKey = pageKey;
		if (pageTable.putIfAbsent(pageKey, claimed) != null) {
			frame.pageKey = null;
			return false;
		}
		return true;
	}

	/** Parks (never spins) until a load or flush settles. globalLock must be held. */
	private void awaitFlushSettled() {
		try {
			flushSettled.await(50, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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

		// freshly allocated page id: no other thread can reference this key yet
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
		if (frame != null && pageKey.equals(frame.pageKey) && frame.hasPage()) {
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
		if (frame != null && pageKey.equals(frame.pageKey) && frame.state.pinCount() > 0)
			frame.state.unpin();
		signalSettled();
	}

	/** Forces all dirty pages currently in memory to be written back to disk. */
	public void force() throws IOException {
		globalLock.lock();
		try {
			for (;;) {
				boolean flushing = false;
				for (Map.Entry<PageKey, Integer> entry : pageTable.entrySet()) {
					PageKey key = entry.getKey();
					Frame frame = bufferPool[entry.getValue()];
					if (frame == null || !key.equals(frame.pageKey))
						continue;
					if (!frame.hasPage()) {
						flushing = true;
						continue;
					}
					if (!frame.isDirty)
						continue;
					if (!frame.state.tryPin()) {
						flushing = true;
						continue;
					}
					try {
						if (!key.equals(frame.pageKey) || !frame.isDirty)
							continue;
						writePageToDisk(key.fileId(), frame.page);
						frame.isDirty = false;
					} finally {
						frame.state.unpin();
					}
				}
				if (!flushing)
					return;
				awaitFlushSettled();
			}
		} finally {
			globalLock.unlock();
		}
	}

	/**
	 * Drops every buffered page of the given file from the pool, freeing its
	 * frames for reuse, and forgets the file's page-id allocation state. For
	 * per-query temp and scratch files whose pages are dead once the query
	 * closes; discarded dirty pages are deliberately NOT written back, since
	 * the file itself is about to be deleted.
	 *
	 * <p>The caller must be done with the file: every page of it must already
	 * be unpinned (throws IllegalStateException otherwise), and no concurrent
	 * getPage/createPage calls for this fileId may race with the discard. The
	 * one concurrent interaction that is tolerated is another thread's
	 * eviction flush of one of this file's pages (any thread may evict an
	 * unpinned dirty page): the discard waits for the file's in-flight
	 * loads/flushes to drain and re-checks, so on return the pool holds no
	 * frame, page-table entry, or in-flight I/O for the fileId. Call this
	 * BEFORE deleting the file, or a draining flush could recreate it.
	 */
	public void discardFile(String fileId) {
		while (true) {
			boolean inFlight = false;
			globalLock.lock();
			try {
				Iterator<Map.Entry<PageKey, Integer>> iter = pageTable.entrySet().iterator();
				while (iter.hasNext()) {
					Map.Entry<PageKey, Integer> entry = iter.next();
					if (!entry.getKey().fileId().equals(fileId))
						continue;
					Frame frame = bufferPool[entry.getValue()];
					if (frame == null || !entry.getKey().equals(frame.pageKey)) {
						iter.remove();
						continue;
					}
					if (frame.state.pinCount() > 0) {
						throw new IllegalStateException(
								"Cannot discard file with pinned page: " + entry.getKey());
					}
					if (!frame.hasPage()) {
						// mid-load or mid-flush: its owner drops the frame
						inFlight = true;
						continue;
					}
					iter.remove();
					// clear() drives the word to FREE: all a later claim needs
					frame.clear();
				}
				if (inFlight) {
					awaitFlushSettled();
				}
			} finally {
				globalLock.unlock();
			}
			if (!inFlight)
				break;
		}

		fileStatesLock.lock();
		try {
			fileStates.remove(fileId);
		} finally {
			fileStatesLock.unlock();
		}
	}

	/** HELPER FUNCTIONS SECTIONS */

	/**
	 * Evicts an unpinned page chosen by the clock replacer and returns its frame
	 * index for immediate reuse by the caller; throws if all frames are pinned.
	 * Lock-free: the clock CAS is exclusive ownership of the victim.
	 *
	 * <p>If the victim is dirty, it keeps its page-table entry and moves to
	 * FLUSHING before the disk write, so a concurrent getPage for the victim's
	 * key finds the frame and parks for the flush instead of reading stale
	 * bytes from disk. reuseAfterEvict keeps the frame in LOADING so a
	 * concurrent claimer cannot steal it.
	 */
	private int evict() throws RuntimeException, IOException {

		// the sweep returns a frame already claimed in EVICTING and owned by
		// this thread; an empty result means nothing in the pool was evictable
		OptionalInt victim = clockReplacer.findVictim();
		if (victim.isEmpty())
			throw new RuntimeException("All frames are pinned, cannot evict");

		Frame evictFrame = bufferPool[victim.getAsInt()];
		PageKey victimKey = evictFrame.pageKey;

		// if frame dirty write to disk first, outside the lock
		if (evictFrame.isDirty) {
			if (!evictFrame.state.beginFlush()) {
				throw new IllegalStateException(
						"victim frame " + evictFrame.frameIndex + " is not claimed: " + evictFrame.state);
			}
			IOException failure = null;
			try {
				writePageToDisk(victimKey.fileId(), evictFrame.page);
			} catch (IOException e) {
				failure = e;
			}
			if (failure != null) {
				if (!evictFrame.state.abortFlush()) {
					failure.addSuppressed(new IllegalStateException(
							"frame " + evictFrame.frameIndex + " stuck after failed flush: " + evictFrame.state));
				}
				signalSettled();
				throw failure;
			}
		}

		int reused = handOffForReuse(evictFrame, victimKey);
		signalSettled();
		return reused;
	}

	/** EVICTING/FLUSHING to LOADING, fields cleared after the state word moves. */
	private int handOffForReuse(Frame frame, PageKey victimKey) {
		int idx = frame.frameIndex;
		if (victimKey != null)
			pageTable.remove(victimKey, idx);
		pageTable.values().removeIf(v -> v == idx);
		if (!frame.state.reuseAfterEvict()) {
			throw new IllegalStateException("cannot reuse frame " + frame.frameIndex + ": " + frame.state);
		}
		frame.page = null;
		frame.isDirty = false;
		frame.pageKey = null;
		return frame.frameIndex;
	}

	/** Write a page to disk. Overridable so tests can stall or fail a flush. */
	void writePageToDisk(String fileId, Page page) throws IOException {
		writeIOCount.increment();
		try (RandomAccessFile raf = new RandomAccessFile(fileId, "rw")) {
			long offset = RawPage.getOffset(page.getPid());
			raf.seek(offset);
			raf.write(page.getByteArray());
		}
	}

	/**
	 * Places a page into a free frame, evicting if needed. Lock-free: claim and
	 * install use the same CAS paths as a cache miss.
	 */
	private Page addToFrame(PageKey pageKey, Page page, boolean is_pinned) throws IOException, IllegalStateException {
		int frameIndex = claimFrame();
		try {
			return fillFrame(frameIndex, pageKey, page, is_pinned);
		} catch (RuntimeException e) {
			pageTable.remove(pageKey, frameIndex);
			releaseClaim(frameIndex, e);
			throw e;
		}
	}

	/** Points a frame this caller has already claimed at its page and publishes it. */
	private Page fillFrame(int frameIndex, PageKey pageKey, Page page, boolean is_pinned) {
		if (!installLoading(frameIndex, pageKey)) {
			throw new IllegalStateException("page already installed: " + pageKey);
		}
		Frame frame = bufferPool[frameIndex];
		frame.page = page;
		frame.markValid();
		return page;
	}

	/** Read a page from disk. Overridable so tests can stall a load. */
	RawPage readPageFromDisk(PageKey pageKey) throws IOException {
		readIOCount.increment();
		byte[] loaded_data = new byte[RawPage.MAX_PAGE_LEN];
		try (RandomAccessFile raf = new RandomAccessFile(pageKey.fileId(), "r")) {
			raf.seek(RawPage.getOffset(pageKey.pageId()));
			raf.readFully(loaded_data);
		}
		RawPage page = new RawPage(pageKey.pageId());
		page.fillPageData(loaded_data);
		return page;
	}

	/**
	 * Returns a frame claimed in LOADING: a FREE one if the pool has any, a
	 * reclaimed victim otherwise. evict() hands the frame over already in
	 * LOADING, so there is no FREE window a concurrent claimer can steal.
	 */
	private int claimFrame() throws IOException {
		int pinnedWaits = 0;
		for (;;) {
			OptionalInt claimed = clockReplacer.claimFree();
			if (claimed.isPresent()) return claimed.getAsInt();
			try {
				return evict();
			} catch (RuntimeException e) {
				if (!"All frames are pinned, cannot evict".equals(e.getMessage())) throw e;
				if (!hasTransientFrame() && pinnedWaits++ >= 8) throw e;
				globalLock.lock();
				try { awaitFlushSettled(); } finally { globalLock.unlock(); }
			}
		}
	}

	private boolean hasTransientFrame() {
		for (FrameState fs : frameStates) {
			FrameState.State s = fs.state();
			if (s != FrameState.State.FREE && s != FrameState.State.VALID) return true;
		}
		return false;
	}

	/**
	 * Hands a claimed frame back after a failed fill. abortLoad is LOADING to
	 * FREE, including when the Frame object was never constructed. State
	 * transitions before fields are nulled.
	 */
	private void releaseClaim(int frameIndex, Throwable cause) {
		Frame frame = bufferPool[frameIndex];
		try {
			if (frame == null) {
				frameStates[frameIndex].abortLoad();
				return;
			}
			if (frame.state.state() == FrameState.State.LOADING) {
				if (!frame.state.abortLoad()) {
					throw new IllegalStateException(
							"cannot abort load of frame " + frameIndex + ": " + frame.state);
				}
				frame.page = null;
				frame.isDirty = false;
				frame.pageKey = null;
				return;
			}
			frame.clear();
		} catch (IllegalStateException e) {
			if (cause != null) {
				cause.addSuppressed(e);
			} else {
				throw e;
			}
		}
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
				pageID[i] = entry.getKey().pageId();
				i++;
			}
			return pageID;
		} finally {
			globalLock.unlock();
		}
	}

	// For testing only: distinct fileIds with at least one page in the pool
	public Set<String> bufferedFileIds() {
		globalLock.lock();
		try {
			Set<String> fileIds = new HashSet<>();
			for (PageKey pageKey : pageTable.keySet()) {
				fileIds.add(pageKey.fileId());
			}
			return fileIds;
		} finally {
			globalLock.unlock();
		}
	}

	// For testing only
	public Set<String> catalogFileNames() {
		return Set.copyOf(catalog.keySet());
	}

	// For testing only
	public int getTotalPinCount() {
		globalLock.lock();
		try {
			int total = 0;
			for (Integer frameIndex : pageTable.values()) {
				total += (int) bufferPool[frameIndex].state.pinCount();
			}
			return total;
		} finally {
			globalLock.unlock();
		}
	}

	// package-private, for concurrency tests: frames whose state word says FREE.
	// In a quiescent pool, free frames + pageTable entries == bufferSize (a frame
	// mid-load is briefly in neither; a mid-flush frame stays in the page table).
	int getFreeFrameCount() {
		globalLock.lock();
		try {
			int free = 0;
			for (FrameState state : frameStates) {
				if (state.state() == FrameState.State.FREE) {
					free++;
				}
			}
			return free;
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
				return (int) frame.state.pinCount();
			} else {
				return -1;
			}
		} finally {
			globalLock.unlock();
		}
	}
}
