package btree;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import buffer.*;
import catalog.IndexEntry;
import catalog.TableEntry;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import storage.*;

/** End-to-end tests for the BTreeManager. */
public class BTreeBuildMovieIDTest {

	// -----------------------------------------------------------------------
	// Configuration
	// -----------------------------------------------------------------------

	private static final String MOVIES_TSV = "data/title.csv";
	private static final String MOVIES_DB = "movies.db";
	private static final String MOVIEID_INDEX = "movies_id.idx";

	private static final int MOVIE_ID_LEN = 9;
	private static final int TITLE_LEN = 30;

	/** Buffer large enough for data pages and B-tree pages simultaneously. */
	private static final int BUFFER_SIZE = 10;

	private static final int MAX_TEST_ROWS = 4000;

	/** B+ tree degree: each internal node holds up to BTREE_DEGREE-1 keys. */
	private static final int BTREE_DEGREE = 50;

	private static final Map<String, Integer> MOVIE_SCHEMA = new LinkedHashMap<>();

	private static int KEY_SIZE;

	static {
		MOVIE_SCHEMA.put("movieId", 9);
		MOVIE_SCHEMA.put("title", 30);
	}

	private BufferManager bm;

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	// -----------------------------------------------------------------------
	// Lifecycle
	// -----------------------------------------------------------------------

	@BeforeEach
	void setup() throws Exception {
		bm = new BufferManager(BUFFER_SIZE);

		// Reset the movies data file.
		File moviesDb = new File(MOVIES_DB);
		moviesDb.delete();
		moviesDb.createNewFile();

		// Reset the index file.
		File movieIDIdx = new File(MOVIEID_INDEX);
		movieIDIdx.delete();
		movieIDIdx.createNewFile();

		bm.register(new TableEntry(MOVIES_DB, MOVIE_SCHEMA));
		// keySize 16 matches the internal KEY_SIZE used by LeafPage / InternalPage.
		bm.register(new IndexEntry(MOVIEID_INDEX, MOVIE_SCHEMA.get("movieId")));

		KEY_SIZE = MOVIE_SCHEMA.get("movieId");
	}

	@AfterEach
	void cleanup() {
		new File(MOVIES_DB).delete();
		new File(MOVIEID_INDEX).delete();
	}

	// -----------------------------------------------------------------------
	// Tests
	// -----------------------------------------------------------------------

