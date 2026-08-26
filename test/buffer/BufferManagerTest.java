package buffer;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import catalog.TableEntry;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import storage.*;

public class BufferManagerTest {
	// Movies schema: movieId=9 bytes, title=30 bytes → recordSize=39
	private static final Map<String, Integer> MOVIE_SCHEMA = new LinkedHashMap<>();
	private static final int RECORD_SIZE = 9 + 30; // 39 bytes

	static {
		MOVIE_SCHEMA.put("movieId", 9);
		MOVIE_SCHEMA.put("title", 30);
	}

	private BufferManager bm;
	private String fileOneName;

	@BeforeEach
	void setup() throws Exception {
		bm = new BufferManager(3);
		fileOneName = "fileOne";

		// create a temp binary file for testing
		File tempFile = File.createTempFile(fileOneName, ".dat");
		tempFile.deleteOnExit();
		fileOneName = tempFile.getAbsolutePath();

		// create an example binary file
		try (RandomAccessFile raf = new RandomAccessFile(fileOneName, "rw")) {
			byte[] fingerprints = {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD};
			// write four pages into the file
			for (int id = 0; id < 4; id++) {
				// create page
				byte[] page_data = new byte[RawPage.MAX_PAGE_LEN];
				Arrays.fill(page_data, fingerprints[id]);

				// locate disk location
				int pageId = id;
				long offset = RawPage.getOffset(pageId);
				raf.seek(offset);

				// write page
				raf.write(page_data);
			}
			System.out.println("Create File Done");
		} catch (IOException e) {
			System.out.println("Create file FAIL");
		}
		bm.register(new TableEntry(fileOneName, MOVIE_SCHEMA));
	}

	@Test
	void testGetPageFromMemory() throws Exception {
		// get page from file
		Page pageFromFile = null;
		pageFromFile = bm.getPage(fileOneName, 0);

		// dirty the page in memory
		byte[] new_data = new byte[RawPage.MAX_PAGE_LEN];
		Arrays.fill(new_data, (byte) 0xFF); // diffrent byte array
		pageFromFile.fillPageData(new_data);
		Page dirtyPage = pageFromFile;

		// get page
		Page pageFromMemory = null;
		pageFromMemory = bm.getPage(fileOneName, 0);

		// assert its the dirty page thus read from memory
		assertArrayEquals(dirtyPage.getByteArray(), pageFromMemory.getByteArray());
	}

	@Test
	void testGetPageFromDisk() throws Exception {
		// get page from file
		Page pageFromFile = null;
		int pageId = 0;

		pageFromFile = bm.getPage(fileOneName, pageId);

		// load from file
		byte[] loaded_data = null;

		try (RandomAccessFile raf = new RandomAccessFile(fileOneName, "r")) {
			long offset = RawPage.getOffset(pageId);
			raf.seek(offset);
			loaded_data = new byte[RawPage.MAX_PAGE_LEN];
			raf.readFully(loaded_data);
		}

		// check tha byte array the function equals byte array loaded in through file
		// i/o
		assertArrayEquals(pageFromFile.getByteArray(), loaded_data);
	}

	@Test
	void testGetPageWhenBufferFullAndAllFramesPinned() throws Exception {
		// load 3 pages and fill buffer
		for (int pageId = 0; pageId < 3; pageId++) {
			bm.getPage(fileOneName, pageId);
		}
		// load 4th page and assert throw exception
		RuntimeException ex = assertThrowsExactly(RuntimeException.class, () -> {
			bm.getPage(fileOneName, 3);
		});

		assertEquals(ex.getMessage(), "All frames are pinned, cannot evict");
	}

	@Test
	void testGetPageWhenBufferFull() throws Exception {
		// load 3 pages and fill buffer
		for (int pageId = 0; pageId < 3; pageId++) {
			bm.getPage(fileOneName, pageId);
		}
		// unpin a page to evict
		bm.unpinPage(fileOneName, 1);

		// load 4th page
		bm.getPage(fileOneName, 3);
	}

