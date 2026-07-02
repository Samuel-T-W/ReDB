package end2end;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import buffer.*;
import catalog.TableEntry;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import storage.*;

/** Lab 1End2End Test */
public class End2EndTest {

	// ----------------------
	// Shared config (edit once)
	// ----------------------
	private static final String MOVIES_TSV = "data/title.csv";
	private static final String MOVIES_DB = "movies.db";
	private static final int PAGE_SIZE = 4096;
	private static final int BUFFER_SIZE = 4;
	private static final int MOVIE_ID_LEN = 9;
	private static final int TITLE_LEN = 30;
	private static final int MAX_TEST_ROWS = 400;

	// Movies schema: movieId=9 bytes, title=30 bytes → recordSize=39
	private static final Map<String, Integer> MOVIE_SCHEMA = new LinkedHashMap<>();
	private static final int RECORD_SIZE = 9 + 30; // 39 bytes

	static {
		MOVIE_SCHEMA.put("movieId", 9);
		MOVIE_SCHEMA.put("title", 30);
	}

	private BufferManager bm;

	@BeforeEach
	void setup() throws Exception {
		bm = new BufferManager(3);
		try {
			File database = new File(MOVIES_DB);
			if (database.delete()) {
				System.out.println("DATABASE DELETE!");
			} else {
				System.out.println("FAILED TO DELETE DATABASE!");
			}

			if (database.createNewFile()) {
				System.out.println("DATABASE CREATED!");
			} else {
				System.out.println("DATABASE ALREADY EXIST!");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		bm.register(new TableEntry(MOVIES_DB, MOVIE_SCHEMA));
	}

	@AfterEach
	void cleanup() throws Exception {
		File database = new File(MOVIES_DB);
		if (database.delete()) {
			System.out.println("DATABASE DELETE!");
		} else {
			System.out.println("FAILED TO DELETE DATABASE!");
		}
	}

	// ----------------------
	// End to end test
	// ----------------------
	@Test
	public void testLoadMoviesFromTsv() {
		/**
		 * Check basic create/load/read data to page Load rows from csv file, insert
		 * into page until full, then create new page and repeat Test on 400 rows
		 */
		System.out.println("START TEST LOAD MOVIES FROM TSV");
		Page page;
		try {
			page = bm.createPage(MOVIES_DB, null);
		} catch (IOException e) {
			fail("CREATE PAGE 0 FAILED", e);
			return;
		}
		int slotId = 0;
		int numRow = 0;

		GenericPage genericPage = new GenericPage(page, MOVIE_SCHEMA);

		// Read and load TSV file
		int count = 0;
		String line;
		try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
			while ((line = br.readLine()) != null) {
				if (count == 0) {
					count = 1;
					continue;
				}
				if (count > MAX_TEST_ROWS) {
					break;
				}
				count += 1;
				// Use comma as a separator
				String[] values = line.split(",");
				String movieId = values[0];
				String title = values[1];

				GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
				if ((slotId = genericPage.insertRecord(record)) != -1) {
					numRow += 1;
					bm.markDirty(MOVIES_DB, page.getPid());
				} else {
					bm.unpinPage(MOVIES_DB, page.getPid());
					try {
						page = bm.createPage(MOVIES_DB, null);
						genericPage = new GenericPage(page, MOVIE_SCHEMA);
						genericPage.insertRecord(record);
						numRow += 1;
						bm.markDirty(MOVIES_DB, page.getPid());
					} catch (IOException e) {
						System.out.println("CREATE PAGE FAILED " + count);
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		bm.unpinPage(MOVIES_DB, page.getPid());
		try {
			bm.force();
		} catch (IOException e) {
			System.out.println("Buffer Manager force failed");
		}
		// Assert number of page created is correct and the page is full except the last
		// page
		try {
			Page testPage = bm.getPage(MOVIES_DB, 0);
			genericPage = new GenericPage(testPage, MOVIE_SCHEMA);
			bm.unpinPage(MOVIES_DB, testPage.getPid());
			assertEquals(true, genericPage.isFull());

			testPage = bm.getPage(MOVIES_DB, 1);
			genericPage = new GenericPage(testPage, MOVIE_SCHEMA);
			bm.unpinPage(MOVIES_DB, testPage.getPid());
			assertEquals(true, genericPage.isFull());

			testPage = bm.getPage(MOVIES_DB, 2);
			genericPage = new GenericPage(testPage, MOVIE_SCHEMA);
			bm.unpinPage(MOVIES_DB, testPage.getPid());
			assertEquals(true, genericPage.isFull());

			testPage = bm.getPage(MOVIES_DB, 3);
			genericPage = new GenericPage(testPage, MOVIE_SCHEMA);
			bm.unpinPage(MOVIES_DB, testPage.getPid());
			assertEquals(false, genericPage.isFull());
		} catch (IOException e) {
			System.out.println("GET PAGE FAIL");
		}

		assertEquals(MAX_TEST_ROWS, numRow);
		System.out.println("FINISH TEST LOAD MOVIES FROM TSV");
	}

	@Test
	public void testAppendOnlyBehavior() {
		/**
		 * Test that only new, not full page can be insert. Setup: Load rows to a page
		 * until full, then create another page. Assert that only new page can be
		 * insertted with more rows.
		 */
		Page page;
		GenericPage genericPage;
		try {
			page = bm.createPage(MOVIES_DB, null);
			genericPage = new GenericPage(page, MOVIE_SCHEMA);
		} catch (IOException e) {
			fail("CREATE PAGE 0 FAILED", e);
			return;
		}
		Page newPage = null;
		GenericPage newGenericPage = null;
		int slotId = 0;
		String movieId = "defaultID";
		String title = "defaultTitle";

		// Read TSV file
		int count = 0;
		String line;
		try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
			while ((line = br.readLine()) != null) {
				if (count == 0) {
					count = 1;
					continue;
				}
				if (count > MAX_TEST_ROWS) {
					break;
				}
				// Use comma as a separator
				String[] values = line.split(",");
				movieId = values[0];
				title = values[1];

				GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
				if ((slotId = genericPage.insertRecord(record)) != -1) {
					bm.markDirty(MOVIES_DB, page.getPid());
				} else {
					bm.unpinPage(MOVIES_DB, page.getPid());
					try {
						newPage = bm.createPage(MOVIES_DB, null);
						newGenericPage = new GenericPage(newPage, MOVIE_SCHEMA);
						break;
					} catch (IOException e) {
						System.out.println("CREATE PAGE FAILED " + count);
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		// Assert Can only insert to new page
		GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
		assertEquals(-1, genericPage.insertRecord(record));
		assertEquals(0, newGenericPage.insertRecord(record));
	}

	@Test
	public void testInterleavedInsertReadTrace() {
		// Goal: follow the exact spec trace for interleaving insert and getPage.
		// Spec mapping: "Interleaved queries and insertions" example trace.
		// Setup: insert partial page, unpin, force eviction, reload same page, insert
		// more.
		// Expect: all rows remain consistent after reload.
		//
		// Pseudocode:
		// insert few rows -> unpin -> force eviction -> getPage -> insert more
		// verify all rows exist
		Page page;
		GenericPage genericPage;
		try {
			page = bm.createPage(MOVIES_DB, null);
			genericPage = new GenericPage(page, MOVIE_SCHEMA);
		} catch (IOException e) {
			fail("CREATE PAGE 0 FAILED", e);
			return;
		}
		String movieId = "defaultID";
		String title = "defaultTitle";

		// Insert customize rows for ease of testing
		for (int i = 0; i < 10; i++) {
			movieId = "tt000000" + i;
			title = "Movie" + i;
			GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
			genericPage.insertRecord(record);
			bm.markDirty(MOVIES_DB, page.getPid());
		}
		// unpin
		bm.unpinPage(MOVIES_DB, page.getPid());
		// force
		try {
			bm.force();
		} catch (IOException e) {
			System.out.println("Buffer Manager force failed");
		}
		// get page
		try {
			page = bm.getPage(MOVIES_DB, 0);
		} catch (IOException e) {
			System.out.println("GET PAGE FAIL");
		}
		// insert more
		for (int i = 0; i < 10; i++) {
			movieId = "tt00000" + (10 + i);
			title = "Movie" + (10 + i);
			GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
			genericPage.insertRecord(record);
		}

		// Test all record exists and corrrect
		String expectedId = "";
		String expectedTitle = "";
		for (int i = 0; i < 20; i++) {
			GenericRecord retrieved = (GenericRecord) genericPage.getRecord(i);
			if (i >= 10) {
				expectedId = "tt00000" + i;
				expectedTitle = "Movie" + i;
			} else {
				expectedId = "tt000000" + i;
				expectedTitle = "Movie" + i;
			}
			assertEquals(expectedId, fromFixedBytes(retrieved.getFieldBytes("movieId")));
			assertEquals(expectedTitle, fromFixedBytes(retrieved.getFieldBytes("title")));
		}
	}

	@Test
	public void testRandomReadsDuringLoad() {
		// Goal: mix insertions with random read queries.
		// Spec mapping: "Consider different frequencies of inserts and reads."
		// Setup: every N inserts, read a random pageId.
		// Expect: random reads return valid, previously inserted rows.
		//
		// Pseudocode:
		// every N inserts -> getPage(randomPid) -> basic sanity check
		Page page = null;
		try {
			page = bm.createPage(MOVIES_DB, null);
		} catch (IOException e) {
			fail("CREATE PAGE 0 FAILED", e);
			return;
		}
		int slotId = 0;
		int numRow = 0;

		GenericPage genericPage = new GenericPage(page, MOVIE_SCHEMA);

		// Bytes arrays to store data when a page is full.
		byte[][] expected_data = new byte[10][RawPage.MAX_PAGE_LEN];
		byte[] check_data = new byte[RawPage.MAX_PAGE_LEN];
		byte[][] generic_data = new byte[10][RawPage.MAX_PAGE_LEN];
		int count = 0;
		int maxPID = 0;
		String line;
		int N = 100;
		try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
			while ((line = br.readLine()) != null) {
				if (count == 0) {
					count = 1;
					continue;
				}
				if (count > 600) {
					break;
				}
				count += 1;
				// Use comma as a separator
				String[] values = line.split(",");
				if (values.length < 2) {
					break;
				}
				String movieId = values[0];
				String title = values[1];

				GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
				if ((slotId = genericPage.insertRecord(record)) != -1) {
					numRow += 1;
					bm.markDirty(MOVIES_DB, page.getPid());
				} else {
					bm.unpinPage(MOVIES_DB, page.getPid());
					try {
						// Save data
						expected_data[page.getPid()] = page.getByteArray();
						generic_data[page.getPid()] = genericPage.getByteArray();

						page = bm.createPage(MOVIES_DB, null);
						genericPage = new GenericPage(page, MOVIE_SCHEMA);
						genericPage.insertRecord(record);
						numRow += 1;
						maxPID += 1;
						bm.markDirty(MOVIES_DB, page.getPid());
					} catch (IOException e) {
						System.out.println("CREATE PAGE FAILED " + count);
					}
				}

				if (count % N == 0) {
					//
					for (int pid = 0; pid < maxPID; pid++) {
						Page newPage = bm.getPage(MOVIES_DB, pid);
						GenericPage newGenericPage = new GenericPage(newPage, MOVIE_SCHEMA);
						check_data = newPage.getByteArray();

						// Assert that saved data and loaded data must be the same. Also
						assertArrayEquals(expected_data[pid], check_data);
						assertArrayEquals(expected_data[pid], generic_data[pid]);
						System.out.println("DONE " + pid);

						bm.unpinPage(MOVIES_DB, newPage.getPid());
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		bm.unpinPage(MOVIES_DB, page.getPid());
	}

	@Test
	public void testDifferentInsertReadFrequencies() {
		// Goal: exercise different insert/read ratios.
		// Spec mapping: "Consider different frequencies of inserts and reads."
		// Setup: run two scenarios (e.g., 100:1 and 10:1).
		// Expect: correctness maintained in both scenarios.
		//
		// Pseudocode:
		// run load with (100 inserts : 1 read) and (10:1)
		Page page = null;
		int slotId = 0;
		int numRow = 0;
		GenericPage genericPage = null;

		// Test multiple insert-load ratio
		int[] ratio = new int[]{10, 20, 30, 40, 100};
		for (int i = 0; i < ratio.length; i++) {
			try {
				setup();
				page = bm.createPage(MOVIES_DB, null);
				genericPage = new GenericPage(page, MOVIE_SCHEMA);
			} catch (Exception e) {
				e.printStackTrace();
			}
			byte[][] expected_data = new byte[20][RawPage.MAX_PAGE_LEN];
			byte[] check_data = new byte[RawPage.MAX_PAGE_LEN];
			byte[][] generic_data = new byte[20][RawPage.MAX_PAGE_LEN];
			// Read TSV file
			int count = 0;
			int maxPID = 0;
			String line;
			int N = ratio[i];
			try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
				while ((line = br.readLine()) != null) {
					if (count == 0) {
						count = 1;
						continue;
					}
					if (count > 600) {
						break;
					}
					count += 1;
					// Use comma as a separator
					String[] values = line.split(",");
					if (values.length < 2) {
						break;
					}
					String movieId = values[0];
					String title = values[1];

					GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
					if ((slotId = genericPage.insertRecord(record)) != -1) {
						numRow += 1;
						bm.markDirty(MOVIES_DB, page.getPid());
					} else {
						bm.unpinPage(MOVIES_DB, page.getPid());
						try {
							// Save data
							expected_data[page.getPid()] = page.getByteArray();
							generic_data[page.getPid()] = genericPage.getByteArray();

							page = bm.createPage(MOVIES_DB, null);
							genericPage = new GenericPage(page, MOVIE_SCHEMA);
							genericPage.insertRecord(record);
							numRow += 1;
							maxPID += 1;
							bm.markDirty(MOVIES_DB, page.getPid());
						} catch (IOException e) {
							System.out.println("CREATE PAGE FAILED " + count);
						}
					}

					if (count % N == 0) {
						// Test get page
						for (int pid = 0; pid < maxPID; pid++) {
							Page newPage = bm.getPage(MOVIES_DB, pid);
							GenericPage newGenericPage = new GenericPage(newPage, MOVIE_SCHEMA);
							check_data = newPage.getByteArray();

							assertArrayEquals(expected_data[pid], check_data);
							assertArrayEquals(expected_data[pid], generic_data[pid]);
							System.out.println("DONE " + pid);

							bm.unpinPage(MOVIES_DB, newPage.getPid());
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			bm.unpinPage(MOVIES_DB, page.getPid());
		}
	}

	@Test
	public void testChangingInsertReadFrequencies() {
		// Test changin insert-load frequency mid way
		Page page = null;
		try {
			page = bm.createPage(MOVIES_DB, null);
		} catch (IOException e) {
			fail("CREATE PAGE 0 FAILED", e);
			return;
		}
		int slotId = 0;
		int numRow = 0;

		GenericPage genericPage = new GenericPage(page, MOVIE_SCHEMA);

		// Read TSV file
		byte[][] expected_data = new byte[10][RawPage.MAX_PAGE_LEN];
		byte[] check_data = new byte[RawPage.MAX_PAGE_LEN];
		byte[][] generic_data = new byte[10][RawPage.MAX_PAGE_LEN];
		int count = 0;
		int maxPID = 0;
		String line;
		int N = 100;
		try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
			while ((line = br.readLine()) != null) {
				if (count == 0) {
					count = 1;
					continue;
				}
				if (count > 600) {
					break;
				}
				if (count % 100 == 0) {
					N = N + 10;
				}
				count += 1;
				// Use comma as a separator
				String[] values = line.split(",");
				if (values.length < 2) {
					break;
				}
				String movieId = values[0];
				String title = values[1];

				GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
				if ((slotId = genericPage.insertRecord(record)) != -1) {
					numRow += 1;
					bm.markDirty(MOVIES_DB, page.getPid());
				} else {
					bm.unpinPage(MOVIES_DB, page.getPid());
					try {
						// Save data
						expected_data[page.getPid()] = page.getByteArray();
						generic_data[page.getPid()] = genericPage.getByteArray();

						page = bm.createPage(MOVIES_DB, null);
						genericPage = new GenericPage(page, MOVIE_SCHEMA);
						genericPage.insertRecord(record);
						numRow += 1;
						maxPID += 1;
						bm.markDirty(MOVIES_DB, page.getPid());
					} catch (IOException e) {
						System.out.println("CREATE PAGE FAILED " + count);
					}
				}

				if (count % N == 0) {
					// Test get page
					for (int pid = 0; pid < maxPID; pid++) {
						Page newPage = bm.getPage(MOVIES_DB, pid);
						GenericPage newGenericPage = new GenericPage(newPage, MOVIE_SCHEMA);
						check_data = newPage.getByteArray();

						assertArrayEquals(expected_data[pid], check_data);
						assertArrayEquals(expected_data[pid], generic_data[pid]);
						System.out.println("DONE " + pid);

						bm.unpinPage(MOVIES_DB, newPage.getPid());
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		bm.unpinPage(MOVIES_DB, page.getPid());
	}

	@Test
	public void testWriteOnEvictionOnly() {
		// Goal: validate write-back policy.
		// Spec mapping: "Rows are written to the data file only when dirty pages are
		// evicted."
		// Setup: insert + unpin without eviction; then force eviction.
		// Expect: disk write occurs on eviction, not on unpin.
		//
		// Pseudocode:
		// insert row, unpin -> no write count change
		// force eviction -> write count increments

		// create an example binary file
		Page page;
		GenericPage genericPage;
		try {
			page = bm.createPage(MOVIES_DB, null);
			genericPage = new GenericPage(page, MOVIE_SCHEMA);
		} catch (IOException e) {
			fail("CREATE PAGE 0 FAILED", e);
			return;
		}
		String movieId = "defaultID";
		String title = "defaultTitle";
		for (int i = 0; i < 10; i++) {
			movieId = "tt000000" + i;
			title = "Movie" + i;
			GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
			genericPage.insertRecord(record);
			bm.markDirty(MOVIES_DB, page.getPid());
		}
		bm.unpinPage(MOVIES_DB, page.getPid());

		// Read current database
		byte[] current_data = new byte[RawPage.MAX_PAGE_LEN];
		try (RandomAccessFile raf = new RandomAccessFile(MOVIES_DB, "r")) {
			int offset = 0;
			raf.seek(offset);
			current_data = new byte[RawPage.MAX_PAGE_LEN];
			raf.readFully(current_data);
		} catch (IOException e) {
			System.out.println("READ DATABASE FAIL!");
		}

		// Read TSV file
		for (int i = 0; i < 10; i++) {
			movieId = "tt000000" + i;
			title = "Movie" + i;
			GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
			genericPage.insertRecord(record);
		}
		bm.markDirty(MOVIES_DB, page.getPid());
		// unpin
		bm.unpinPage(MOVIES_DB, page.getPid());

		// Read database again
		byte[] check_data = new byte[RawPage.MAX_PAGE_LEN];
		try (RandomAccessFile raf = new RandomAccessFile(MOVIES_DB, "r")) {
			int offset = 0;
			raf.seek(offset);
			check_data = new byte[RawPage.MAX_PAGE_LEN];
			raf.readFully(check_data);
		} catch (IOException e) {
			System.out.println("READ DATABASE FAIL!");
		}

		// Compare current_data and check_data
		try {
			boolean check = Arrays.equals(current_data, check_data);
			assertTrue(check);
		} catch (Exception e) {
			e.printStackTrace();
		}

		// force write data to disk
		try {
			bm.force();
		} catch (IOException e) {
			System.out.println("Buffer Manager force failed");
		}

		// Read database again
		check_data = new byte[RawPage.MAX_PAGE_LEN];
		try (RandomAccessFile raf = new RandomAccessFile(MOVIES_DB, "r")) {
			int offset = 0;
			raf.seek(offset);
			check_data = new byte[RawPage.MAX_PAGE_LEN];
			raf.readFully(check_data);
		} catch (IOException e) {
			System.out.println("READ DATABASE FAIL!");
		}

		// Compare current_data and check_data
		try {
			boolean check = Arrays.equals(current_data, check_data);
			assertFalse(check);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testLoadMoviesFromTsvCheckContent() {
		/**
		 * Check basic create/load/read data to page Check that the content of inserted
		 * records are correct when loaded later Setup: Inserted 2000 records to page,
		 * store them when first read => Then getPage, get content, and cofirm that the
		 * content is the same as stored rows
		 */
		int slotId = 0;
		int numRow = 0;
		int numPage = 0;
		Page page;
		try {
			page = bm.createPage(MOVIES_DB, null);
			numPage += 1;
		} catch (IOException e) {
			fail("CREATE PAGE 0 FAILED", e);
			return;
		}

		GenericPage genericPage = new GenericPage(page, MOVIE_SCHEMA);

		// Read and load TSV file
		// NUM_ROWS_TO_READ is the number of records to read into pages
		int NUM_ROWS_TO_READ = 2000;
		int count = 0;
		String line;
		String[] movieIDs = new String[NUM_ROWS_TO_READ];
		String[] titles = new String[NUM_ROWS_TO_READ];
		try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
			while ((line = br.readLine()) != null) {
				if (count == 0) {
					count = 1;
					continue;
				}
				if (count > 200) {
					break;
				}
				count += 1;
				// Use comma as a separator
				String[] values = line.split(",");
				String movieId = values[0];
				String title = values[1];

				// Stores movie and title
				movieIDs[numRow] = movieId;
				titles[numRow] = title;

				GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
				if ((slotId = genericPage.insertRecord(record)) != -1) {
					numRow += 1;
					bm.markDirty(MOVIES_DB, page.getPid());
				} else {
					bm.unpinPage(MOVIES_DB, page.getPid());
					try {
						page = bm.createPage(MOVIES_DB, null);
						numPage += 1;
						genericPage = new GenericPage(page, MOVIE_SCHEMA);
						genericPage.insertRecord(record);
						numRow += 1;
						bm.markDirty(MOVIES_DB, page.getPid());
					} catch (IOException e) {
						System.out.println("CREATE PAGE FAILED " + count);
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		bm.unpinPage(MOVIES_DB, page.getPid());

		// Force
		try {
			bm.force();
		} catch (Exception e) {

		}

		// Load rows
		String[] loaded_movieID = new String[NUM_ROWS_TO_READ];
		String[] loaded_titles = new String[NUM_ROWS_TO_READ];
		int count_loaded = 0;
		for (int i = 0; i < numPage; i++) {
			try {
				GenericPage testPage = new GenericPage(bm.getPage(MOVIES_DB, i), MOVIE_SCHEMA);

				// Get rows
				int numRecords = fromByteArray(readBytesFromArray(testPage.getByteArray(), 0, 4));
				for (slotId = 0; slotId < numRecords; slotId++) {
					GenericRecord retrieved = (GenericRecord) testPage.getRecord(slotId);
					String movieId = fromFixedBytes(retrieved.getFieldBytes("movieId"));
					String title = fromFixedBytes(retrieved.getFieldBytes("title"));
					loaded_movieID[count_loaded] = movieId;
					loaded_titles[count_loaded] = title;
					count_loaded++;
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		// Assert rows retrieved are correct
		for (int i = 0; i < NUM_ROWS_TO_READ; i++) {
			assertEquals(movieIDs[i], loaded_movieID[i]);
			assertEquals(titles[i], loaded_titles[i]);
		}
	}
}
