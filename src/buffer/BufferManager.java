package buffer;

import catalog.CatalogEntry;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
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
	private final ConcurrentMap<PageKey, Integer> pageTable;
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
	private final AtomicInteger freeFrames = new AtomicInteger();

	// Global lock guarding all buffer pool state: pageTable, bufferPool frames
	// (pin counts, dirty flags, contents). Page loads and dirty eviction
	// flushes happen OUTSIDE the lock so one thread's disk I/O never blocks
	// another thread's cache hits; force() keeps the coarse lock (rare).
	private final ReentrantLock globalLock = new ReentrantLock();

	// A LOADING or FLUSHING frame stays in the page table, so a reader finds it
	// and parks here instead of issuing a duplicate read or seeing stale disk
	// bytes. Signalled when the frame settles (VALID or gone).
	private final Condition flushSettled = globalLock.newCondition();

	// A load that keeps losing arbitration is waiting on other loaders, not
	// spinning, so this only has to be larger than any real pile-up. It exists
	// so a livelock surfaces as a failure rather than a hang.
	private static final int MAX_LOAD_ROUNDS = 1024;

	// I/O counters: read = disk loads (cache misses), write = pages written to disk
	private final LongAdder readIOCount = new LongAdder();
	private final LongAdder writeIOCount = new LongAdder();
	// getPage calls served without ever acquiring globalLock. Counts a code
	// path taken, not time saved: it is exact and reproducible, where a latency
	// figure from a hand-rolled harness on this workload would be neither.
	private final LongAdder lockFreeHitCount = new LongAdder();
	// unpinPage(handle) calls that released a pin without acquiring globalLock.
	private final LongAdder lockFreeUnpinCount = new LongAdder();
	// Taken on every acquire, including the relock after a dirty flush. A
	// contended acquire (tryLock failed) also increments globalLockContentions.
	// Same counters can sit on the PR 31 locked path; only the numbers change.
	private final LongAdder globalLockAcquisitions = new LongAdder();
	private final LongAdder globalLockContentions = new LongAdder();

	public BufferManager(int bufferSize) {
		this.bufferSize = bufferSize;
		this.pageTable = new ConcurrentHashMap<>();
		this.bufferPool = new Frame[bufferSize];
		this.frameStates = new FrameState[bufferSize];

		this.catalog = new ConcurrentHashMap<>();

		initializeBufferManager();
		this.clockReplacer = new ClockReplacer(frameStates, freeFrames);
	}

	private void initializeBufferManager() {
		// FREE is the pool's only record of a frame being available; the shared
		// count only lets the replacer skip a sweep it knows would find nothing
		for (int i = 0; i < bufferSize; i++) {
			frameStates[i] = new FrameState(freeFrames);
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
		return pinPage(fileId, pageId).page();
	}

	/**
	 * Pins the page and returns a handle that names that pin by frame and
	 * version. {@link #getPage} unwraps the page so existing key-based callers
	 * keep working. Unpin the handle to release this incarnation; key-based
	 * unpin remains for callers that have not switched.
	 */
	public PageHandle pinPage(String fileId, int pageId) throws IOException {
		PageKey pageKey = new PageKey(fileId, pageId);
		PageHandle hit = tryPinHit(pageKey);
		if (hit != null) {
			return hit;
		}
		acquireGlobalLock();
		try {
			int rounds = 0;
			for (;;) {
				Integer frameIndex = pageTable.get(pageKey);
				if (frameIndex != null) {
					Frame frame = bufferPool[frameIndex];
					if (frame.hasPage()) {
						frame.pin();
						return bindHandle(frame, pageKey);
					}
					awaitFlushSettled();
					continue;
				}

				if (++rounds > MAX_LOAD_ROUNDS) {
					throw new IllegalStateException(
							"gave up loading " + pageKey + " after " + MAX_LOAD_ROUNDS + " rounds");
				}

				int claimed = claimFrame();
				try {
					if (bufferPool[claimed] == null) {
						bufferPool[claimed] = new Frame(claimed, frameStates[claimed]);
					}
					Frame frame = bufferPool[claimed];
					if (frame.page != null) {
						throw new IllegalStateException("Expected Free Frame object");
					}
					frame.pageKey = pageKey;
				} catch (RuntimeException e) {
					releaseClaim(claimed, e);
					throw e;
				}

				// The single arbiter for this page. Exactly one frame index can
				// be mapped to a key at a time — this only ever installs into an
				// empty slot, and eviction only ever retires the mapping still
				// pointing at the frame it evicted — so two frames holding one
				// page is not a state the pool can reach.
				Integer winner = pageTable.putIfAbsent(pageKey, claimed);
				if (winner != null) {
					// Another loader got there first, possibly while claimFrame
					// dropped the lock to flush. Give the frame back and wait for
					// their load. Looping straight back to claimFrame instead
					// would evict a fresh victim, and could pay a real disk write
					// on every lost round.
					releaseClaim(claimed, null);
					awaitFlushSettled();
					continue;
				}

				Page page = null;
				Exception loadError = null;
				globalLock.unlock();
				try {
					page = readPageFromDisk(pageKey);
				} catch (IOException | RuntimeException e) {
					loadError = e;
				} finally {
					acquireGlobalLock();
				}
				if (loadError != null) {
					pageTable.remove(pageKey, claimed);
					releaseClaim(claimed, loadError);
					flushSettled.signalAll();
					if (loadError instanceof IOException ioe)
						throw ioe;
					throw (RuntimeException) loadError;
				}
				try {
					Frame frame = bufferPool[claimed];
					publishValid(frame, pageKey, page);
					frame.pin();
					return bindHandle(frame, pageKey);
				} catch (RuntimeException e) {
					pageTable.remove(pageKey, claimed);
					releaseClaim(claimed, e);
					throw e;
				} finally {
					flushSettled.signalAll();
				}
			}
		} finally {
			globalLock.unlock();
		}
	}

	/**
	 * Serves a cache hit without taking globalLock, or returns null to send the
	 * caller down the locked path.
	 *
	 * <p>Two separate things can be stale here, and each needs its own guard.
	 *
	 * <p>The page table read can be stale: by the time it returns, the page may
	 * have been evicted and the frame refilled with something else. So the frame
	 * index is a hint, never proof, and the frame's own pageKey is what decides.
	 * That check has to come after the pin, not before: only the pin stops the
	 * frame being claimed for eviction, and only a frame that cannot be claimed
	 * has a stable pageKey and page.
	 *
	 * <p>The state snapshot can also be stale: the frame may be recycled between
	 * the snapshot and the pin, arriving back in VALID holding a different page.
	 * Pinning at the observed version is what rejects that, since every return
	 * to FREE moves the version.
	 *
	 * <p>Reading bufferPool without the lock races the lazy construction in the
	 * miss path, which is benign in both directions: a stale null just falls
	 * through to the locked path, and a Frame published by that race is still
	 * safe to touch, because the only field read before the pin is {@code state},
	 * which is final.
	 */
	private PageHandle bindHandle(Frame frame, PageKey key) {
		return new PageHandle(frame.frameIndex, frame.state.version(), key, frame.page);
	}

	private PageHandle tryPinHit(PageKey pageKey) {
		Integer frameIndex = pageTable.get(pageKey);
		if (frameIndex == null) {
			return null;
		}
		afterPageTableRead();
		Frame frame = bufferPool[frameIndex];
		if (frame == null) {
			return null;
		}
		long snapshot = frame.state.snapshot();
		if (FrameState.decodeState(snapshot) != FrameState.State.VALID) {
			return null;
		}
		long version = FrameState.decodeVersion(snapshot);
		if (!frame.state.tryPin(version)) {
			return null;
		}
		if (pageKey.equals(frame.pageKey)) {
			lockFreeHitCount.increment();
			return new PageHandle(frame.frameIndex, version, pageKey, frame.page);
		}
		// The index was stale and this frame belongs to another page. Hand the
		// pin straight back rather than serving its holder someone else's data.
		frame.state.unpin(version);
		return null;
	}

	/**
	 * Hook between the page table read and the pin that acts on it. Does nothing
	 * in production; overridable so tests can hold the window open and make the
	 * stale-index race deterministic, in the same way writePageToDisk and
	 * readPageFromDisk let tests stall a flush or a load.
	 */
	void afterPageTableRead() {
	}

	/**
	 * Parks until a load or flush settles. globalLock must be held.
	 *
	 * <p>The wait is untimed on purpose. Every caller tests its predicate while
	 * holding globalLock and parks on this condition without letting go of it,
	 * and every change to what those predicates read — a frame reaching VALID,
	 * a mapping being installed or retired — signals under the same lock. There
	 * is therefore no gap for a signal to slip through, and a bounded wait would
	 * only convert a real missed signal into a slow one, which is exactly the
	 * kind of bug that never gets found. If this ever hangs, a settle point is
	 * missing its signal; add the signal rather than a timeout.
	 */
	private void awaitFlushSettled() {
		try {
			flushSettled.await();
		} catch (InterruptedException e) {
			// Restoring the flag and looping would spin, because the next await
			// rethrows immediately. Hand the interrupt to the caller instead.
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for a page to settle", e);
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
		return (RawPage) createPinnedPage(fileId, data).page();
	}

	/**
	 * Allocates a new page and returns the handle for the pin taken on it.
	 * {@link #createPage} unwraps the page so existing key-based callers keep
	 * working. The allocation itself stays on globalLock: it is a structural
	 * mutation, not a cache hit.
	 */
	public PageHandle createPinnedPage(String fileId, byte[] data) throws IOException {
		int nextPageId = getOrCreateFileState(fileId).allocatePageId();
		PageKey pageKey = new PageKey(fileId, nextPageId);

		RawPage page = new RawPage(nextPageId);
		if (data != null) {
			page.fillPageData(data);
		}

		// freshly allocated page id: no other thread can reference this key yet,
		// so no in-flight load marker is needed around addToFrame
		acquireGlobalLock();
		try {
			addToFrame(pageKey, page, true);
			return bindHandle(bufferPool[pageTable.get(pageKey)], pageKey);
		} finally {
			globalLock.unlock();
		}
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
		if (frame == null) {
			throw new IllegalArgumentException("Page not in buffer: " + pageKey);
		}
		long snapshot = frame.state.snapshot();
		if (FrameState.decodeState(snapshot) != FrameState.State.VALID
				|| FrameState.decodePinCount(snapshot) == 0
				|| !pageKey.equals(frame.pageKey)) {
			throw new IllegalStateException(
					"markDirty requires the caller to hold the page pinned: " + pageKey
							+ " is " + FrameState.describe(snapshot));
		}
		frame.isDirty = true;
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
		acquireGlobalLock();
		try {
			Integer frameIndex = pageTable.get(pageKey);
			if (frameIndex == null) {
				throw new IllegalArgumentException("Page not in buffer: " + pageKey);
			}
			Frame frame = bufferPool[frameIndex];
			// globalLock makes the version read and the unpin one step, so the
			// guard cannot fire here yet. It becomes load-bearing when the lock
			// comes off and this lookup stops being atomic with the release.
			if (frame.state.pinCount() > 0)
				frame.state.unpin(frame.state.version());
		} finally {
			globalLock.unlock();
		}
	}

	/**
	 * Releases the pin named by {@code handle} without taking globalLock.
	 * The handle already names the frame and version, so there is no key
	 * lookup that could land on a recycled page. markReleased runs first so
	 * a duplicated unpin cannot drop a sibling holder's pin.
	 */
	public void unpinPage(PageHandle handle) {
		if (!handle.markReleased()) {
			throw new IllegalStateException("handle already unpinned: " + handle);
		}
		releaseHandle(handle);
		lockFreeUnpinCount.increment();
	}

	private void releaseHandle(PageHandle handle) {
		int index = handle.frameIndex();
		if (index >= bufferPool.length) {
			throw new IllegalStateException("stale handle: " + handle);
		}
		Frame frame = bufferPool[index];
		if (frame == null || !frame.state.unpin(handle.version())) {
			throw new IllegalStateException("stale handle: " + handle);
		}
	}

	/** Forces all dirty pages currently in memory to be written back to disk. */
	public void force() throws IOException {
		acquireGlobalLock();
		try {
			for (;;) {
				boolean flushing = false;
				for (Map.Entry<PageKey, Integer> entry : pageTable.entrySet()) {
					Frame frame = bufferPool[entry.getValue()];
					if (!frame.hasPage()) {
						// mid-flush: the evictor owns that write; skipping
						// avoids a double-write, then we wait and recheck
						flushing = true;
						continue;
					}
					if (!frame.isDirty)
						continue;
					// Clear before writing, never after. markDirty runs without
					// globalLock now — its exclusion comes from the caller's pin,
					// which says nothing about force — so a modification can land
					// while the write is in flight, or after it and before the flag
					// is cleared. Clearing first means such a write re-dirties the
					// page and it goes out again; clearing afterwards would declare
					// the page clean while its newest bytes are still only in
					// memory, and the next eviction would drop them.
					frame.isDirty = false;
					try {
						writePageToDisk(entry.getKey().fileId(), frame.page);
					} catch (IOException | RuntimeException e) {
						// nothing was persisted, so the page is still dirty
						frame.isDirty = true;
						throw e;
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
			acquireGlobalLock();
			try {
				Iterator<Map.Entry<PageKey, Integer>> iter = pageTable.entrySet().iterator();
				while (iter.hasNext()) {
					Map.Entry<PageKey, Integer> entry = iter.next();
					if (!entry.getKey().fileId().equals(fileId))
						continue;
					Frame frame = bufferPool[entry.getValue()];
					if (frame.state.pinCount() > 0) {
						throw new IllegalStateException(
								"Cannot discard file with pinned page: " + entry.getKey());
					}
					if (!frame.hasPage()) {
						// mid-load or mid-flush: its owner drops the frame
						inFlight = true;
						continue;
					}
					// Own the frame before unmapping it. Readers pin without
					// globalLock, so the pin count checked a moment ago is not a
					// promise, and only winning the claim excludes them. Losing
					// it means a reader got in; leave the mapping alone and come
					// back, rather than retiring an entry for a frame this call
					// is not entitled to empty.
					if (!frame.tryClaimForClear()) {
						inFlight = true;
						continue;
					}
					iter.remove();
					frame.clearOwned();
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
	 * Must be called with globalLock held.
	 *
	 * <p>If the victim is dirty, it keeps its page-table entry and moves to
	 * FLUSHING BEFORE the lock is released for the disk write, so a concurrent
	 * getPage for the victim's key finds the frame and parks for
	 * the flush instead of reading stale bytes from disk. The reclaimed frame is
	 * returned FREE, so a caller that fails to claim or fill it still leaves it
	 * findable by the next sweep.
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
			globalLock.unlock();
			IOException failure = null;
			try {
				writePageToDisk(victimKey.fileId(), evictFrame.page);
			} catch (IOException e) {
				failure = e;
			} finally {
				acquireGlobalLock();
			}
			try {
				if (failure != null) {
					// stay installed: abortFlush is FLUSHING to VALID, never FREE,
					// so a concurrent claim cannot steal the only dirty copy
					if (!evictFrame.state.abortFlush()) {
						failure.addSuppressed(new IllegalStateException(
								"frame " + evictFrame.frameIndex + " stuck after failed flush: " + evictFrame.state));
					}
					throw failure;
				}
				int frameIndex = evictFrame.frameIndex;
				// conditional: only unmap the entry still pointing at this frame, never a
				// mapping a later loader installed for the same key on another frame
				pageTable.remove(victimKey, frameIndex);
				evictFrame.clearOwned();
				return frameIndex;
			} finally {
				// every flush exit, after the frame has settled: the waiters' predicate
				// has changed, and nothing but this signal will wake them
				flushSettled.signalAll();
			}
		}

		// evict frame content and hand the frame to the caller
		int frameIndex = evictFrame.frameIndex;
		pageTable.remove(victimKey, frameIndex);
		evictFrame.clearOwned();
		flushSettled.signalAll();
		return frameIndex;
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
	 * Places a page into a free frame, evicting if needed. Must be called with
	 * globalLock held. May temporarily release the lock while a dirty victim is
	 * flushed (see evict).
	 */
	private Page addToFrame(PageKey pageKey, Page page, boolean is_pinned) throws IOException, IllegalStateException {

		// take a frame claimed out of FREE, evicting to reclaim one if none are free
		int frameIndex = claimFrame();
		try {
			return fillFrame(frameIndex, pageKey, page, is_pinned);
		} catch (RuntimeException e) {
			releaseClaim(frameIndex, e);
			throw e;
		}
	}

	/** Points a frame this caller has already claimed at its page and publishes it. */
	private Page fillFrame(int frameIndex, PageKey pageKey, Page page, boolean is_pinned) {

		// load if frame object instantiated otherwise create a new one
		if (bufferPool[frameIndex] == null) {
			bufferPool[frameIndex] = new Frame(frameIndex, frameStates[frameIndex]);
		}
		Frame frame = bufferPool[frameIndex];

		// assert page is empty
		if (frame.page != null) {
			throw new IllegalStateException("Expected Free Frame object");
		}

		publishValid(frame, pageKey, page);
		if (is_pinned) {
			frame.pin();
		}
		if (!installMapping(pageKey, frameIndex)) {
			throw new IllegalStateException("page already resident: " + pageKey);
		}
		return page;
	}

	/**
	 * Publication order for a resident frame. {@code page} and {@code pageKey}
	 * are written first; the LOADING→VALID CAS is the publication. A lock-free
	 * reader may examine those fields only after it has observed VALID (or
	 * taken a pin, which requires VALID), so the writes happen-before any
	 * unlocked use of the identity.
	 *
	 * <p>Installing the page-table mapping is a separate step. The load path
	 * does it before the disk read so waiters find the LOADING frame; createPage
	 * does it after VALID so the first unlocked lookup cannot observe a VALID
	 * frame whose identity is still being written.
	 */
	void publishValid(Frame frame, PageKey key, Page page) {
		if (key == null || page == null) {
			throw new IllegalStateException("cannot publish a frame without a page and a key");
		}
		frame.page = page;
		frame.pageKey = key;
		frame.markValid();
	}

	/**
	 * Makes a frame findable by key. {@code putIfAbsent} so a loser cannot
	 * overwrite a mapping another loader already installed for this key.
	 *
	 * @return true if this frame is now the mapping
	 */
	boolean installMapping(PageKey key, int frameIndex) {
		Integer winner = pageTable.putIfAbsent(key, frameIndex);
		return winner == null || winner == frameIndex;
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
	 * Returns a frame claimed in LOADING and owned by this caller: a FREE one if
	 * the pool has any, a reclaimed victim otherwise. Both routes leave through
	 * the same compare-and-swap out of FREE, so nothing has to be kept in step
	 * with the state word and two callers can never hold the same index. A
	 * victim reclaimed by evict() is briefly FREE like any other frame, so
	 * losing the race for it just means sweeping again.
	 */
	private int claimFrame() throws IOException {
		for (;;) {
			OptionalInt claimed = clockReplacer.claimFree();
			if (claimed.isPresent()) {
				return claimed.getAsInt();
			}
			int reclaimed = evict();
			if (frameStates[reclaimed].tryBeginLoad()) {
				return reclaimed;
			}
		}
	}

	/**
	 * Hands a claimed frame back after a failed fill, making allocation
	 * all-or-nothing: the caller either gets a filled frame or leaves the frame
	 * FREE and sweepable, never stranded where no sweep looks. FrameState has no
	 * direct LOADING to FREE edge, so an unfilled claim is finished and cleared
	 * straight back out; a frame that will not unwind is reported as a
	 * suppressed exception rather than replacing the original failure.
	 */
	private void releaseClaim(int frameIndex, Throwable cause) {
		Frame frame = bufferPool[frameIndex];
		if (frame == null) {
			return;
		}
		// An unfilled claim is finished back out through the same door every
		// other caller uses: markValid lands the frame in VALID, and clear()
		// then proves ownership by taking the eviction claim before erasing
		// anything. A refused unwind leaves the frame's fields intact.
		try {
			if (frame.state.state() == FrameState.State.LOADING) {
				frame.markValid();
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

	public void resetIOCounts() {
		readIOCount.reset();
		writeIOCount.reset();
		lockFreeHitCount.reset();
		lockFreeUnpinCount.reset();
		globalLockAcquisitions.reset();
		globalLockContentions.reset();
	}
	/** getPage calls served entirely without globalLock. */
	public long getLockFreeHitCount() { return lockFreeHitCount.sum(); }
	/** unpinPage(handle) calls that released a pin without globalLock. */
	public long getLockFreeUnpinCount() { return lockFreeUnpinCount.sum(); }
	/** Times {@link #acquireGlobalLock()} actually obtained the lock. */
	public long getGlobalLockAcquisitions() { return globalLockAcquisitions.sum(); }
	/** Times the lock was already held, so the caller had to wait. */
	public long getGlobalLockContentions() { return globalLockContentions.sum(); }

	private void acquireGlobalLock() {
		if (!globalLock.tryLock()) {
			globalLockContentions.increment();
			globalLock.lock();
		}
		globalLockAcquisitions.increment();
	}
	public long getReadIOCount()  { return readIOCount.sum();  }
	public long getWriteIOCount() { return writeIOCount.sum(); }
	public long getTotalIOCount() { return readIOCount.sum() + writeIOCount.sum(); }

	// For testing only
	public int[] listPageID() {
		acquireGlobalLock();
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
		acquireGlobalLock();
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
		acquireGlobalLock();
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

	/**
	 * Checks the pool's structural and data invariants and reports what is
	 * broken. Empty means consistent.
	 *
	 * <p>Written to be called mid-flight, with other threads reading, loading and
	 * evicting, not only once everything has gone quiet. A check that only runs
	 * after the workers join cannot see a torn intermediate state, which is the
	 * only kind these bugs produce — by the time a pool is quiescent it has
	 * usually tidied itself up.
	 *
	 * <p>Takes globalLock because the scan has to be atomic with respect to
	 * installs and evictions: read frame by frame without it and a page that
	 * merely moves between frames during the scan looks like two copies.
	 */
	List<String> checkInvariants() {
		acquireGlobalLock();
		try {
			List<String> problems = new ArrayList<>();
			Map<PageKey, Integer> heldBy = new HashMap<>();
			for (int i = 0; i < bufferPool.length; i++) {
				Frame frame = bufferPool[i];
				if (frame == null) {
					continue;
				}
				FrameState.State state = frame.state.state();
				PageKey key = frame.pageKey;
				if (state == FrameState.State.FREE) {
					// A free frame is advertised as available; anything still hanging
					// off it will be inherited by whoever claims it next.
					if (frame.page != null) {
						problems.add("free frame " + i + " still holds " + key);
					}
					if (frame.isDirty) {
						problems.add("free frame " + i + " is still marked dirty");
					}
					continue;
				}
				if (state != FrameState.State.VALID) {
					continue; // owned by a loader or an evictor, mid-transition
				}
				if (key == null) {
					problems.add("resident frame " + i + " has no page key");
					continue;
				}
				if (frame.page == null) {
					problems.add("resident frame " + i + " holds no page for " + key);
				}
				Integer prior = heldBy.put(key, i);
				if (prior != null) {
					// two divergent copies: a write through one is invisible to the
					// holder of the other, and the later eviction overwrites the earlier
					problems.add(key + " is resident in frames " + prior + " and " + i);
				}
				Integer mapped = pageTable.get(key);
				if (mapped == null) {
					problems.add(key + " is resident in frame " + i + " but nothing maps to it");
				} else if (mapped != i) {
					problems.add(key + " maps to frame " + mapped + " but is resident in " + i);
				}
			}
			for (Map.Entry<PageKey, Integer> entry : pageTable.entrySet()) {
				Frame frame = bufferPool[entry.getValue()];
				if (frame == null) {
					problems.add(entry.getKey() + " maps to frame " + entry.getValue() + ", which does not exist");
				} else if (frame.hasPage() && !entry.getKey().equals(frame.pageKey)) {
					problems.add(entry.getKey() + " maps to frame " + entry.getValue()
							+ ", which holds " + frame.pageKey);
				}
			}
			return problems;
		} finally {
			globalLock.unlock();
		}
	}

	// package-private, for concurrency tests: frames whose state word says FREE.
	// In a quiescent pool, free frames + pageTable entries == bufferSize (a frame
	// mid-load is briefly in neither; a mid-flush frame stays in the page table).
	int getFreeFrameCount() {
		acquireGlobalLock();
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

		acquireGlobalLock();
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
