package btree;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import buffer.*;
import catalog.IndexEntry;
import catalog.TableEntry;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import storage.*;

/**
 * End-to-end tests for the BTreeManager.
 *
 * <p>
 * Test 1 verifies that building a title index over the Movies table completes
 * without error.
 *
 * <p>
 * Tests 2 and 3 verify that a single-key search on the title index and the
 * movieId index respectively returns the same records as an equivalent SQL
 * query executed against an H2 in-memory database loaded with the same data.
 */
public class BTreeValidationRangeSearchTest {

	// -----------------------------------------------------------------------
	// Configuration
	// -----------------------------------------------------------------------

	private static final String MOVIES_TSV = "data/title.csv";
	private static final String MOVIES_DB = "movies.db";
	private static final String TITLE_INDEX = "movies_title.idx";
	private static final String MOVIEID_INDEX = "movies_movieid.idx";

	/** Buffer large enough for data pages and B-tree pages simultaneously. */
	private static final int BUFFER_SIZE = 20;

	private static final int MAX_TEST_ROWS = 4000;

	/**
	 * B+ tree degree: each internal node holds up to BTREE_DEGREE children, each
	 * leaf holds up to BTREE_DEGREE-1 key/RID pairs.
	 */
	private static final int BTREE_DEGREE = 50;

	/** Stored key size for the title index: full title field width. */
	private static final int TITLE_KEY_SIZE = 30;

	/** Stored key size for the movieId index: full movieId field width. */
	private static final int MOVIEID_KEY_SIZE = 9;

	// Range-search bounds -------------------------------------------------------
	// Title range: all titles with first char in ['b'..'c'] (case-sensitive ASCII).
	// Using single-char bounds ensures consistent behaviour between the B+ tree's
	// 30-byte byte-comparison and H2's VARCHAR comparison: for any real title T,
	// "b\0...\0" < T (if T starts with 'b'/'c') < "d\0...\0" iff 'b' ≤ T[0] ≤ 'c',
	// which mirrors H2's WHERE title >= 'b' AND title <= 'd'.
	private static final String RANGE_TITLE_START = "b";
	private static final String RANGE_TITLE_END = "d";
	// MovieId range covers the first cluster of IDs in the dataset.
	private static final String RANGE_MOVIEID_START = "tt0000001";
	private static final String RANGE_MOVIEID_END = "tt0000060";

	private static final Map<String, Integer> MOVIE_SCHEMA = new LinkedHashMap<>();

	static {
		MOVIE_SCHEMA.put("movieId", 9);
		MOVIE_SCHEMA.put("title", 30);
	}

	private BufferManager bm;

	// -----------------------------------------------------------------------
	// Helper utilities
	// -----------------------------------------------------------------------