	@Test
	void evictionReclaimsTheOnlyUnpinnedFrameAndLoadsTheNewPage() throws Exception {
		// pool of 3, holding pages 0, 1, 2; only page 1 is unpinned
		for (int pageId = 0; pageId < 3; pageId++) {
			bm.getPage(fileOneName, pageId);
		}
		bm.unpinPage(fileOneName, 1);

		Page page3 = bm.getPage(fileOneName, 3);

		// page 1 is the frame the sweep reclaimed; 0 and 2 stayed put
		assertArrayEquals(new int[]{0, 2, 3}, bufferedPageIds());
		// and page 3 arrived whole: on disk every byte of it is 0xDD
		byte[] expected = new byte[RawPage.MAX_PAGE_LEN];
		Arrays.fill(expected, (byte) 0xDD);
		assertArrayEquals(expected, page3.getByteArray());
	}

	@Test
	void aPinnedFrameIsNeverChosenAsAVictim() throws Exception {
		bm = new BufferManager(2);
		bm.getPage(fileOneName, 0); // pinned for the whole test

		// churn the one remaining frame; page 0 must survive every sweep
		for (int pageId = 1; pageId <= 3; pageId++) {
			bm.getPage(fileOneName, pageId);
			bm.unpinPage(fileOneName, pageId);
			assertEquals(1, bm.getPinCount(fileOneName, 0), "pinned page 0 was evicted");
		}
		assertArrayEquals(new int[]{0, 3}, bufferedPageIds());
	}

	@Test
	void anAllPinnedPoolThrowsAndLeavesEveryFrameInPlace() throws Exception {
		for (int pageId = 0; pageId < 3; pageId++) {
			bm.getPage(fileOneName, pageId);
		}

		RuntimeException ex = assertThrowsExactly(RuntimeException.class, () -> bm.getPage(fileOneName, 3));

		assertEquals("All frames are pinned, cannot evict", ex.getMessage());
		// the failed sweep must leave the pool exactly as it found it
		assertArrayEquals(new int[]{0, 1, 2}, bufferedPageIds());
		assertEquals(3, bm.getTotalPinCount());
	}

	/**
	 * Page ids held by the pool, sorted. The page table is a plain HashMap, so
	 * {@link BufferManager#listPageID()} hands them back in no particular order.
	 */
	private int[] bufferedPageIds() {
		int[] ids = bm.listPageID();
		Arrays.sort(ids);
		return ids;
	}

	@Test
	void aCacheHitDoesNotProtectAPageTheWayLruWould() throws Exception {
		// pool of 2 holding pages 0 and 1, both unpinned
		bm = new BufferManager(2);
		for (int pageId = 0; pageId < 2; pageId++) {
			bm.getPage(fileOneName, pageId);
			bm.unpinPage(fileOneName, pageId);
		}

		// hammer page 0: under LRU this would make page 1 the victim
		for (int hit = 0; hit < 3; hit++) {
			bm.getPage(fileOneName, 0);
			bm.unpinPage(fileOneName, 0);
		}
		bm.getPage(fileOneName, 2);

		// the hand still starts at frame 0, so the hottest page is the one that goes
		assertArrayEquals(new int[]{1, 2}, bufferedPageIds());
	}

	@Test
	void aPageTouchedSinceTheHandPassedSurvivesOneSweepAndGoesOnTheNext() throws Exception {
		// pool of 3 holding pages 0, 1, 2, all unpinned; loading page 3 sweeps
		// every reference bit clear and takes page 0
		for (int pageId = 0; pageId <= 3; pageId++) {
			bm.getPage(fileOneName, pageId);
			bm.unpinPage(fileOneName, pageId);
		}
		assertArrayEquals(new int[]{1, 2, 3}, bufferedPageIds());

		// touch page 1 only: the hit re-sets its reference bit via tryPin
		bm.getPage(fileOneName, 1);
		bm.unpinPage(fileOneName, 1);

		// next sweep spends a pass giving page 1 its second chance and takes
		// untouched page 2 instead
		bm.getPage(fileOneName, 0);
		bm.unpinPage(fileOneName, 0);
		assertArrayEquals(new int[]{0, 1, 3}, bufferedPageIds());

		// page 1 is now unreferenced, so the following sweep does take it
		bm.getPage(fileOneName, 2);
		assertArrayEquals(new int[]{0, 2, 3}, bufferedPageIds());
	}