	/**
	 * Test 1 – Build a B+ tree index on the {@code movieId} attribute.
	 *
	 * <p>
	 * Steps:
	 *
	 * <ol>
	 * <li>Sequentially load {@value #MAX_TEST_ROWS} rows from the CSV into
	 * {@code movies.db}, interacting directly with the BufferManager.
	 * <li>Scan every data page of {@code movies.db} via the BufferManager.
	 * <li>For each record, extract the {@code movieId} bytes, wrap them in a
	 * {@link K} key, and pair with a {@link RecordId}(pageId, slotId).
	 * <li>Insert each {@code (key, rid)} into a {@link BTreeManager}.
	 * </ol>
	 *
	 * <p>
	 * The test asserts that:
	 *
	 * <ul>
	 * <li>Loading the table completes without any exception.
	 * <li>Building the index completes without any exception.
	 * <li>The number of entries inserted into the index equals the number of rows
	 * loaded into the table ({@value #MAX_TEST_ROWS}).
	 * </ul>
	 */
	@Test
	public void testBuildMovieIDIndex() throws Exception {
		// ----------------------------------------------------------------
		// Phase 1: Load the Movies table from CSV into movies.db
		// ----------------------------------------------------------------
		int numDataPages = 0;

		Page currentPage = bm.createPage(MOVIES_DB, null);
		numDataPages++;
		GenericPage genericPage = new GenericPage(currentPage, MOVIE_SCHEMA);

		int count = 0;
		int numRows = 0;
		String line;

		try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
			while ((line = br.readLine()) != null) {
				if (count == 0) {
					count = 1;
					continue;
				} // skip CSV header
				if (count > MAX_TEST_ROWS)
					break;
				count++;

				String[] values = line.split(",");
				String movieId = values[0];
				String title = values[1];

				GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);

				int slotId = genericPage.insertRecord(record);
				if (slotId != -1) {
					numRows++;
					bm.markDirty(MOVIES_DB, currentPage.getPid());
				} else {
					// Current page full: flush it and open a new one.
					bm.unpinPage(MOVIES_DB, currentPage.getPid());

					currentPage = bm.createPage(MOVIES_DB, null);
					numDataPages++;
					genericPage = new GenericPage(currentPage, MOVIE_SCHEMA);

					genericPage.insertRecord(record);
					numRows++;
					bm.markDirty(MOVIES_DB, currentPage.getPid());
				}
			}
		}

		bm.unpinPage(MOVIES_DB, currentPage.getPid());
		bm.force();

		assertEquals(MAX_TEST_ROWS, numRows, "Phase 1: expected " + MAX_TEST_ROWS + " rows loaded");

		// ----------------------------------------------------------------
		// Phase 2: Build the B+ tree index on the movieId field
		// ----------------------------------------------------------------
		BTreeManager btree = new BTreeManager(BTREE_DEGREE, MOVIEID_INDEX, bm, KEY_SIZE);

		int totalInserted = 0;

		for (int pid = 0; pid < numDataPages; pid++) {
			Page dataPage = bm.getPage(MOVIES_DB, pid);
			GenericPage gp = new GenericPage(dataPage, MOVIE_SCHEMA);

			int numRecords = fromByteArray(readBytesFromArray(dataPage.getByteArray(), 0, 4));

			for (int slotId = 0; slotId < numRecords; slotId++) {
				GenericRecord rec = (GenericRecord) gp.getRecord(slotId);
				byte[] movieIDBytes = rec.getFieldBytes("movieId");
				K key = new K(movieIDBytes);
				RecordId rid = new RecordId(pid, slotId);

				assertDoesNotThrow(() -> btree.insert(key, rid),
						"insert should not throw for key at (" + pid + "," + slotId + ")");
				totalInserted++;
			}

			bm.unpinPage(MOVIES_DB, pid);
		}

		// All rows that were loaded into the table must appear in the index.
		assertEquals(MAX_TEST_ROWS, totalInserted,
				"Phase 2: expected " + MAX_TEST_ROWS + " entries inserted into the index");

		// Phase 3: spot-check search — "tt0000001" is the first movieId in the dataset.
		K searchKey = new K(toFixedBytes("tt0000001", 9));
		java.util.Iterator<RecordId> it = btree.search(searchKey);
		assertTrue(it.hasNext(), "search for \"tt0000001\" should return at least one result");
	}

	@Test
	public void testBulkLoading() throws Exception {
		// --- 1. Load movies into data pages ---
		List<K> allMovieID = new ArrayList<>();
		List<RecordId> allRids = new ArrayList<>();

		// Dynamic list because page count is not known upfront
		List<Integer> recPerPageList = new ArrayList<>();

		Page curPage = bm.createPage(MOVIES_DB, null);
		GenericPage curGP = new GenericPage(curPage, MOVIE_SCHEMA);
		int curCount = 0; // records inserted on the current page
		int numDataPages = 1;
		int count = 0;
		int numRows = 0;

		try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
			String line;
			boolean header = true;
			while ((line = br.readLine()) != null) {
				if (count > MAX_TEST_ROWS)
					break;
				if (header) {
					count = 1;
					header = false;
					continue;
				}
				count++;

				String[] parts = line.split(",", 2);
				if (parts.length < 2)
					continue;
				String movieId = parts[0].trim();
				String title = parts[1].trim();

				GenericRecord rec = makeMovieRecord(MOVIE_SCHEMA, movieId, title);
				int slotId = curGP.insertRecord(rec);
				if (slotId == -1) {
					// Current page is full — seal it and open a new one
					recPerPageList.add(curCount);
					bm.markDirty(MOVIES_DB, curPage.getPid());
					bm.unpinPage(MOVIES_DB, curPage.getPid());
					curPage = bm.createPage(MOVIES_DB, null);
					curGP = new GenericPage(curPage, MOVIE_SCHEMA);
					slotId = curGP.insertRecord(rec);
					curCount = 0;
					numDataPages++;
				}
				allRids.add(new RecordId(curPage.getPid(), slotId));
				allMovieID.add(fixedAsciiKey(movieId, MOVIE_ID_LEN));
				curCount++;
				numRows++;
			}
		}
		// Seal the last page
		recPerPageList.add(curCount);
		bm.markDirty(MOVIES_DB, curPage.getPid());
		bm.unpinPage(MOVIES_DB, curPage.getPid());

		int[] recordsPerPage = recPerPageList.stream().mapToInt(i -> i).toArray();
		int totalMovies = allMovieID.size();
		System.out.printf("Loaded %d movies into %d data page(s).%n", totalMovies, numDataPages);

		assertEquals(MAX_TEST_ROWS, numRows, "Phase 1: expected " + MAX_TEST_ROWS + " rows loaded");

		// ----------------------------------------------------------------
		// Phase 2: Build the B+ tree index on the movieId field with bulk loading
		// ----------------------------------------------------------------
		BTreeManager btree = new BTreeManager(BTREE_DEGREE, MOVIEID_INDEX, bm, KEY_SIZE);

		int totalInserted = 0;

		totalInserted = btree.bulkLoad(allMovieID, allRids);

		// All rows that were loaded into the table must appear in the index.
		assertEquals(MAX_TEST_ROWS, totalInserted,
				"Phase 2: expected " + MAX_TEST_ROWS + " entries inserted into the index");
	}
}
