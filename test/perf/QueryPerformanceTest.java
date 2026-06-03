package perf;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import buffer.BufferManager;
import buffer.IndexEntry;
import buffer.TableEntry;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import operators.Join;
import operators.Project;
import operators.Scan;
import operators.Selection;
import org.junit.jupiter.api.Test;
import storage.GenericPage;
import storage.GenericRecord;
import storage.Page;
import storage.RawPage;
import util.RecordUtils;

/**
 * Measures actual I/O operations for the run_query plan and compares them
 * against the analytical I/O formula derived from the BNL join cost model.
 *
 * <p>Precondition: {@code pre_process} must have been run so that movies.db,
 * workedon.db, and people.db exist.
 *
 * <p>Results are written to report/query_io_results.csv. Run
 * report/io_plot_results.py to produce the comparison figure.
 *
 * <p>Analytical formula (one director per movie approximation):
 * <pre>
 *   I(s_M) = P_W + P_W_dir + P_M
 *           + ceil(s_M * T_M / (N * rpp_M))  * P_W_dir
 *           + ceil(s_M * T_M / (N * rpp_J1)) * P_P
 * </pre>
 * where:
 *   P_M    = Movies page count, P_W = WorkedOn page count, P_P = People page count
 *   P_W_dir = materialized WorkedOn-projection (director only) page count
 *   T_M    = total movie records, s_M = actual query selectivity
 *   N      = (B-1)/2 (BNL block size)
 *   rpp_M  = 104 (records/page for 39-byte Movies records)
 *   rpp_J1 = 83  (records/page for 49-byte Join1-output records)
 */
public class QueryPerformanceTest {

    // ── paths ────────────────────────────────────────────────────────────────
    private static final String MOVIES_DB    = "movies.db";
    private static final String WORKEDON_DB  = "workedon.db";
    private static final String PEOPLE_DB    = "people.db";
    private static final String TITLE_IDX    = "title.idx";
    private static final String PERF_TMP_DB  = "perf_workedon_proj_tmp.db";
    private static final String RESULTS_CSV  = "report/query_io_results.csv";

    // ── schemas ──────────────────────────────────────────────────────────────
    private static final Map<String, Integer> MOVIES_SCHEMA   = new LinkedHashMap<>();
    private static final Map<String, Integer> WORKEDON_SCHEMA = new LinkedHashMap<>();
    private static final Map<String, Integer> PEOPLE_SCHEMA   = new LinkedHashMap<>();
    private static final Map<String, Integer> WK_PROJ_SCHEMA  = new LinkedHashMap<>();
    private static final Map<String, Integer> J1_SCHEMA       = new LinkedHashMap<>();
    private static final Map<String, Integer> J2_SCHEMA       = new LinkedHashMap<>();
    private static final Map<String, Integer> OUT_SCHEMA      = new LinkedHashMap<>();

    static {
        MOVIES_SCHEMA.put("movieId", 9);
        MOVIES_SCHEMA.put("title",  30);

        WORKEDON_SCHEMA.put("movieId",  9);
        WORKEDON_SCHEMA.put("personId", 10);
        WORKEDON_SCHEMA.put("category", 20);

        PEOPLE_SCHEMA.put("personId", 10);
        PEOPLE_SCHEMA.put("name",    105);

        WK_PROJ_SCHEMA.put("movieId",  9);
        WK_PROJ_SCHEMA.put("personId", 10);

        J1_SCHEMA.put("movieId",  9);
        J1_SCHEMA.put("title",   30);
        J1_SCHEMA.put("personId", 10);

        J2_SCHEMA.put("movieId",  9);
        J2_SCHEMA.put("title",   30);
        J2_SCHEMA.put("personId", 10);
        J2_SCHEMA.put("name",   105);

        OUT_SCHEMA.put("title", 30);
        OUT_SCHEMA.put("name", 105);
    }

    // ── constants for analytical formula ─────────────────────────────────────
    private static final int PAGE_SIZE  = RawPage.MAX_PAGE_LEN; // 4096
    private static final int HEADER     = 4;                    // record-count header bytes
    private static final int REC_MOVIES  = 9 + 30;             // 39 bytes
    private static final int REC_J1      = 9 + 30 + 10;        // 49 bytes
    private static final int RPP_M  = (PAGE_SIZE - HEADER) / REC_MOVIES; // 104
    private static final int RPP_J1 = (PAGE_SIZE - HEADER) / REC_J1;     // 83

