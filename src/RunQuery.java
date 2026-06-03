import buffer.BufferManager;
import buffer.IndexEntry;
import buffer.TableEntry;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
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
import util.preprocessor.PreProcessorUtils;

public class RunQuery {

    static final String MOVIES_DB   = "movies.db";
    static final String WORKEDON_DB = "workedon.db";
    static final String PEOPLE_DB   = "people.db";
    static final String TITLE_IDX   = "title.idx";
    static final String WORKEDON_TMP = "workedon_proj_tmp.db";

    static final Map<String, Integer> MOVIES_SCHEMA   = new LinkedHashMap<>();
    static final Map<String, Integer> WORKEDON_SCHEMA = new LinkedHashMap<>();
    static final Map<String, Integer> PEOPLE_SCHEMA   = new LinkedHashMap<>();

    static {
        MOVIES_SCHEMA.put("movieId", 9);
        MOVIES_SCHEMA.put("title",  30);

        WORKEDON_SCHEMA.put("movieId",  9);
        WORKEDON_SCHEMA.put("personId", 10);
        WORKEDON_SCHEMA.put("category", 20);

        PEOPLE_SCHEMA.put("personId", 10);
        PEOPLE_SCHEMA.put("name",    105);
    }

    public static void run(String startRange, String endRange, int bufferSize) throws IOException {
        run(startRange, endRange, bufferSize, false);
    }

    public static void run(String startRange, String endRange, int bufferSize, boolean useIndex) throws IOException {
        // N = (B - C) / 2  where C = 1 (one frame for inner scan at any time)
        int N = (bufferSize - 1) / 2;
        if (N < 1) {
            System.err.println("Error: buffer_size must be at least 3 to run BNL join");
            return;
        }

        BufferManager bm = new BufferManager(bufferSize);
        bm.register(new TableEntry(MOVIES_DB,    MOVIES_SCHEMA));
        bm.register(new TableEntry(WORKEDON_DB,  WORKEDON_SCHEMA));
        bm.register(new TableEntry(PEOPLE_DB,    PEOPLE_SCHEMA));
        bm.register(new IndexEntry(TITLE_IDX,    MOVIES_SCHEMA.get("title")));

        // ---- WorkedOn projection schema: {movieId, personId} ----------------
        Map<String, Integer> wkProjSchema = new LinkedHashMap<>();
        wkProjSchema.put("movieId",  9);
        wkProjSchema.put("personId", 10);
        bm.register(new TableEntry(WORKEDON_TMP, wkProjSchema));

        // ---- Leaf operators -------------------------------------------------
        Scan workedonScan = new Scan(bm, WORKEDON_DB,  WORKEDON_SCHEMA);
        Scan peopleScan   = new Scan(bm, PEOPLE_DB,    PEOPLE_SCHEMA);

        // ---- Movies access: index range scan OR scan + selection ------------
        byte[] startBytes = RecordUtils.toFixedBytes(startRange, 30);
        byte[] endBytes   = RecordUtils.toFixedBytes(endRange,   30);
        Operator movieSel;
        if (useIndex) {
            int moviesPages = Math.toIntExact(new File(MOVIES_DB).length() / storage.RawPage.MAX_PAGE_LEN);
            PreProcessorUtils.resetFile(TITLE_IDX);
            BTreeManager titleIdx = PreProcessorUtils.buildIndex(
                    bm, moviesPages, MOVIES_DB, MOVIES_SCHEMA, TITLE_IDX, "title", 50);
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
        Project wkProj = new Project(wkSel, wkProjSchema, bm, WORKEDON_TMP);

        // ---- Join 1: Movies ⋈ WorkedOn on movieId ---------------------------
        Map<String, Integer> j1Schema = new LinkedHashMap<>();
        j1Schema.put("movieId",  9);
        j1Schema.put("title",   30);
        j1Schema.put("personId", 10);

        Join join1 = new Join(
                movieSel, wkProj,
                "movieId", "movieId",
                MOVIES_SCHEMA, wkProjSchema, j1Schema,
                bm, "__bnl_outer_0__", N);

        // ---- Join 2: Join1 ⋈ People on personId -----------------------------
        Map<String, Integer> j2Schema = new LinkedHashMap<>();
        j2Schema.put("movieId",  9);
        j2Schema.put("title",   30);
        j2Schema.put("personId", 10);
        j2Schema.put("name",   105);

        Join join2 = new Join(
                join1, peopleScan,
                "personId", "personId",
                j1Schema, PEOPLE_SCHEMA, j2Schema,
                bm, "__bnl_outer_1__", N);

        // ---- Final pipelined projection: → {title, name} --------------------
        Map<String, Integer> outSchema = new LinkedHashMap<>();
        outSchema.put("title", 30);
        outSchema.put("name", 105);

        Project finalProj = new Project(join2, outSchema);

        // ---- Execute --------------------------------------------------------
        finalProj.open();
        GenericRecord result;
        while ((result = finalProj.next()) != null) {
            String title = new String(result.getFieldBytes("title")).trim();
            String name  = new String(result.getFieldBytes("name")).trim();
            System.out.println(title + "," + name);
        }
        finalProj.close();

        // ---- Cleanup temp file ----------------------------------------------
        wkProj.cleanup();
        new File(WORKEDON_TMP).delete();
    }
}
