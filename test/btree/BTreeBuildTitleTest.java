package btree;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import buffer.*;
import catalog.IndexEntry;
import catalog.TableEntry;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import storage.*;

/** End-to-end tests for the BTreeManager. */
public class BTreeBuildTitleTest {

	// -----------------------------------------------------------------------
	// Configuration
	// -----------------------------------------------------------------------

	private static final String MOVIES_TSV = "data/title.csv";
	private static final String MOVIES_DB = "movies.db";
	private static final String TITLE_INDEX = "movies_title.idx";

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
		File titleIdx = new File(TITLE_INDEX);
		titleIdx.delete();
		titleIdx.createNewFile();

		bm.register(new TableEntry(MOVIES_DB, MOVIE_SCHEMA));
		// keySize 16 matches the internal KEY_SIZE used by LeafPage / InternalPage.
		bm.register(new IndexEntry(TITLE_INDEX, MOVIE_SCHEMA.get("title")));

		KEY_SIZE = MOVIE_SCHEMA.get("title");
	}

	@AfterEach
	void cleanup() {
		new File(MOVIES_DB).delete();
		new File(TITLE_INDEX).delete();
	}

	// -----------------------------------------------------------------------
	// Tests
	// -----------------------------------------------------------------------

	/**
	 * Test 1 – Build a B+ tree index on the {@code title} attribute.
	 *
	 * <p>
	 * Steps:
	 *
	 * <ol>
	 * <li>Sequentially load {@value #MAX_TEST_ROWS} rows from the CSV into
	 * {@code movies.db}, interacting directly with the BufferManager.
	 * <li>Scan every data page of {@code movies.db} via the BufferManager.
	 * <li>For each record, extract the {@code title} bytes, wrap them in a
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
	public void testBuildTitleIndex() throws Exception {
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
		// Phase 2: Build the B+ tree index on the title field
		// ----------------------------------------------------------------
		BTreeManager btree = new BTreeManager(BTREE_DEGREE, TITLE_INDEX, bm, KEY_SIZE);

		int totalInserted = 0;

		for (int pid = 0; pid < numDataPages; pid++) {
			Page dataPage = bm.getPage(MOVIES_DB, pid);
			GenericPage gp = new GenericPage(dataPage, MOVIE_SCHEMA);

			int numRecords = fromByteArray(readBytesFromArray(dataPage.getByteArray(), 0, 4));

			for (int slotId = 0; slotId < numRecords; slotId++) {
				GenericRecord rec = (GenericRecord) gp.getRecord(slotId);
				byte[] titleBytes = rec.getFieldBytes("title");
				K key = new K(titleBytes);
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

		// Phase 3: spot-check search — "carmencita" is the first title in the dataset.
		K searchKey = new K(toFixedBytes("carmencita", 30));
		java.util.Iterator<RecordId> it = btree.search(searchKey);
		assertTrue(it.hasNext(), "search for \"carmencita\" should return at least one result");
	}
}