    // ── test ─────────────────────────────────────────────────────────────────

    @Test
    public void testIOCostVsAnalytical() throws Exception {
        assumeTrue(new File(MOVIES_DB).exists(),
                "Skipped: run pre_process first to create " + MOVIES_DB);
        assumeTrue(new File(WORKEDON_DB).exists(),
                "Skipped: run pre_process first to create " + WORKEDON_DB);
        assumeTrue(new File(PEOPLE_DB).exists(),
                "Skipped: run pre_process first to create " + PEOPLE_DB);

        // ── page counts from file sizes ───────────────────────────────────
        long P_M = pageCount(MOVIES_DB);
        long P_W = pageCount(WORKEDON_DB);
        long P_P = pageCount(PEOPLE_DB);
        System.out.printf("P_M=%d  P_W=%d  P_P=%d%n", P_M, P_W, P_P);

        // ── load all titles from movies.db to build selectivity ranges ───
        List<String> allTitles = loadAllTitles();
        List<String> sortedTitles = new ArrayList<>(allTitles);
        Collections.sort(sortedTitles);
        int T_M = sortedTitles.size();
        String startTitle = sortedTitles.get(0);
        System.out.printf("T_M=%d  startTitle=\"%s\"%n", T_M, startTitle);

        // ── dry-run: measure P_W_dir (write I/Os when materialising WorkedOn) ──
        long P_W_dir = measurePWdir(30);
        System.out.printf("P_W_dir=%d%n", P_W_dir);

        // ── test matrix ─────────────────────────────────────────────────
        int[]    bufferSizes  = {20, 50, 100};
        double[] selectivities = {0.001, 0.005, 0.01, 0.05, 0.1, 0.2};

        List<String> csvLines = new ArrayList<>();
        csvLines.add("selectivity,buffer_size,measured_io,analytical_io,result_count");

        System.out.printf("%n%-12s %6s %14s %14s %10s%n",
                "Selectivity", "BufSz", "Measured_IO", "Analytical_IO", "Results");
        System.out.println("-".repeat(62));

        for (int bufSize : bufferSizes) {
            int N = (bufSize - 1) / 2;
            if (N < 1) continue;

            for (double sel : selectivities) {
                int endIdx     = Math.min((int) Math.ceil(sel * T_M) - 1, T_M - 1);
                String endTitle = sortedTitles.get(endIdx);

                // ── build operator tree with fresh BM ─────────────────────
                BufferManager bm = buildBM(bufSize);
                bm.resetIOCounts();

                Project finalProj = buildOperatorTree(bm, startTitle, endTitle, N);

                // ── execute ───────────────────────────────────────────────
                finalProj.open();
                long resultCount = 0;
                while (finalProj.next() != null) resultCount++;
                finalProj.close();

                long measuredIO = bm.getTotalIOCount();

                // cleanup temp file
                new File(PERF_TMP_DB).delete();

                // ── analytical estimate ───────────────────────────────────
                long selectedMovies = resultCount; // ≈ s_M * T_M (one director/movie)
                long blocksJ1 = ceil(selectedMovies, (long) N * RPP_M);
                long blocksJ2 = ceil(selectedMovies, (long) N * RPP_J1);
                long analyticalIO = P_W + P_W_dir + P_M
                        + blocksJ1 * P_W_dir
                        + blocksJ2 * P_P;

                double actualSel = (double) resultCount / T_M;

                System.out.printf("%-12.4f %6d %14d %14d %10d%n",
                        actualSel, bufSize, measuredIO, analyticalIO, resultCount);

                csvLines.add(String.format(Locale.US, "%.5f,%d,%d,%d,%d",
                        actualSel, bufSize, measuredIO, analyticalIO, resultCount));
            }
        }

        // ── write CSV ────────────────────────────────────────────────────
        try (PrintWriter pw = new PrintWriter(new FileWriter(RESULTS_CSV))) {
            for (String line : csvLines) pw.println(line);
        }
        System.out.println("\nResults written to " + RESULTS_CSV);
        System.out.println("Run: python3 report/io_plot_results.py");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private BufferManager buildBM(int bufSize) throws IOException {
        BufferManager bm = new BufferManager(bufSize);
        bm.register(new TableEntry(MOVIES_DB,   MOVIES_SCHEMA));
        bm.register(new TableEntry(WORKEDON_DB, WORKEDON_SCHEMA));
        bm.register(new TableEntry(PEOPLE_DB,   PEOPLE_SCHEMA));
        bm.register(new IndexEntry(TITLE_IDX,   MOVIES_SCHEMA.get("title")));
        bm.register(new TableEntry(PERF_TMP_DB, WK_PROJ_SCHEMA));
        return bm;
    }

    private Project buildOperatorTree(BufferManager bm,
                                      String startTitle,
                                      String endTitle,
                                      int N) throws IOException {
        // Delete stale temp file so materialize starts fresh
        new File(PERF_TMP_DB).delete();

        byte[] startBytes = RecordUtils.toFixedBytes(startTitle, 30);
        byte[] endBytes   = RecordUtils.toFixedBytes(endTitle,   30);
        byte[] dirBytes   = RecordUtils.toFixedBytes("director", 20);

        Scan      movieScan    = new Scan(bm, MOVIES_DB,    MOVIES_SCHEMA);
        Scan      workedonScan = new Scan(bm, WORKEDON_DB,  WORKEDON_SCHEMA);
        Scan      peopleScan   = new Scan(bm, PEOPLE_DB,    PEOPLE_SCHEMA);

        Selection movieSel = new Selection(movieScan, rec -> {
            byte[] t = rec.getFieldBytes("title");
            return Arrays.compare(t, startBytes) >= 0
                && Arrays.compare(t, endBytes)   <= 0;
        });

        Selection wkSel = new Selection(workedonScan,
                rec -> Arrays.equals(rec.getFieldBytes("category"), dirBytes));

        Project wkProj = new Project(wkSel, WK_PROJ_SCHEMA, bm, PERF_TMP_DB);

        Join join1 = new Join(movieSel, wkProj,
                "movieId", "movieId",
                MOVIES_SCHEMA, WK_PROJ_SCHEMA, J1_SCHEMA,
                bm, "__perf_bnl_0__", N);

        Join join2 = new Join(join1, peopleScan,
                "personId", "personId",
                J1_SCHEMA, PEOPLE_SCHEMA, J2_SCHEMA,
                bm, "__perf_bnl_1__", N);

        return new Project(join2, OUT_SCHEMA);
    }

    /** Materialises only the WorkedOn projection with bufSize=30 to measure P_W_dir. */
    private long measurePWdir(int bufSize) throws IOException {
        new File(PERF_TMP_DB).delete();
        BufferManager bm = buildBM(bufSize);
        bm.resetIOCounts();

        byte[] dirBytes = RecordUtils.toFixedBytes("director", 20);
        Scan workedonScan = new Scan(bm, WORKEDON_DB, WORKEDON_SCHEMA);
        Selection wkSel   = new Selection(workedonScan,
                rec -> Arrays.equals(rec.getFieldBytes("category"), dirBytes));
        Project wkProj = new Project(wkSel, WK_PROJ_SCHEMA, bm, PERF_TMP_DB);

        wkProj.open();
        wkProj.close();

        long writeIOs = bm.getWriteIOCount();
        new File(PERF_TMP_DB).delete();
        return writeIOs;
    }

    /** Load all movie titles from movies.db (scans the raw binary file). */
    private List<String> loadAllTitles() throws IOException {
        List<String> titles = new ArrayList<>();
        BufferManager bm = new BufferManager(10);
        bm.register(new TableEntry(MOVIES_DB, MOVIES_SCHEMA));
        int pages = (int) pageCount(MOVIES_DB);
        for (int pid = 0; pid < pages; pid++) {
            Page page = bm.getPage(MOVIES_DB, pid);
            byte[] raw = page.getByteArray();
            int numRec = ByteBuffer.wrap(raw, 0, 4).getInt();
            GenericPage gp = new GenericPage(page, MOVIES_SCHEMA);
            for (int slot = 0; slot < numRec; slot++) {
                GenericRecord rec = (GenericRecord) gp.getRecord(slot);
                titles.add(new String(rec.getFieldBytes("title")).trim());
            }
            bm.unpinPage(MOVIES_DB, pid);
        }
        return titles;
    }

    private static long pageCount(String fileId) {
        return new File(fileId).length() / PAGE_SIZE;
    }

    /** Integer ceiling division: ceil(a / b). Returns 0 when a == 0. */
    private static long ceil(long a, long b) {
        return a == 0 ? 0 : (a + b - 1) / b;
    }
}