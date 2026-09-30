import buffer.BufferManager;
import catalog.IndexEntry;
import catalog.TableEntry;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import operators.IndexScan;
import operators.Join;
import operators.Operator;
import operators.Project;
import operators.Scan;
import operators.Selection;
import storage.BTreeManager;
import storage.GenericRecord;
import storage.K;
import util.RecordUtils;

public class RunQuery {

    static final String MOVIES_DB   = "movies.db";
    static final String WORKEDON_DB = "workedon.db";
    static final String PEOPLE_DB   = "people.db";
    static final String TITLE_IDX   = "title.idx";
    static final String QUERY_RESULTS = "query_results.csv";

    static final Map<String, Integer> MOVIES_SCHEMA;
    static final Map<String, Integer> WORKEDON_SCHEMA;
    static final Map<String, Integer> PEOPLE_SCHEMA;

    // Must stay identical to the widths PreProcessor wrote the heap files with;
    // a mismatch silently misreads every record.
    static {
        MOVIES_SCHEMA = Collections.unmodifiableMap(new LinkedHashMap<>(PreProcessor.MOVIES_SCHEMA));
        WORKEDON_SCHEMA = Collections.unmodifiableMap(new LinkedHashMap<>(PreProcessor.WORKEDON_SCHEMA));
        PEOPLE_SCHEMA = Collections.unmodifiableMap(new LinkedHashMap<>(PreProcessor.PEOPLE_SCHEMA));
    }

    private static final int MOVIE_ID_LEN = MOVIES_SCHEMA.get("movieId");
    private static final int TITLE_LEN = MOVIES_SCHEMA.get("title");
    private static final int PERSON_ID_LEN = PEOPLE_SCHEMA.get("personId");
    private static final int NAME_LEN = PEOPLE_SCHEMA.get("name");

    static final int BTREE_DEGREE = BTreeManager.maxDegree(TITLE_LEN);

    /** One transient page for the scan/index operation this query is advancing. */
    static final int WORKING_FRAMES = 1;

    /** Two one-page BNL blocks plus this query's working page. */
    static final int MIN_FRAME_BUDGET = 2 + WORKING_FRAMES;

    static int blockPagesPerJoin(int frameBudget) {
        int blockPages = (frameBudget - WORKING_FRAMES) / 2;
        if (blockPages < 1) {
            throw new IllegalArgumentException(
                    "frame budget must be at least " + MIN_FRAME_BUDGET + " to run BNL join");
        }
        return blockPages;
    }

    public static long run(
            String startRange,
            String endRange,
            int bufferSize,
            boolean useIndex) throws IOException {
        BufferManager bm = new BufferManager(bufferSize);
        registerCatalog(bm);
        return run(startRange, endRange, bufferSize, useIndex, bm, Path.of(QUERY_RESULTS));
    }

