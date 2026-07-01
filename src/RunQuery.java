import buffer.BufferManager;
import catalog.IndexEntry;
import catalog.TableEntry;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
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
import storage.RawPage;
import trace.QueryTrace;
import trace.QueryTraceRecorder;
import trace.TracePlanNode;
import trace.TracePlanNodeType;
import trace.TraceTable;
import util.RecordUtils;

public class RunQuery {

    static final String MOVIES_DB   = "movies.db";
    static final String WORKEDON_DB = "workedon.db";
    static final String PEOPLE_DB   = "people.db";
    static final String TITLE_IDX   = "title.idx";
    static final String QUERY_RESULTS = "query_results.csv";
    static final int BTREE_DEGREE = 50;

    static final Map<String, Integer> MOVIES_SCHEMA;
    static final Map<String, Integer> WORKEDON_SCHEMA;
    static final Map<String, Integer> PEOPLE_SCHEMA;

    static {
        Map<String, Integer> movies = new LinkedHashMap<>();
        movies.put("movieId", 9);
        movies.put("title",  30);
        MOVIES_SCHEMA = Collections.unmodifiableMap(movies);

        Map<String, Integer> workedon = new LinkedHashMap<>();
        workedon.put("movieId",  9);
        workedon.put("personId", 10);
        workedon.put("category", 20);
        WORKEDON_SCHEMA = Collections.unmodifiableMap(workedon);

        Map<String, Integer> people = new LinkedHashMap<>();
        people.put("personId", 10);
        people.put("name",    105);
        PEOPLE_SCHEMA = Collections.unmodifiableMap(people);
    }

    public static long run(
            String startRange,
            String endRange,
            int bufferSize,
            boolean useIndex) throws IOException {
        return execute(startRange, endRange, bufferSize, useIndex, null).resultCount();
    }

    public static QueryTrace capture(
            String startRange,
            String endRange,
            int bufferSize,
            boolean useIndex) throws IOException {
        QueryTraceRecorder recorder = new QueryTraceRecorder(
                UUID.randomUUID().toString(),
                startRange,
                endRange,
                bufferSize,
                useIndex,
                buildPlan(useIndex),
                buildTraceTables());
        return execute(startRange, endRange, bufferSize, useIndex, recorder).trace();
    }

