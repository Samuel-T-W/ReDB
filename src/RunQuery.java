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
import trace.QueryTrace;
import trace.QueryTraceRecorder;
import trace.TracePlanNode;
import trace.TracePlanNodeType;
import trace.TraceTable;
import trace.TraceTables;
import trace.TracingOperator;
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
        Operator workedonScan = TracingOperator.wrap(
                recorder, new Scan(bm, WORKEDON_DB, WORKEDON_SCHEMA),
                "wo-scan", TracePlanNodeType.SCAN, "Scan: WorkedOn", WORKEDON_DB);
        Operator peopleScan = TracingOperator.wrap(
                recorder, new Scan(bm, PEOPLE_DB, PEOPLE_SCHEMA),
                "people-scan", TracePlanNodeType.SCAN, "Scan: People", PEOPLE_DB);

        // ---- Movies access: index range scan OR scan + selection ------------
        byte[] startBytes = RecordUtils.toFixedBytes(startRange, 30);
        byte[] endBytes   = RecordUtils.toFixedBytes(endRange,   30);
        Operator movieSel;
        if (useIndex) {
            BTreeManager titleIdx = BTreeManager.openExisting(
                    BTREE_DEGREE, TITLE_IDX, bm, MOVIES_SCHEMA.get("title"));
            IndexScan indexScan = new IndexScan(bm, MOVIES_DB, MOVIES_SCHEMA, titleIdx,
                    new K(startBytes), new K(endBytes));
            // Brackets the B+ tree range search specifically, so BTREE_NODE_VISIT
            // events land between a distinct begin/end pair rather than being
            // folded into the generic operator-open event.
            Operator searched = recorder == null ? indexScan : new Operator() {
                @Override public void open() {
                    recorder.btreeSearchBegin(TITLE_IDX, startRange, endRange);
                    indexScan.open();
                    recorder.btreeSearchEnd();
                }
                @Override public GenericRecord next() { return indexScan.next(); }
                @Override public void close() { indexScan.close(); }
            };
            movieSel = TracingOperator.wrap(recorder, searched,
                    "movies-index", TracePlanNodeType.INDEX_SCAN, "Index Scan: Movies",
                    "B+ tree range on title");
        } else {
            Operator movieScan = TracingOperator.wrap(
                    recorder, new Scan(bm, MOVIES_DB, MOVIES_SCHEMA),
                    "movies-scan", TracePlanNodeType.SCAN, "Scan: Movies", MOVIES_DB);
            Selection movieSelRaw = new Selection(movieScan, rec -> {
                byte[] t = rec.getFieldBytes("title");
                return Arrays.compare(t, startBytes) >= 0
                    && Arrays.compare(t, endBytes)   <= 0;
            });
            movieSel = TracingOperator.wrap(recorder, movieSelRaw,
                    "movies-sigma", TracePlanNodeType.SELECTION, "Selection: title in range",
                    "filter over full scan", movieScan);
        }

        // ---- Selection on WorkedOn: category = "director" -------------------
        byte[] dirBytes = RecordUtils.toFixedBytes("director", 20);
        Selection wkSelRaw = new Selection(workedonScan,
                rec -> Arrays.equals(rec.getFieldBytes("category"), dirBytes));
        Operator wkSel = TracingOperator.wrap(recorder, wkSelRaw,
                "wo-sigma", TracePlanNodeType.SELECTION, "Selection: category = director", workedonScan);

        // ---- Materializing projection: WorkedOn → {movieId, personId} -------
        Project wkProjRaw = new Project(wkSel, wkProjSchema, bm, workedonTmp);
        Operator wkProj = TracingOperator.wrap(recorder, wkProjRaw,
                "wo-pi", TracePlanNodeType.MATERIALIZE, "Project: movieId, personId",
                "materialized temp file", wkSel);

        // ---- Join 1: Movies ⋈ WorkedOn on movieId ---------------------------
        Map<String, Integer> j1_ = new LinkedHashMap<>();
        j1_.put("movieId",  9);
        j1_.put("title",   30);
        j1_.put("personId", 10);
        Map<String, Integer> j1Schema = Collections.unmodifiableMap(j1_);

        Join join1Raw = new Join(
                movieSel, wkProj,
                "movieId", "movieId",
                MOVIES_SCHEMA, wkProjSchema, j1Schema,
                bm, query.scratchFileId("bnl-outer-0"), N);
        Operator join1 = TracingOperator.wrap(recorder, join1Raw,
                "join-movies-wo", TracePlanNodeType.BNL_JOIN,
                "BNL Join: Movies.movieId = WorkedOn.movieId", movieSel, wkProj);

        // ---- Join 2: Join1 ⋈ People on personId -----------------------------
        Map<String, Integer> j2_ = new LinkedHashMap<>();
        j2_.put("movieId",  9);
        j2_.put("title",   30);
        j2_.put("personId", 10);
        j2_.put("name",   105);
        Map<String, Integer> j2Schema = Collections.unmodifiableMap(j2_);

        Join join2Raw = new Join(
                join1, peopleScan,
                "personId", "personId",
                j1Schema, PEOPLE_SCHEMA, j2Schema,
                bm, query.scratchFileId("bnl-outer-1"), N);
        Operator join2 = TracingOperator.wrap(recorder, join2Raw,
                "join-wo-people", TracePlanNodeType.BNL_JOIN,
                "BNL Join: WorkedOn.personId = People.personId", join1, peopleScan);

        // ---- Final pipelined projection: → {title, name} --------------------
        Map<String, Integer> out_ = new LinkedHashMap<>();
        out_.put("title", 30);
        out_.put("name", 105);
        Map<String, Integer> outSchema = Collections.unmodifiableMap(out_);

        Project finalProjRaw = new Project(join2, outSchema);
        Operator finalProj = TracingOperator.wrap(recorder, finalProjRaw,
                "project", TracePlanNodeType.PROJECT, "Project: title, name", join2);

        // ---- Execute --------------------------------------------------------
        long startNanos = System.nanoTime();
        long resultCount = 0;
        boolean opened = false;
        try {
            finalProj.open();
            opened = true;
            // BufferedWriter collects small writes in memory and sends them to the
            // file in larger batches, avoiding a disk write for every field or row.
            // newBufferedWriter creates or overwrites query_results.csv by default.
            try (BufferedWriter writer = Files.newBufferedWriter(
                    query.outputPath(), StandardCharsets.UTF_8)) {
                GenericRecord result;
                while ((result = finalProj.next()) != null) {
                    if (recorder != null) {
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
            }
            query.cleanup();
        }

        long wallClockMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        if (recorder != null) {
            recorder.queryComplete(resultCount);
            TracePlanNode plan = ((TracingOperator) finalProj).describe();
            return new RunResult(resultCount, recorder.toTrace(plan, wallClockMs));
        }
        return new RunResult(resultCount, null);
    }

    private static Map<String, TraceTable> buildTraceTables() throws IOException {
        Map<String, TraceTable> tables = new LinkedHashMap<>();
        tables.put(MOVIES_DB, TraceTables.forTable(MOVIES_DB, MOVIES_SCHEMA));
        tables.put(WORKEDON_DB, TraceTables.forTable(WORKEDON_DB, WORKEDON_SCHEMA));
        tables.put(PEOPLE_DB, TraceTables.forTable(PEOPLE_DB, PEOPLE_SCHEMA));
        tables.put(TITLE_IDX, TraceTables.forIndex(TITLE_IDX, MOVIES_SCHEMA.get("title")));
        return tables;
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