    /**
     * Runs the query against a caller-provided BufferManager, writing results to
     * the given path. The base tables and index must already be registered on the
     * manager (see {@link #registerCatalog}); only the per-query temp table is
     * registered here.
     *
     * @param frameBudget
     *            Frames reserved for this query's two BNL blocks and working page;
     *            it only drives the block-size computation, not pool construction
     *            (the pool size is whatever the injected manager was built with).
     */
    public static long run(
            String startRange,
            String endRange,
            int frameBudget,
            boolean useIndex,
            BufferManager bm,
            Path outputPath) throws IOException {
        // WORKING_FRAMES is per query, via QueryEngine's per-query budget.
        // Applying N = (B - 1) / 2 to the full pool would leave one leftover
        // frame shared by every concurrent BNL inner scan.
        int N = blockPagesPerJoin(frameBudget);

        QueryContext query = QueryContext.create(bm, outputPath);
        Throwable queryFailure = null;
        try {
            // ---- WorkedOn projection schema: {movieId, personId} ------------
            Map<String, Integer> wkProj_ = new LinkedHashMap<>();
            wkProj_.put("movieId",  MOVIE_ID_LEN);
            wkProj_.put("personId", PERSON_ID_LEN);
            Map<String, Integer> wkProjSchema = Collections.unmodifiableMap(wkProj_);
            String workedonTmp = query.tempFileId("workedon-proj", ".db");
            bm.register(new TableEntry(workedonTmp, wkProjSchema));

            // ---- Leaf operators ---------------------------------------------
            Scan workedonScan = new Scan(bm, WORKEDON_DB,  WORKEDON_SCHEMA);
            Scan peopleScan   = new Scan(bm, PEOPLE_DB,    PEOPLE_SCHEMA);

            // ---- Movies access: index range scan OR scan + selection --------
            byte[] startBytes = RecordUtils.toFixedBytes(startRange, TITLE_LEN);
            byte[] endBytes   = RecordUtils.toFixedBytes(endRange,   TITLE_LEN);
            Operator movieSel;
            if (useIndex) {
                BTreeManager titleIdx = BTreeManager.openExisting(
                        BTREE_DEGREE, TITLE_IDX, bm, MOVIES_SCHEMA.get("title"));
                movieSel = new IndexScan(bm, MOVIES_DB, MOVIES_SCHEMA, titleIdx,
                        new K(startBytes), new K(endBytes));
            } else {
                Scan movieScan = new Scan(bm, MOVIES_DB, MOVIES_SCHEMA);
                movieSel = new Selection(movieScan, rec -> {
                    byte[] t = rec.getFieldBytes("title");
                    return Arrays.compare(t, startBytes) >= 0
                        && Arrays.compare(t, endBytes)   <= 0;
                });
            }

            // ---- Selection on WorkedOn: category = "director" ---------------
            byte[] dirBytes = RecordUtils.toFixedBytes("director", 20);
            Selection wkSel = new Selection(workedonScan,
                    rec -> Arrays.equals(rec.getFieldBytes("category"), dirBytes));

            // ---- Materializing projection: WorkedOn → {movieId, personId} ---
            Project wkProj = new Project(wkSel, wkProjSchema, bm, workedonTmp);

            // ---- Join 1: Movies ⋈ WorkedOn on movieId -----------------------
            Map<String, Integer> j1_ = new LinkedHashMap<>();
            j1_.put("movieId",  MOVIE_ID_LEN);
            j1_.put("title",    TITLE_LEN);
            j1_.put("personId", PERSON_ID_LEN);
            Map<String, Integer> j1Schema = Collections.unmodifiableMap(j1_);

            Join join1 = new Join(
                    movieSel, wkProj,
                    "movieId", "movieId",
                    MOVIES_SCHEMA, wkProjSchema, j1Schema,
                    bm, query.scratchFileId("bnl-outer-0"), N);

            // ---- Join 2: Join1 ⋈ People on personId -------------------------
            Map<String, Integer> j2_ = new LinkedHashMap<>();
            j2_.put("movieId",  MOVIE_ID_LEN);
            j2_.put("title",    TITLE_LEN);
            j2_.put("personId", PERSON_ID_LEN);
            j2_.put("name",     NAME_LEN);
            Map<String, Integer> j2Schema = Collections.unmodifiableMap(j2_);

            Join join2 = new Join(
                    join1, peopleScan,
                    "personId", "personId",
                    j1Schema, PEOPLE_SCHEMA, j2Schema,
                    bm, query.scratchFileId("bnl-outer-1"), N);

            // ---- Final pipelined projection: → {title, name} ----------------
            Map<String, Integer> out_ = new LinkedHashMap<>();
            out_.put("title", TITLE_LEN);
            out_.put("name", NAME_LEN);
            Map<String, Integer> outSchema = Collections.unmodifiableMap(out_);

            Project finalProj = new Project(join2, outSchema);

            // ---- Execute -----------------------------------------------------
            long resultCount = 0;
            try {
                finalProj.open();
                // BufferedWriter collects small writes in memory and sends them
                // to the file in larger batches, avoiding a disk write for every
                // field or row. newBufferedWriter creates or overwrites
                // query_results.csv by default.
                try (BufferedWriter writer = Files.newBufferedWriter(
                        query.outputPath(), StandardCharsets.UTF_8)) {
                    GenericRecord result;
                    while ((result = finalProj.next()) != null) {
                        String title = RecordUtils.fromFixedBytes(result.getFieldBytes("title"));
                        String name = RecordUtils.fromFixedBytes(result.getFieldBytes("name"));
                        writer.write(title);
                        writer.write(',');
                        writer.write(name);
                        writer.newLine();
                        resultCount++;
                    }
                }
            } finally {
                // Safe even when open() failed partway: every operator releases
                // its own resources on a failed open and tolerates close() in
                // that state.
                finalProj.close();
            }

            return resultCount;
        } catch (Throwable t) {
            queryFailure = t;
            throw t;
        } finally {
            // Cleanup starts before any per-query file id is created or
            // registered, so plan-construction failures cannot leak state into
            // a caller-provided, long-lived BufferManager.
            try {
                query.cleanup();
            } catch (RuntimeException cleanupFailure) {
                if (queryFailure == null) {
                    throw cleanupFailure;
                }
                // Keep the query's own failure primary (cleanup throwing here
                // is typically a symptom of it, e.g. a page the failed query
                // left pinned).
                queryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * Registers the three base tables and the title index in the manager's
     * catalog. Safe to call more than once: re-registering the same entry just
     * overwrites it with an identical one.
     */
    public static void registerCatalog(BufferManager bm) {
        bm.register(new TableEntry(MOVIES_DB,    MOVIES_SCHEMA));
        bm.register(new TableEntry(WORKEDON_DB,  WORKEDON_SCHEMA));
        bm.register(new TableEntry(PEOPLE_DB,    PEOPLE_SCHEMA));
        bm.register(new IndexEntry(TITLE_IDX,    MOVIES_SCHEMA.get("title")));
    }

    private static final class QueryContext {
        private static final String TEMP_PREFIX = ".redb-query-";

        private final BufferManager bm;
        private final String runId;
        private final Path outputPath;
        private final List<Path> tempFiles;
        private final List<String> fileIds;

        private QueryContext(BufferManager bm, String runId, Path outputPath) {
            this.bm = bm;
            this.runId = runId;
            this.outputPath = outputPath;
            this.tempFiles = new ArrayList<>();
            this.fileIds = new ArrayList<>();
        }

        static QueryContext create(BufferManager bm, Path outputPath) {
            return new QueryContext(bm, UUID.randomUUID().toString(), outputPath);
        }

        Path outputPath() {
            return outputPath;
        }

        String tempFileId(String label, String extension) {
            Path path = Path.of(TEMP_PREFIX + runId + "-" + label + extension);
            tempFiles.add(path);
            fileIds.add(path.toString());
            return path.toString();
        }

        String scratchFileId(String label) {
            String fileId = TEMP_PREFIX + runId + "-" + label;
            fileIds.add(fileId);
            return fileId;
        }

        void cleanup() {
            // Drop this query's dead pages from the shared pool and its temp
            // table from the catalog BEFORE deleting the files: discardFile
            // waits out a concurrent eviction flush that could otherwise
            // recreate a just-deleted temp file. It also asserts none of the
            // query's pages are still pinned, so a pin leak fails loudly here.
            for (String fileId : fileIds) {
                bm.discardFile(fileId);
                bm.unregister(fileId);
            }
            for (Path tempFile : tempFiles) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup; query completion should not be masked by this.
                }
            }
        }
    }
}
