package operators;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import buffer.BufferManager;
import catalog.TableEntry;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import storage.GenericRecord;

public class ProjectTest {

    // -----------------------------------------------------------------------
    // Schemas
    // -----------------------------------------------------------------------

    // Input schema for pipelined tests (Movies)
    private static final Map<String, Integer> MOVIES_SCHEMA = new LinkedHashMap<>();
    // Projected output: title only
    private static final Map<String, Integer> TITLE_ONLY_SCHEMA = new LinkedHashMap<>();

    // Input schema for materializing tests (WorkedOn)
    private static final Map<String, Integer> WORKEDON_SCHEMA = new LinkedHashMap<>();
    // Projected output: {movieId, personId} — category dropped
    private static final Map<String, Integer> WORKEDON_PROJ_SCHEMA = new LinkedHashMap<>();

    static {
        MOVIES_SCHEMA.put("movieId", 9);
        MOVIES_SCHEMA.put("title", 30);

        TITLE_ONLY_SCHEMA.put("title", 30);

        WORKEDON_SCHEMA.put("movieId", 9);
        WORKEDON_SCHEMA.put("personId", 10);
        WORKEDON_SCHEMA.put("category", 20);

        WORKEDON_PROJ_SCHEMA.put("movieId", 9);
        WORKEDON_PROJ_SCHEMA.put("personId", 10);
    }

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    private BufferManager bm;
    private String movieFile;
    private String workedonFile;
    private String tmpFile;

    @BeforeEach
    void setup() throws Exception {
        bm = new BufferManager(20);

        movieFile = File.createTempFile("proj_movies_", ".db").getAbsolutePath();
        workedonFile = File.createTempFile("proj_workedon_", ".db").getAbsolutePath();
        tmpFile = File.createTempFile("proj_tmp_", ".db").getAbsolutePath();

        // Pre-delete tmpFile so the materializing Project creates it fresh
        new File(tmpFile).delete();

        bm.register(new TableEntry(movieFile, MOVIES_SCHEMA));
        bm.register(new TableEntry(workedonFile, WORKEDON_SCHEMA));
        bm.register(new TableEntry(tmpFile, WORKEDON_PROJ_SCHEMA));
    }

    @AfterEach
    void teardown() {
        new File(movieFile).delete();
        new File(workedonFile).delete();
        new File(tmpFile).delete();
    }

    // -----------------------------------------------------------------------
    // Helper: build a WorkedOn record
    // -----------------------------------------------------------------------

    private GenericRecord makeWorkedonRecord(String movieId, String personId, String category) {
        return GenericRecord.create(WORKEDON_SCHEMA)
                .set("movieId",  toFixedBytes(movieId,  9))
                .set("personId", toFixedBytes(personId, 10))
                .set("category", toFixedBytes(category, 20));
    }

    // -----------------------------------------------------------------------
    // Pipelined tests
    // -----------------------------------------------------------------------

    /**
     * A pipelined Project on Movies → {title} must return only the title field.
     * The output record must NOT carry movieId (output schema has only "title").
     */
    @Test
    void pipelined_projectsSubsetOfFields() throws Exception {
        List<GenericRecord> recs = List.of(
                makeMovieRecord(MOVIES_SCHEMA, "tt0000001", "Alpha"),
                makeMovieRecord(MOVIES_SCHEMA, "tt0000002", "Beta")
        );
        writePages(bm, movieFile, MOVIES_SCHEMA, recs);

        Project proj = new Project(new Scan(bm, movieFile, MOVIES_SCHEMA),
                TITLE_ONLY_SCHEMA);
        proj.open();

        GenericRecord r = proj.next();
        assertNotNull(r);
        assertEquals("Alpha", fromFixedBytes(r.getFieldBytes("title")));
        // Output schema has no "movieId" — accessing it should throw
        assertThrows(IllegalArgumentException.class, () -> r.getFieldBytes("movieId"));

        GenericRecord r2 = proj.next();
        assertNotNull(r2);
        assertEquals("Beta", fromFixedBytes(r2.getFieldBytes("title")));

        assertNull(proj.next());
        proj.close();
    }

    /** Pipelined Project on an empty file returns null immediately. */
    @Test
    void pipelined_emptyInput_returnsNull() throws Exception {
        writePages(bm, movieFile, MOVIES_SCHEMA, List.of());

        Project proj = new Project(new Scan(bm, movieFile, MOVIES_SCHEMA),
                TITLE_ONLY_SCHEMA);
        proj.open();
        assertNull(proj.next());
        proj.close();
    }

