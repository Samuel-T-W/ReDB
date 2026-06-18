package buffer;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import catalog.TableEntry;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
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
				int offset = RawPage.getOffset(pageId);
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
			int offset = RawPage.getOffset(pageId);
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
	void testGetPageThatDoesNotExist() throws Exception {
		// page 10 doesn't exist in the file (only pages 0-3)
		assertThrows(java.io.EOFException.class, () -> {
			bm.getPage(fileOneName, 10);
		});
	}

	@Test
	public void testLRUEviction() {
		// Goal: validate LRU replacement policy.
		// Spec mapping: "LRU policy... page is used on getPage/createPage."
		// Setup: access A, B, then touch A, then allocate C.
		// Expect: B is evicted (least recently used).
		//
		// Pseudocode:
		// A=create+unpin, B=create+unpin, getPage(A), create C
		// assert B evicted, A and C in memory
		try {
			String fileTwoName = "fileTwo";
			File tempFile = File.createTempFile(fileTwoName, ".dat");
			tempFile.deleteOnExit();
			fileTwoName = tempFile.getAbsolutePath();
			bm = new BufferManager(2);
			bm.register(new TableEntry(fileTwoName, MOVIE_SCHEMA));
			Page page_A = bm.createPage(fileTwoName, null);
			bm.unpinPage(fileTwoName, page_A.getPid());
			Page page_B = bm.createPage(fileTwoName, null);
			bm.unpinPage(fileTwoName, page_B.getPid());
			bm.getPage(fileTwoName, page_A.getPid());
			Page page_C = bm.createPage(fileTwoName, null);
		} catch (Exception e) {
			e.printStackTrace();
		}

		// Assert page B is evicted
		int[] ids = bm.listPageID();
		int[] expected = new int[]{0, 2};
		for (int i = 0; i < ids.length; i++) {
			System.out.println(ids[0]);
		}
		assertArrayEquals(expected, ids);
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