	/**
	 * Reads every data page in movies.db and loads all rows from the CSV into it
	 * via the BufferManager. Returns the total number of data pages allocated.
	 */
	private int loadMoviesTable() throws Exception {
		int numDataPages = 0;
		Page currentPage = bm.createPage(MOVIES_DB, null);
		numDataPages++;
		GenericPage genericPage = new GenericPage(currentPage, MOVIE_SCHEMA);

		int count = 0;
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

				String[] parts = line.split(",");
				String movieId = parts[0];
				String title = parts[1];

				GenericRecord record = makeMovieRecord(MOVIE_SCHEMA, movieId, title);

				int slotId = genericPage.insertRecord(record);
				if (slotId != -1) {
					bm.markDirty(MOVIES_DB, currentPage.getPid());
				} else {
					// Page is full: flush, allocate a new one, and retry.
					bm.unpinPage(MOVIES_DB, currentPage.getPid());
					currentPage = bm.createPage(MOVIES_DB, null);
					numDataPages++;
					genericPage = new GenericPage(currentPage, MOVIE_SCHEMA);
					genericPage.insertRecord(record);
					bm.markDirty(MOVIES_DB, currentPage.getPid());
				}
			}
		}
		bm.unpinPage(MOVIES_DB, currentPage.getPid());
		bm.force();
		return numDataPages;
	}

	/**
	 * Loads the same {@value #MAX_TEST_ROWS} rows from the CSV into an H2 in-memory
	 * database. The caller is responsible for closing the connection.
	 *
	 * <p>
	 * Titles and movieIds are stored as plain (non-padded) strings so that SQL
	 * equality predicates work naturally.
	 */
	private Connection loadMoviesIntoH2() throws Exception {
		Connection conn = DriverManager.getConnection("jdbc:h2:mem:movies_test;DB_CLOSE_DELAY=-1");
		conn.createStatement()
				.execute("CREATE TABLE IF NOT EXISTS movies " + "(movieId VARCHAR(9), title VARCHAR(30))");

		PreparedStatement ps = conn.prepareStatement("INSERT INTO movies (movieId, title) VALUES (?, ?)");
		int count = 0;
		String line;
		try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
			while ((line = br.readLine()) != null) {
				if (count == 0) {
					count = 1;
					continue;
				}
				if (count > MAX_TEST_ROWS)
					break;
				count++;
				String[] parts = line.split(",");
				ps.setString(1, parts[0]);
				ps.setString(2, parts[1]);
				ps.addBatch();
			}
		}
		ps.executeBatch();
		ps.close();
		return conn;
	}

	/**
	 * Given an iterator of RIDs returned by a B+ tree search, fetches the
	 * corresponding records from movies.db and returns them as a set of
	 * {@code "movieId|title"} strings (with zero-padding stripped).
	 */
	private Set<String> fetchRecordsFromRids(Iterator<RecordId> rids) throws Exception {
		Set<String> results = new HashSet<>();
		while (rids.hasNext()) {
			RecordId rid = rids.next();
			Page page = bm.getPage(MOVIES_DB, rid.pageId());
			GenericPage gp = new GenericPage(page, MOVIE_SCHEMA);
			GenericRecord rec = (GenericRecord) gp.getRecord(rid.slotId());
			String movieId = fromFixedBytes(rec.getFieldBytes("movieId"));
			String title = fromFixedBytes(rec.getFieldBytes("title"));
			results.add(movieId + "|" + title);
			bm.unpinPage(MOVIES_DB, rid.pageId());
		}
		return results;
	}

	// -----------------------------------------------------------------------
	// Lifecycle
	// -----------------------------------------------------------------------

	@BeforeEach
	void setup() throws Exception {
		bm = new BufferManager(BUFFER_SIZE);

		// Create fresh, empty files for the table and both indexes.
		for (String name : new String[]{MOVIES_DB, TITLE_INDEX, MOVIEID_INDEX}) {
			File f = new File(name);
			f.delete();
			f.createNewFile();
		}

		bm.register(new TableEntry(MOVIES_DB, MOVIE_SCHEMA));
		bm.register(new IndexEntry(TITLE_INDEX, TITLE_KEY_SIZE));
		bm.register(new IndexEntry(MOVIEID_INDEX, MOVIEID_KEY_SIZE));
	}

	@AfterEach
	void cleanup() {
		for (String name : new String[]{MOVIES_DB, TITLE_INDEX, MOVIEID_INDEX}) {
			new File(name).delete();
		}
	}

	// -----------------------------------------------------------------------
	// Tests
	// -----------------------------------------------------------------------

	/**
	 * Test 4 – Range search on the title index, verified against H2.
	 *
	 * <p>
	 * Searches for all titles whose 30-byte key falls in the inclusive range
	 * [{@value #RANGE_TITLE_START}, {@value #RANGE_TITLE_END}] (zero-padded to
	 * {@value #TITLE_KEY_SIZE} bytes). This selects every title whose first
	 * character is {@code 'b'} or {@code 'c'}, because:
	 *
	 * <ul>
	 * <li>Any title starting with {@code 'b'} or {@code 'c'} is byte-greater than
	 * {@code "b\0…\0"} and byte-less than {@code "d\0…\0"}.
	 * <li>A title starting with {@code 'd'} (e.g. "dancing darkies") is
	 * byte-greater than {@code
	 *       "d\0…\0"} because {@code 'a' > '\0'}, so it is excluded – consistent
	 * with H2's {@code
	 *       WHERE title <= 'd'}.
	 * </ul>
	 *
	 * <p>
	 * The equivalent H2 predicate {@code WHERE title >= 'b' AND title <= 'd'}
	 * produces the same result set because ASCII byte order and SQL VARCHAR order
	 * agree for lowercase Latin strings, and a zero-padded key {@code "b\0…\0"}
	 * compares identically to the plain string {@code "b"} for any title that
	 * shares the first character with the bound.
	 */
	@Test
	public void testRangeSearchByTitleIndex() throws Exception {
		// ---- Phase 1: load the Movies table ----
		int numDataPages = loadMoviesTable();

		// ---- Phase 2: build the title index ----
		BTreeManager btree = new BTreeManager(BTREE_DEGREE, TITLE_INDEX, bm, TITLE_KEY_SIZE);

		for (int pid = 0; pid < numDataPages; pid++) {
			Page dataPage = bm.getPage(MOVIES_DB, pid);
			GenericPage gp = new GenericPage(dataPage, MOVIE_SCHEMA);
			int numRecords = fromByteArray(readBytesFromArray(dataPage.getByteArray(), 0, 4));

			for (int slotId = 0; slotId < numRecords; slotId++) {
				GenericRecord rec = (GenericRecord) gp.getRecord(slotId);
				btree.insert(new K(rec.getFieldBytes("title")), new RecordId(pid, slotId));
			}
			bm.unpinPage(MOVIES_DB, pid);
		}

		// ---- Phase 3: B+ tree range search ----
		K startKey = new K(toFixedBytes(RANGE_TITLE_START, TITLE_KEY_SIZE));
		K endKey = new K(toFixedBytes(RANGE_TITLE_END, TITLE_KEY_SIZE));
		Iterator<RecordId> rids = btree.rangeSearch(startKey, endKey);
		Set<String> btreeResults = fetchRecordsFromRids(rids);

		// ---- Phase 4: equivalent SQL range query via H2 ----
		Set<String> h2Results = new HashSet<>();
		try (Connection conn = loadMoviesIntoH2()) {
			PreparedStatement ps = conn
					.prepareStatement("SELECT movieId, title FROM movies WHERE title >= ? AND title <= ?");
			ps.setString(1, RANGE_TITLE_START);
			ps.setString(2, RANGE_TITLE_END);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				h2Results.add(rs.getString("movieId") + "|" + rs.getString("title"));
			}
		}

		// ---- Phase 5: assertions ----
		assertFalse(h2Results.isEmpty(),
				"H2 must find at least one title in range ['" + RANGE_TITLE_START + "', '" + RANGE_TITLE_END + "']");
		assertEquals(h2Results, btreeResults, "B+ tree title range-search must return exactly the same records as H2");
	}

	/**
	 * Test 5 – Range search on the movieId index, verified against H2.
	 *
	 * <p>
	 * Searches for all movieIds in the inclusive range
	 * [{@value #RANGE_MOVIEID_START}, {@value #RANGE_MOVIEID_END}]. All movieIds
	 * share the fixed-length format {@code ttNNNNNNN} (9 ASCII bytes), so
	 * lexicographic byte order equals numeric order and the B+ tree's 9-byte
	 * comparison agrees exactly with H2's VARCHAR comparison.
	 */
	@Test
	public void testRangeSearchByMovieIdIndex() throws Exception {
		// ---- Phase 1: load the Movies table ----
		int numDataPages = loadMoviesTable();

		// ---- Phase 2: build the movieId index ----
		BTreeManager btree = new BTreeManager(BTREE_DEGREE, MOVIEID_INDEX, bm, MOVIEID_KEY_SIZE);

		for (int pid = 0; pid < numDataPages; pid++) {
			Page dataPage = bm.getPage(MOVIES_DB, pid);
			GenericPage gp = new GenericPage(dataPage, MOVIE_SCHEMA);
			int numRecords = fromByteArray(readBytesFromArray(dataPage.getByteArray(), 0, 4));

			for (int slotId = 0; slotId < numRecords; slotId++) {
				GenericRecord rec = (GenericRecord) gp.getRecord(slotId);
				btree.insert(new K(rec.getFieldBytes("movieId")), new RecordId(pid, slotId));
			}
			bm.unpinPage(MOVIES_DB, pid);
		}

		// ---- Phase 3: B+ tree range search ----
		K startKey = new K(toFixedBytes(RANGE_MOVIEID_START, MOVIEID_KEY_SIZE));
		K endKey = new K(toFixedBytes(RANGE_MOVIEID_END, MOVIEID_KEY_SIZE));
		Iterator<RecordId> rids = btree.rangeSearch(startKey, endKey);
		Set<String> btreeResults = fetchRecordsFromRids(rids);

		// ---- Phase 4: equivalent SQL range query via H2 ----
		Set<String> h2Results = new HashSet<>();
		try (Connection conn = loadMoviesIntoH2()) {
			PreparedStatement ps = conn
					.prepareStatement("SELECT movieId, title FROM movies WHERE movieId >= ? AND movieId <= ?");
			ps.setString(1, RANGE_MOVIEID_START);
			ps.setString(2, RANGE_MOVIEID_END);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				h2Results.add(rs.getString("movieId") + "|" + rs.getString("title"));
			}
		}

		// ---- Phase 5: assertions ----
		assertFalse(h2Results.isEmpty(), "H2 must find at least one movieId in range ['" + RANGE_MOVIEID_START + "', '"
				+ RANGE_MOVIEID_END + "']");
		assertEquals(h2Results, btreeResults,
				"B+ tree movieId range-search must return exactly the same records as H2");
	}
}
