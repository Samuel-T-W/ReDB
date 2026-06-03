package btree;

import static testutil.TestUtils.*;

import buffer.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import storage.*;

/**
 * Performance test comparing two range-query methods on the Movies table:
 *
 * <p>
 * Method 1 – Table Scan: iterate every page/slot, filter records by movieID
 * range. Method 2 – Index Scan: use the B+ tree movieID index to get RIDs, then
 * fetch the matching rows from the data pages.
 *
 * <p>
 * Results are written to movieID_range_query_results.csv. Run plot_results.py
 * to produce the two figures.
 */
public class PinnedMovieIDRangeQueryPerformanceTest {

	// -----------------------------------------------------------------------
	// Configuration
	// -----------------------------------------------------------------------
	private static final String MOVIES_TSV = "data/title.csv";
	private static final String MOVIES_DB = "movies_perf.db";
	private static final String MOVIES_IDX = "movies_movieID_perf.idx";
	private static final String RESULTS_CSV = "report/pinned_movieID_range_query_results.csv";

	private static final int MOVIE_ID_LEN = 9; // also used as B+ tree KEY_SIZE
	private static final int TITLE_LEN = 30;
	private static final int BTREE_DEGREE = 50;
	private static final int BUFFER_SIZE = 100;
	private static final int MEASURE_REPS = 10;

	private static final Map<String, Integer> MOVIE_SCHEMA = new LinkedHashMap<>();