	@Test
	void testGetPageThatDoesNotExist() throws Exception {
		// page 10 doesn't exist in the file (only pages 0-3)
		assertThrows(java.io.EOFException.class, () -> {
			bm.getPage(fileOneName, 10);
		});
	}

	@Test
	void createPageEvictsInHandOrderAndNeverTakesAPinnedFrame() throws Exception {
		File tempFile = File.createTempFile("fileTwo", ".dat");
		tempFile.deleteOnExit();
		String fileTwoName = tempFile.getAbsolutePath();
		bm = new BufferManager(2);
		bm.register(new TableEntry(fileTwoName, MOVIE_SCHEMA));

		// pool of 2 holding freshly created pages 0 and 1, both unpinned
		for (int pageId = 0; pageId < 2; pageId++) {
			assertEquals(pageId, bm.createPage(fileTwoName, null).getPid());
			bm.unpinPage(fileTwoName, pageId);
		}

		// allocating page 2 sweeps both reference bits clear, then takes page 0:
		// the frame the hand reaches first once nothing is referenced
		bm.createPage(fileTwoName, null);
		bm.unpinPage(fileTwoName, 2);
		assertArrayEquals(new int[]{1, 2}, bufferedPageIds());

		// pin page 1 and leave it pinned. The sweep for page 3 reaches it first
		// and clears its reference bit, but must still refuse it as a victim and
		// carry on to unpinned page 2.
		bm.getPage(fileTwoName, 1);
		bm.createPage(fileTwoName, null);
		assertArrayEquals(new int[]{1, 3}, bufferedPageIds());
	}