    private static RunResult execute(
            String startRange,
            String endRange,
            int bufferSize,
            boolean useIndex,
            QueryTraceRecorder recorder) throws IOException {
        // N = (B - C) / 2  where C = 1 (one frame for inner scan at any time)
        int N = (bufferSize - 1) / 2;
        if (N < 1) {
            throw new IllegalArgumentException("buffer_size must be at least 3 to run BNL join");
        }

        QueryContext query = QueryContext.create();
        BufferManager bm = new BufferManager(bufferSize);
        if (recorder != null) {
            bm.setTraceListener(recorder);
        }
        bm.register(new TableEntry(MOVIES_DB,    MOVIES_SCHEMA));
        bm.register(new TableEntry(WORKEDON_DB,  WORKEDON_SCHEMA));
        bm.register(new TableEntry(PEOPLE_DB,    PEOPLE_SCHEMA));
        bm.register(new IndexEntry(TITLE_IDX,    MOVIES_SCHEMA.get("title")));

        // ---- WorkedOn projection schema: {movieId, personId} ----------------
        Map<String, Integer> wkProj_ = new LinkedHashMap<>();
        wkProj_.put("movieId",  9);
        wkProj_.put("personId", 10);
        Map<String, Integer> wkProjSchema = Collections.unmodifiableMap(wkProj_);
        String workedonTmp = query.tempFileId("workedon-proj", ".db");
        bm.register(new TableEntry(workedonTmp, wkProjSchema));

        // ---- Leaf operators -------------------------------------------------
        Scan workedonScan = new Scan(bm, WORKEDON_DB,  WORKEDON_SCHEMA);
        Scan peopleScan   = new Scan(bm, PEOPLE_DB,    PEOPLE_SCHEMA);

        // ---- Movies access: index range scan OR scan + selection ------------
        byte[] startBytes = RecordUtils.toFixedBytes(startRange, 30);
        byte[] endBytes   = RecordUtils.toFixedBytes(endRange,   30);
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

        // ---- Selection on WorkedOn: category = "director" -------------------
        byte[] dirBytes = RecordUtils.toFixedBytes("director", 20);
        Selection wkSel = new Selection(workedonScan,
                rec -> Arrays.equals(rec.getFieldBytes("category"), dirBytes));

        // ---- Materializing projection: WorkedOn → {movieId, personId} -------
        Project wkProj = new Project(wkSel, wkProjSchema, bm, workedonTmp);

        // ---- Join 1: Movies ⋈ WorkedOn on movieId ---------------------------
        Map<String, Integer> j1_ = new LinkedHashMap<>();
        j1_.put("movieId",  9);
        j1_.put("title",   30);
        j1_.put("personId", 10);
        Map<String, Integer> j1Schema = Collections.unmodifiableMap(j1_);

        Join join1 = new Join(
                movieSel, wkProj,
                "movieId", "movieId",
                MOVIES_SCHEMA, wkProjSchema, j1Schema,
                bm, query.scratchFileId("bnl-outer-0"), N);

        // ---- Join 2: Join1 ⋈ People on personId -----------------------------
        Map<String, Integer> j2_ = new LinkedHashMap<>();
        j2_.put("movieId",  9);
        j2_.put("title",   30);
        j2_.put("personId", 10);
        j2_.put("name",   105);
        Map<String, Integer> j2Schema = Collections.unmodifiableMap(j2_);

        Join join2 = new Join(
                join1, peopleScan,
                "personId", "personId",
                j1Schema, PEOPLE_SCHEMA, j2Schema,
                bm, query.scratchFileId("bnl-outer-1"), N);

        // ---- Final pipelined projection: → {title, name} --------------------
        Map<String, Integer> out_ = new LinkedHashMap<>();
        out_.put("title", 30);
        out_.put("name", 105);
        Map<String, Integer> outSchema = Collections.unmodifiableMap(out_);

        Project finalProj = new Project(join2, outSchema);

        // ---- Execute --------------------------------------------------------
        long startNanos = System.nanoTime();
        long resultCount = 0;
        boolean opened = false;
        try {
            if (recorder != null) {
                recorder.operatorOpen("project", "open query plan");
                if (useIndex) {
                    recorder.btreeSearchBegin(TITLE_IDX, startRange, endRange);
                }
            }
            finalProj.open();
            if (recorder != null && useIndex) {
                recorder.btreeSearchEnd();
            }
            opened = true;
            // BufferedWriter collects small writes in memory and sends them to the
            // file in larger batches, avoiding a disk write for every field or row.
            // newBufferedWriter creates or overwrites query_results.csv by default.
            try (BufferedWriter writer = Files.newBufferedWriter(
                    query.outputPath(), StandardCharsets.UTF_8)) {
                GenericRecord result;
                while (true) {
                    if (recorder != null) {
                        recorder.operatorNext("project");
                    }
                    result = finalProj.next();
                    if (result == null) {
                        break;
                    }
                    if (recorder != null) {
                        recorder.operatorEmit("project");
                        recorder.queryResult(result);
                    }
                    String title = new String(result.getFieldBytes("title")).trim();
                    String name  = new String(result.getFieldBytes("name")).trim();
                    writer.write(title);
                    writer.write(',');
                    writer.write(name);
                    writer.newLine();
                    resultCount++;
                }
            }
        // finally closes the operator tree and removes this query's temp files,
        // including when an exception interrupts query execution.
        } finally {
            if (opened) {
                finalProj.close();
                if (recorder != null) {
                    recorder.operatorClose("project");
                }
            }
            query.cleanup();
        }

        long wallClockMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        if (recorder != null) {
            recorder.queryComplete(resultCount);
            return new RunResult(resultCount, recorder.toTrace(wallClockMs));
        }
        return new RunResult(resultCount, null);
    }