	static {
		MOVIE_SCHEMA.put("movieId", MOVIE_ID_LEN);
		MOVIE_SCHEMA.put("title", TITLE_LEN);
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	// -----------------------------------------------------------------------
	// Method 1: Table Scan
	// -----------------------------------------------------------------------

	/**
	 * Scans every slot on every data page and counts records whose movieID falls in
	 * [startID, endID] (byte-level comparison, same as the B+ tree).
	 *
	 * @return number of matching records
	 */
	private long tableScan(byte[] startBytes, byte[] endBytes, int numDataPages, int[] recordsPerPage, BufferManager bm)
			throws IOException {
		long count = 0;
		for (int pid = 0; pid < numDataPages; pid++) {
			Page page = bm.getPage(MOVIES_DB, pid);
			GenericPage gp = new GenericPage(page, MOVIE_SCHEMA);
			int numRec = recordsPerPage[pid];
			for (int slot = 0; slot < numRec; slot++) {
				GenericRecord rec = (GenericRecord) gp.getRecord(slot);
				byte[] movieIDBytes = rec.getFieldBytes("movieId");
				if (Arrays.compare(movieIDBytes, startBytes) >= 0 && Arrays.compare(movieIDBytes, endBytes) <= 0) {
					count++;
				}
			}
			bm.unpinPage(MOVIES_DB, pid);
		}
		return count;
	}

	// -----------------------------------------------------------------------
	// Method 2: Index Scan
	// -----------------------------------------------------------------------

	/**
	 * Uses the B+ tree movieID index to obtain RIDs for the range, then fetches
	 * each matching record from the data pages.
	 *
	 * @return number of matching records
	 */
	private long indexScan(String startID, String endID, BTreeManager btree, BufferManager bm) throws IOException {
		Iterator<RecordId> rids = btree.rangeSearch(fixedAsciiKey(startID, MOVIE_ID_LEN), fixedAsciiKey(endID, MOVIE_ID_LEN));
		long count = 0;
		while (rids.hasNext()) {
			RecordId rid = rids.next();
			Page page = bm.getPage(MOVIES_DB, rid.pageId());
			GenericPage gp = new GenericPage(page, MOVIE_SCHEMA);
			@SuppressWarnings("unused")
			GenericRecord rec = (GenericRecord) gp.getRecord(rid.slotId());
			count++;
			bm.unpinPage(MOVIES_DB, rid.pageId());
		}
		return count;
	}

	// -----------------------------------------------------------------------
	// Method 3: Pin First Level
	// -----------------------------------------------------------------------

	public void pinFirstLevels(int rootId, BufferManager bm) throws IOException {
		// Pin the page via BufferManager
		Page root = bm.getPage(MOVIES_IDX, rootId);
		byte[] data = root.getByteArray();
		boolean isLeaf = ByteBuffer.wrap(data).getInt(0) == 1; // OFFSET_IS_LEAF
		InternalPage page = new InternalPage(data, TITLE_LEN);
		for (int i = 0; i <= page.getSize(); i++) {
			int childId = page.getChildId(i);
			Page childPage = bm.getPage(MOVIES_IDX, childId);
			data = childPage.getByteArray();
			isLeaf = ByteBuffer.wrap(data).getInt(0) == 1; // OFFSET_IS_LEAF
			if (isLeaf) {
				bm.unpinPage(MOVIES_IDX, childId);
			}
			// Not unpin so the internal pages got pin
		}
	}

	// -----------------------------------------------------------------------
	// Main performance test
	// -----------------------------------------------------------------------

	@Test
	public void testRangeQueryPerformance() throws Exception {

		// --- 1. Create fresh DB files ---
		for (String fname : new String[]{MOVIES_DB, MOVIES_IDX}) {
			File f = new File(fname);
			f.delete();
			f.createNewFile();
		}

		BufferManager bm = new BufferManager(BUFFER_SIZE);
		bm.register(new TableEntry(MOVIES_DB, MOVIE_SCHEMA));
		bm.register(new IndexEntry(MOVIES_IDX, MOVIE_ID_LEN));

		// --- 2. Load movies into data pages ---
		List<String> allMovieID = new ArrayList<>();
		List<RecordId> allRids = new ArrayList<>();

		// Dynamic list because page count is not known upfront
		List<Integer> recPerPageList = new ArrayList<>();

		Page curPage = bm.createPage(MOVIES_DB, null);
		GenericPage curGP = new GenericPage(curPage, MOVIE_SCHEMA);
		int curCount = 0; // records inserted on the current page
		int numDataPages = 1;

		try (BufferedReader br = new BufferedReader(new FileReader(MOVIES_TSV))) {
			String line;
			boolean header = true;
			while ((line = br.readLine()) != null) {
				if (header) {
					header = false;
					continue;
				}
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
				allMovieID.add(movieId);
				curCount++;
			}
		}
		// Seal the last page
		recPerPageList.add(curCount);
		bm.markDirty(MOVIES_DB, curPage.getPid());
		bm.unpinPage(MOVIES_DB, curPage.getPid());

		int[] recordsPerPage = recPerPageList.stream().mapToInt(i -> i).toArray();
		int totalMovies = allMovieID.size();
		System.out.printf("Loaded %d movies into %d data page(s).%n", totalMovies, numDataPages);

		// --- 3. Build B+ tree movieID index ---
		BTreeManager btree = new BTreeManager(BTREE_DEGREE, MOVIES_IDX, bm, MOVIE_ID_LEN);
		for (int i = 0; i < totalMovies; i++) {
			btree.insert(fixedAsciiKey(allMovieID.get(i), MOVIE_ID_LEN), allRids.get(i));
		}

		// Flush everything to disk so the buffer starts clean before timing
		bm.force();
		System.out.println("Index built and flushed to disk.");

		// --- 4. Compute range boundaries for each selectivity level ---
		// Sort movieID in the same byte order the B+ tree uses
		List<String> sortedMovieID = new ArrayList<>(allMovieID);
		Collections.sort(sortedMovieID);
		int n = sortedMovieID.size();

		// Fixed start: the lexicographically smallest movieId
		String startID = sortedMovieID.get(0);
		byte[] startBytes = toFixedBytes(startID, MOVIE_ID_LEN);

		double[] selectivities = {0.001, 0.005, 0.01, 0.05, 0.1, 0.2, 0.5};

		// Pre-compute end movieID for each selectivity
		String[] endMovieID = new String[selectivities.length];
		byte[][] endBytesArr = new byte[selectivities.length][];
		for (int s = 0; s < selectivities.length; s++) {
			int endIdx = Math.min((int) Math.ceil(selectivities[s] * n) - 1, n - 1);
			endMovieID[s] = sortedMovieID.get(endIdx);
			endBytesArr[s] = toFixedBytes(endMovieID[s], TITLE_LEN);
		}

		// --- 5. Measure ---
		// Pin first levels
		pinFirstLevels(btree.getRoot(), bm);

		System.out.printf("%n%-12s %20s %20s %10s %10s%n", "Selectivity", "TableScan(avg ns)", "IndexScan(avg ns)",
				"Count1", "Count2");
		System.out.println("-".repeat(78));

		List<String> csvLines = new ArrayList<>();
		csvLines.add("selectivity,table_scan_ns,index_scan_ns,count_table,count_index");

		for (int s = 0; s < selectivities.length; s++) {
			double sel = selectivities[s];
			byte[] endBytes = endBytesArr[s];
			String endID = endMovieID[s];

			long totalT1 = 0, totalT2 = 0;
			long count1 = 0, count2 = 0;

			for (int r = 0; r < MEASURE_REPS; r++) {
				System.out.println(r);
				long t0 = System.nanoTime();
				count1 = tableScan(startBytes, endBytes, numDataPages, recordsPerPage, bm);
				totalT1 += System.nanoTime() - t0;

				t0 = System.nanoTime();
				count2 = indexScan(startID, endID, btree, bm);
				totalT2 += System.nanoTime() - t0;
			}

			long avgT1 = totalT1 / MEASURE_REPS;
			long avgT2 = totalT2 / MEASURE_REPS;

			System.out.printf("%-12.3f %20d %20d %10d %10d%n", sel, avgT1, avgT2, count1, count2);
			csvLines.add(String.format(Locale.US, "%.3f,%d,%d,%d,%d", sel, avgT1, avgT2, count1, count2));
		}

		// --- 6. Write CSV ---
		try (PrintWriter pw = new PrintWriter(new FileWriter(RESULTS_CSV))) {
			for (String line : csvLines)
				pw.println(line);
		}
		System.out.println("\nResults written to " + RESULTS_CSV);
		System.out.println("Run: python3 movieID_plot_results.py");

		// --- 7. Cleanup temporary DB files ---
		new File(MOVIES_DB).delete();
		new File(MOVIES_IDX).delete();
	}
}