    /** All records from the child are projected and returned in order. */
    @Test
    void pipelined_multipleRecords_allProjectedCorrectly() throws Exception {
        List<GenericRecord> recs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            recs.add(makeMovieRecord(MOVIES_SCHEMA, String.format("tt%07d", i), "Title" + i));
        }
        writePages(bm, movieFile, MOVIES_SCHEMA, recs);

        Project proj = new Project(new Scan(bm, movieFile, MOVIES_SCHEMA),
                TITLE_ONLY_SCHEMA);
        proj.open();

        List<String> titles = new ArrayList<>();
        GenericRecord r;
        while ((r = proj.next()) != null) {
            titles.add(fromFixedBytes(r.getFieldBytes("title")));
        }
        proj.close();

        assertEquals(List.of("Title1", "Title2", "Title3", "Title4", "Title5"), titles);
    }

    // -----------------------------------------------------------------------
    // Materializing tests
    // -----------------------------------------------------------------------

    /**
     * Materializing Project: all WorkedOn records are projected to {movieId, personId}
     * and returned in insertion order.
     */
    @Test
    void materializing_allRecordsReturnedInOrder() throws Exception {
        List<GenericRecord> recs = List.of(
                makeWorkedonRecord("tt0000001", "nm0000001", "director"),
                makeWorkedonRecord("tt0000002", "nm0000002", "director"),
                makeWorkedonRecord("tt0000003", "nm0000003", "actor")
        );
        writePages(bm, workedonFile, WORKEDON_SCHEMA, recs);

        Project proj = new Project(
                new Scan(bm, workedonFile, WORKEDON_SCHEMA),
                WORKEDON_PROJ_SCHEMA, bm, tmpFile);
        proj.open();

        List<String> movieIds = new ArrayList<>();
        List<String> personIds = new ArrayList<>();
        GenericRecord r;
        while ((r = proj.next()) != null) {
            movieIds.add(fromFixedBytes(r.getFieldBytes("movieId")));
            personIds.add(fromFixedBytes(r.getFieldBytes("personId")));
        }
        proj.close();

        assertEquals(List.of("tt0000001", "tt0000002", "tt0000003"), movieIds);
        assertEquals(List.of("nm0000001", "nm0000002", "nm0000003"), personIds);
    }

    /**
     * Materializing Project: after close() then open(), the scan restarts from
     * the beginning without re-draining the child. All records are returned again.
     */
    @Test
    void materializing_canReopen_afterClose() throws Exception {
        List<GenericRecord> recs = List.of(
                makeWorkedonRecord("tt0000001", "nm0000001", "director"),
                makeWorkedonRecord("tt0000002", "nm0000002", "actor")
        );
        writePages(bm, workedonFile, WORKEDON_SCHEMA, recs);

        Project proj = new Project(
                new Scan(bm, workedonFile, WORKEDON_SCHEMA),
                WORKEDON_PROJ_SCHEMA, bm, tmpFile);

        // First open: drain + return records
        proj.open();
        List<String> firstPass = new ArrayList<>();
        GenericRecord r;
        while ((r = proj.next()) != null) {
            firstPass.add(fromFixedBytes(r.getFieldBytes("movieId")));
        }
        proj.close();

        // Second open: should re-scan temp file, NOT re-drain child
        proj.open();
        List<String> secondPass = new ArrayList<>();
        while ((r = proj.next()) != null) {
            secondPass.add(fromFixedBytes(r.getFieldBytes("movieId")));
        }
        proj.close();

        assertEquals(firstPass, secondPass);
        assertEquals(2, secondPass.size());
    }

    /**
     * After cleanup() is called, the temp file must no longer exist on disk.
     */
    @Test
    void materializing_cleanupDeletesFile() throws Exception {
        List<GenericRecord> recs = List.of(
                makeWorkedonRecord("tt0000001", "nm0000001", "director")
        );
        writePages(bm, workedonFile, WORKEDON_SCHEMA, recs);

        Project proj = new Project(
                new Scan(bm, workedonFile, WORKEDON_SCHEMA),
                WORKEDON_PROJ_SCHEMA, bm, tmpFile);

        proj.open();
        proj.next();    // trigger materialization
        proj.close();

        assertTrue(new File(tmpFile).exists(), "temp file should exist before cleanup");

        proj.cleanup();

        assertFalse(new File(tmpFile).exists(), "temp file should be deleted after cleanup");
    }
}