    private static TracePlanNode buildPlan(boolean indexed) {
        TracePlanNode moviesAccess;
        if (indexed) {
            moviesAccess = new TracePlanNode(
                    "movies-index",
                    TracePlanNodeType.INDEX_SCAN,
                    "Index Scan: Movies",
                    "B+ tree range on title",
                    List.of());
        } else {
            moviesAccess = new TracePlanNode(
                    "movies-sigma",
                    TracePlanNodeType.SELECTION,
                    "Selection: title in range",
                    "filter over full scan",
                    List.of(new TracePlanNode(
                            "movies-scan",
                            TracePlanNodeType.SCAN,
                            "Scan: Movies",
                            MOVIES_DB,
                            List.of())));
        }

        TracePlanNode workedOnPipe = new TracePlanNode(
                "wo-pi",
                TracePlanNodeType.MATERIALIZE,
                "Project: movieId, personId",
                "materialized temp file",
                List.of(new TracePlanNode(
                        "wo-sigma",
                        TracePlanNodeType.SELECTION,
                        "Selection: category = director",
                        null,
                        List.of(new TracePlanNode(
                                "wo-scan",
                                TracePlanNodeType.SCAN,
                                "Scan: WorkedOn",
                                WORKEDON_DB,
                                List.of())))));
        TracePlanNode joinMoviesWorkedOn = new TracePlanNode(
                "join-movies-wo",
                TracePlanNodeType.BNL_JOIN,
                "BNL Join: Movies.movieId = WorkedOn.movieId",
                null,
                List.of(moviesAccess, workedOnPipe));
        TracePlanNode peopleScan = new TracePlanNode(
                "people-scan",
                TracePlanNodeType.SCAN,
                "Scan: People",
                PEOPLE_DB,
                List.of());
        TracePlanNode joinPeople = new TracePlanNode(
                "join-wo-people",
                TracePlanNodeType.BNL_JOIN,
                "BNL Join: WorkedOn.personId = People.personId",
                null,
                List.of(joinMoviesWorkedOn, peopleScan));
        return new TracePlanNode(
                "project",
                TracePlanNodeType.PROJECT,
                "Project: title, name",
                null,
                List.of(joinPeople));
    }

    private static Map<String, TraceTable> buildTraceTables() throws IOException {
        Map<String, TraceTable> tables = new LinkedHashMap<>();
        tables.put(MOVIES_DB, new TraceTable(MOVIES_DB, recordSize(MOVIES_SCHEMA), recordCount(MOVIES_DB)));
        tables.put(WORKEDON_DB, new TraceTable(WORKEDON_DB, recordSize(WORKEDON_SCHEMA), recordCount(WORKEDON_DB)));
        tables.put(PEOPLE_DB, new TraceTable(PEOPLE_DB, recordSize(PEOPLE_SCHEMA), recordCount(PEOPLE_DB)));
        tables.put(TITLE_IDX, new TraceTable(TITLE_IDX, MOVIES_SCHEMA.get("title")));
        return tables;
    }

    private static int recordSize(Map<String, Integer> schema) {
        return schema.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static long recordCount(String fileId) throws IOException {
        File file = new File(fileId);
        if (!file.exists() || file.length() == 0) {
            return 0;
        }
        if (file.length() % RawPage.MAX_PAGE_LEN != 0) {
            throw new IllegalStateException("File size is not a multiple of pages: " + fileId);
        }
        long count = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[4];
            int pageCount = Math.toIntExact(file.length() / RawPage.MAX_PAGE_LEN);
            for (int pageId = 0; pageId < pageCount; pageId++) {
                raf.seek(RawPage.getOffset(pageId));
                raf.readFully(header);
                count += ByteBuffer.wrap(header).getInt();
            }
        }
        return count;
    }

    private record RunResult(long resultCount, QueryTrace trace) {}

    private static final class QueryContext {
        private static final String TEMP_PREFIX = ".redb-query-";

        private final String runId;
        private final Path outputPath;
        private final List<Path> tempFiles;

        private QueryContext(String runId, Path outputPath) {
            this.runId = runId;
            this.outputPath = outputPath;
            this.tempFiles = new ArrayList<>();
        }

        static QueryContext create() {
            return new QueryContext(UUID.randomUUID().toString(), Path.of(QUERY_RESULTS));
        }

        Path outputPath() {
            return outputPath;
        }

        String tempFileId(String label, String extension) {
            Path path = Path.of(TEMP_PREFIX + runId + "-" + label + extension);
            tempFiles.add(path);
            return path.toString();
        }

        String scratchFileId(String label) {
            return TEMP_PREFIX + runId + "-" + label;
        }

        void cleanup() {
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