	@Test
	public void testDirtyEvictionPersists() {
		// Goal: dirty pages are written to disk on eviction.
		// Spec mapping: "All evicted pages that are dirty must be written back."
		// Setup: create A (dirty), force eviction, reload A.
		// Expect: data matches what was inserted.
		//
		// Pseudocode:
		// create A, mark dirty, unpin
		// force eviction, reload A, verify row
		try {
			String fileTwoName = "fileTwo";
			File tempFile = File.createTempFile(fileTwoName, ".dat");
			tempFile.deleteOnExit();
			fileTwoName = tempFile.getAbsolutePath();
			bm = new BufferManager(2);
			bm.register(new TableEntry(fileTwoName, MOVIE_SCHEMA));
			Page page_A = bm.createPage(fileTwoName, null);
			GenericPage genericPage_A = new GenericPage(page_A, MOVIE_SCHEMA);
			GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, "movie1", "title1");
			genericPage_A.insertRecord(record);
			bm.markDirty(fileTwoName, page_A.getPid());
			bm.unpinPage(fileTwoName, page_A.getPid());

			// Keep page id
			int pid = page_A.getPid();

			// Keep page data
			byte[] data = page_A.getByteArray();

			// force
			bm.force();

			// Reload
			page_A = bm.getPage(fileTwoName, pid);
			byte[] loaded_data = page_A.getByteArray();

			// Assert content not change
			assertArrayEquals(data, loaded_data);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testInvalidUnpinAndMarkDirty() {
		// Goal: defensive behavior on invalid pageIds.
		// Setup: call unpin/markDirty on a pageId not in buffer.
		// Expect: error/exception is thrown.
		//
		// Pseudocode:
		// expect exception on unpin(fakePid)
		// expect exception on markDirty(fakePid)

		assertThrows(Exception.class, () -> bm.unpinPage(fileOneName, 100));
		assertThrows(Exception.class, () -> bm.markDirty(fileOneName, 100));
	}

	@Test
	public void testPinCountBehavior() {
		// Goal: correct pin count semantics.
		// Setup: get same page twice (pinCount=2), unpin once (pinCount=1), unpin again
		// (pinCount=0).
		// Expect: no over-unpin error; page is evictable only when pinCount==0.
		//
		// Pseudocode:
		// p = createPage
		// getPage(p); unpin once; unpin second time -> ok

		try {
			Page page_1 = bm.createPage(fileOneName, null);
			assertDoesNotThrow(() -> {
				Page page_2 = bm.getPage(fileOneName, page_1.getPid());
			});

			// Check pinCount
			assertEquals(bm.getPinCount(fileOneName, page_1.getPid()), 2);
			// Unpin once
			bm.unpinPage(fileOneName, page_1.getPid());
			// Check pinCount
			assertEquals(bm.getPinCount(fileOneName, page_1.getPid()), 1);
			// Unpin the second time
			bm.unpinPage(fileOneName, page_1.getPid());
			// Check pinCount
			assertEquals(bm.getPinCount(fileOneName, page_1.getPid()), 0);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testCreateReadBack() {
		// Goal: verify create/get round-trip works through the BufferManager.
		// Spec mapping: "Fetches a page... loads from disk" + basic page I/O behavior.
		// Setup: create one page, insert one row, mark dirty, unpin, then getPage.
		// Expect: row contents match exactly; pageId is stable.
		//
		// Pseudocode:
		// p = bm.createPage(MOVIES_DB)
		// p.insertRow(movieId, title)
		// bm.markDirty(MOVIES_DB, p.pid); bm.unpin(MOVIES_DB, p.pid)
		// p2 = bm.getPage(MOVIES_DB, p.pid)
		// assert row == expected

		try {
			Page page = bm.createPage(fileOneName, null);
			GenericPage genericPage = new GenericPage(page, MOVIE_SCHEMA);
			GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, "movie1", "title1");
			genericPage.insertRecord(record);
			bm.markDirty(fileOneName, page.getPid());
			bm.unpinPage(fileOneName, page.getPid());

			Page page2 = bm.getPage(fileOneName, page.getPid());
			assertArrayEquals(page.getByteArray(), page2.getByteArray());

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testMultipleRowsSamePage() {
		// Goal: verify row slotting and multiple inserts in one page.
		// Spec mapping: record layout and fixed-length storage.
		// Setup: insert 2-3 rows into the same page.
		// Expect: rowIds are sequential, data matches at each slot.
		//
		// Pseudocode:
		// insert 3 rows into same page
		// assert rowIds: 0,1,2 and data matches
		try {
			Page page = bm.createPage(fileOneName, null);
			GenericPage genericPage = new GenericPage(page, MOVIE_SCHEMA);
			for (int i = 0; i < 3; i++) {
				GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, "movie" + i, "title" + i);
				genericPage.insertRecord(record);
			}
			bm.markDirty(fileOneName, page.getPid());
			bm.unpinPage(fileOneName, page.getPid());

			// Test
			for (int i = 0; i < 3; i++) {
				String expectedId = "movie" + i;
				String expectedTitle = "title" + i;
				GenericRecord retrieved = (GenericRecord) genericPage.getRecord(i);
				assertEquals(expectedId, fromFixedBytes(retrieved.getFieldBytes("movieId")));
				assertEquals(expectedTitle, fromFixedBytes(retrieved.getFieldBytes("title")));
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testConcurrentAllocateNoDuplicatePageIds() throws Exception {
		// Goal: FileState.allocatePageId() hands out unique, contiguous ids under
		// concurrent access — the ReentrantLock must make the read-compute-write atomic.
		// Without the lock, racing threads read the same nextPageId and collide.
		File tempFile = File.createTempFile("alloc", ".dat");
		tempFile.deleteOnExit();
		// empty file (length 0) → ids start at 0 and are driven entirely by nextPageId
		FileState fileState = new FileState(tempFile.getAbsolutePath());

		final int threads = 16;
		final int perThread = 500;
		final int total = threads * perThread;

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<List<Integer>>> futures = new ArrayList<>();

		for (int t = 0; t < threads; t++) {
			futures.add(pool.submit(() -> {
				start.await(); // release all threads at once to maximize contention
				List<Integer> ids = new ArrayList<>(perThread);
				for (int i = 0; i < perThread; i++) {
					ids.add(fileState.allocatePageId());
				}
				return ids;
			}));
		}

		start.countDown();
		Set<Integer> all = new HashSet<>();
		for (Future<List<Integer>> f : futures) {
			all.addAll(f.get());
		}
		pool.shutdown();

		// no duplicates, and the union covers a contiguous 0..total-1
		assertEquals(total, all.size(), "duplicate page ids handed out under concurrency");
		for (int id = 0; id < total; id++) {
			assertTrue(all.contains(id), "missing page id " + id);
		}
	}

	@Test
	public void testConcurrentGetOrCreateReturnsSameFileState() throws Exception {
		// Goal: getOrCreateFileState() returns one shared FileState per file even under
		// concurrent first-touch — fileStatesLock must make the check-then-put atomic.
		// Without the lock, two threads can each create a FileState and one is lost.
		// Looped over fresh managers to widen the (tiny) first-touch race window.
		final int iterations = 200;
		final int threads = 8;

		for (int iter = 0; iter < iterations; iter++) {
			BufferManager manager = new BufferManager(3);
			String fileId = "concurrentFile" + iter;

			ExecutorService pool = Executors.newFixedThreadPool(threads);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<FileState>> futures = new ArrayList<>();

			for (int t = 0; t < threads; t++) {
				futures.add(pool.submit(() -> {
					start.await();
					return manager.getOrCreateFileState(fileId);
				}));
			}

			start.countDown();
			Set<FileState> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
			for (Future<FileState> f : futures) {
				distinct.add(f.get());
			}
			pool.shutdown();

			assertEquals(1, distinct.size(), "iteration " + iter + ": multiple FileState instances created");
		}
	}

	@Test
	public void testUnregisterRemovesCatalogEntry() {
		// Goal: unregister removes exactly the named entry; removing an absent
		// entry is a harmless no-op (per-query cleanup may run more than once).
		assertNotNull(bm.getCatalogEntry(fileOneName));
		bm.unregister(fileOneName);
		assertNull(bm.getCatalogEntry(fileOneName));
		assertDoesNotThrow(() -> bm.unregister(fileOneName));
	}

	@Test
	public void testDiscardFileFreesFramesAndForgetsPageIds() throws Exception {
		// Goal: discardFile drops every buffered page of the file, returns the
		// frames to the free list, and resets page-id allocation (a scratch
		// fileId has no backing file, so ids restart at 0 after discard).
		String scratchId = "discard-scratch";
		Page pageA = bm.createPage(scratchId, null);
		Page pageB = bm.createPage(scratchId, null);
		assertEquals(0, pageA.getPid());
		assertEquals(1, pageB.getPid());
		bm.unpinPage(scratchId, pageA.getPid());
		bm.unpinPage(scratchId, pageB.getPid());

		bm.discardFile(scratchId);

		assertFalse(bm.bufferedFileIds().contains(scratchId), "no pages of the file may remain buffered");
		assertEquals(3, bm.getFreeFrameCount(), "discarded frames must return to the free list");
		assertEquals(0, bm.createPage(scratchId, null).getPid(), "page ids restart once the file is forgotten");
	}

	@Test
	public void testFreshPoolHandsOutEveryFrameWithNoFreeList() throws Exception {
		// Goal: the FREE state words alone are enough to allocate a fresh pool.
		// Worked example: a pool of 3 reports 3 free frames, three createPage
		// calls return page ids 0, 1 and 2, and no frame is left free.
		String scratchId = "fresh-pool";
		assertEquals(3, bm.getFreeFrameCount());
		assertEquals(0, bm.createPage(scratchId, null).getPid());
		assertEquals(1, bm.createPage(scratchId, null).getPid());
		assertEquals(2, bm.createPage(scratchId, null).getPid());
		int[] buffered = bm.listPageID();
		Arrays.sort(buffered);
		assertArrayEquals(new int[] {0, 1, 2}, buffered);
		assertEquals(0, bm.getFreeFrameCount(), "a full pool has no free frame left");
	}

	@Test
	public void testFramesFreedByDiscardAreFoundByLaterAllocations() throws Exception {
		// Goal: discardFile hands its frames back through the state word only,
		// so every one of them must be reachable again, not just the first.
		String discarded = "discard-reuse";
		for (int i = 0; i < 3; i++) {
			bm.unpinPage(discarded, bm.createPage(discarded, null).getPid());
		}
		assertEquals(0, bm.getFreeFrameCount());

		bm.discardFile(discarded);
		assertEquals(3, bm.getFreeFrameCount());

		String reuse = "discard-reuse-next";
		assertEquals(0, bm.createPage(reuse, null).getPid());
		assertEquals(1, bm.createPage(reuse, null).getPid());
		assertEquals(2, bm.createPage(reuse, null).getPid());
		assertEquals(0, bm.getFreeFrameCount());
	}

	@Test
	public void testFailedEvictionDoesNotLoseAFrame() throws Exception {
		// Goal: a failure in the allocate-or-evict path leaves every frame
		// findable. The write-back of a dirty victim is aimed into a directory
		// that does not exist yet, so the eviction fails; creating the directory
		// then lets the very same allocation succeed on an intact pool.
		File missingDir = new File(System.getProperty("java.io.tmpdir"), "redb-lost-frame-" + System.nanoTime());
		File scratchFile = new File(missingDir, "scratch.dat");
		scratchFile.deleteOnExit();
		missingDir.deleteOnExit();
		String scratchId = scratchFile.getAbsolutePath();
		for (int i = 0; i < 3; i++) {
			Page page = bm.createPage(scratchId, null);
			bm.markDirty(scratchId, page.getPid());
			bm.unpinPage(scratchId, page.getPid());
		}

		assertThrows(IOException.class, () -> bm.createPage(scratchId, null));
		assertEquals(3, bm.getFreeFrameCount() + bm.listPageID().length, "the failed eviction lost a frame");

		assertTrue(missingDir.mkdirs());
		assertDoesNotThrow(() -> bm.createPage(scratchId, null));
	}

	@Test
	public void testDiscardFileLeavesOtherFilesAlone() throws Exception {
		// Goal: discarding one file must not touch another file's pages or pins.
		String scratchId = "discard-scratch-other";
		Page scratch = bm.createPage(scratchId, null);
		bm.unpinPage(scratchId, scratch.getPid());
		bm.getPage(fileOneName, 0); // stays pinned across the discard

		bm.discardFile(scratchId);

		assertTrue(bm.bufferedFileIds().contains(fileOneName));
		assertEquals(1, bm.getPinCount(fileOneName, 0));
		bm.unpinPage(fileOneName, 0);
	}

	@Test
	public void testDiscardFileThrowsOnPinnedPage() throws Exception {
		// Goal: discarding a file whose page is still pinned is a caller bug and
		// must fail loudly instead of yanking an in-use frame.
		String scratchId = "discard-pinned";
		Page page = bm.createPage(scratchId, null); // pinned by createPage
		assertThrows(IllegalStateException.class, () -> bm.discardFile(scratchId));

		// once unpinned, the same discard succeeds
		bm.unpinPage(scratchId, page.getPid());
		assertDoesNotThrow(() -> bm.discardFile(scratchId));
		assertFalse(bm.bufferedFileIds().contains(scratchId));
	}

	@Test
	public void testFullPageInsertion() {
		// Goal: confirm page-full detection and append-only page allocation.
		// Spec mapping: "Only the last page can have free space."
		// Setup: insert rows until insertRow returns -1.
		// Expect: next insert succeeds on a newly created page.
		//
		// Pseudocode:
		// while (insertRow != -1) continue
		// newPage = bm.createPage(MOVIES_DB)
		// assert insertRow(newPage) succeeds
		try {
			Page page = bm.createPage(fileOneName, null);
			GenericPage genericPage = new GenericPage(page, MOVIE_SCHEMA);
			int slotId = 0;
			GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, "movie0", "title1");
			int count = 0;
			while ((slotId = genericPage.insertRecord(record)) != -1) {
				count += 1;
				record = makeMovieRecord(MOVIE_SCHEMA, "movie" + count, "title" + count);
			}
			bm.markDirty(fileOneName, page.getPid());
			bm.unpinPage(fileOneName, page.getPid());

			// Test
			Page newPage = bm.createPage(fileOneName, null);
			GenericPage newGenericPage = new GenericPage(newPage, MOVIE_SCHEMA);
			assertEquals(-1, genericPage.insertRecord(record));
			assertEquals(0, newGenericPage.insertRecord(record));

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
