package operators;

import static org.junit.jupiter.api.Assertions.*;
import static testutil.TestUtils.*;

import buffer.BufferManager;
import buffer.TableEntry;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import storage.GenericRecord;

public class JoinTest {

    // -----------------------------------------------------------------------
    // Schemas
    // -----------------------------------------------------------------------

    /** Movies: {movieId:9, title:30} */
    private static final Map<String, Integer> MOVIES_SCHEMA = new LinkedHashMap<>();
    /** WorkedOn projection: {movieId:9, personId:10} */
    private static final Map<String, Integer> WORKEDON_PROJ_SCHEMA = new LinkedHashMap<>();
    /** Join1 output: {movieId:9, title:30, personId:10} */
    private static final Map<String, Integer> JOIN1_SCHEMA = new LinkedHashMap<>();
    /** People: {personId:10, name:105} */
    private static final Map<String, Integer> PEOPLE_SCHEMA = new LinkedHashMap<>();
    /** Join2 output: {movieId:9, title:30, personId:10, name:105} */
    private static final Map<String, Integer> JOIN2_SCHEMA = new LinkedHashMap<>();

    static {
        MOVIES_SCHEMA.put("movieId", 9);
        MOVIES_SCHEMA.put("title", 30);

        WORKEDON_PROJ_SCHEMA.put("movieId", 9);
        WORKEDON_PROJ_SCHEMA.put("personId", 10);

        JOIN1_SCHEMA.put("movieId", 9);
        JOIN1_SCHEMA.put("title", 30);
        JOIN1_SCHEMA.put("personId", 10);

        PEOPLE_SCHEMA.put("personId", 10);
        PEOPLE_SCHEMA.put("name", 105);

        JOIN2_SCHEMA.put("movieId", 9);
        JOIN2_SCHEMA.put("title", 30);
        JOIN2_SCHEMA.put("personId", 10);
        JOIN2_SCHEMA.put("name", 105);
    }

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    private BufferManager bm;
    private String movieFile;
    private String workedonProjFile;
    private String peopleFile;

    @BeforeEach
    void setup() throws Exception {
        bm = new BufferManager(50);
        movieFile       = File.createTempFile("join_movies_",  ".db").getAbsolutePath();
        workedonProjFile = File.createTempFile("join_wkproj_", ".db").getAbsolutePath();
        peopleFile      = File.createTempFile("join_people_",  ".db").getAbsolutePath();
        bm.register(new TableEntry(movieFile,        MOVIES_SCHEMA));
        bm.register(new TableEntry(workedonProjFile, WORKEDON_PROJ_SCHEMA));
        bm.register(new TableEntry(peopleFile,       PEOPLE_SCHEMA));
    }

    @AfterEach
    void teardown() {
        for (String f : List.of(movieFile, workedonProjFile, peopleFile))
            new File(f).delete();
    }

    // -----------------------------------------------------------------------
    // Record helpers
    // -----------------------------------------------------------------------

    private GenericRecord wkProj(String movieId, String personId) {
        return GenericRecord.create(WORKEDON_PROJ_SCHEMA)
                .set("movieId",  toFixedBytes(movieId,  9))
                .set("personId", toFixedBytes(personId, 10));
    }

    private GenericRecord person(String personId, String name) {
        return GenericRecord.create(PEOPLE_SCHEMA)
                .set("personId", toFixedBytes(personId, 10))
                .set("name",     toFixedBytes(name, 105));
    }

    /** Creates a Join1 instance with a given block size. */
    private Join join1(Operator outer, Operator inner, int blockSize) {
        return new Join(outer, inner,
                "movieId", "movieId",
                MOVIES_SCHEMA, WORKEDON_PROJ_SCHEMA, JOIN1_SCHEMA,
                bm, "__bnl_join1__", blockSize);
    }

    /** Creates a Join2 instance with a given block size. */
    private Join join2(Operator outer, Operator inner, int blockSize) {
        return new Join(outer, inner,
                "personId", "personId",
                JOIN1_SCHEMA, PEOPLE_SCHEMA, JOIN2_SCHEMA,
                bm, "__bnl_join2__", blockSize);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Three movies each matched by exactly one WorkedOn record.
     * Join1 must return exactly 3 records with correct title and personId.
     */
    @Test
    void basicJoin_oneToOne() throws Exception {
        List<GenericRecord> movies = List.of(
                makeMovieRecord(MOVIES_SCHEMA, "tt0000001", "The Shining"),
                makeMovieRecord(MOVIES_SCHEMA, "tt0000002", "2001"),
                makeMovieRecord(MOVIES_SCHEMA, "tt0000003", "Psycho")
        );
        List<GenericRecord> wk = List.of(
                wkProj("tt0000001", "nm0000001"),
                wkProj("tt0000002", "nm0000002"),
                wkProj("tt0000003", "nm0000003")
        );
        writePages(bm, movieFile,        MOVIES_SCHEMA,       movies);
        writePages(bm, workedonProjFile, WORKEDON_PROJ_SCHEMA, wk);

        Join join = join1(new Scan(bm, movieFile, MOVIES_SCHEMA),
                          new Scan(bm, workedonProjFile, WORKEDON_PROJ_SCHEMA), 5);
        join.open();

        Set<String> titles = new HashSet<>();
        GenericRecord r;
        while ((r = join.next()) != null) {
            titles.add(fromFixedBytes(r.getFieldBytes("title")));
            assertNotNull(r.getFieldBytes("personId"));
        }
        join.close();

        assertEquals(3, titles.size());
        assertTrue(titles.contains("The Shining"));
        assertTrue(titles.contains("2001"));
        assertTrue(titles.contains("Psycho"));
    }

    /**
     * No movieId in the outer matches any movieId in the inner.
     * next() must return null immediately.
     */
    @Test
    void join_noMatches_returnsNull() throws Exception {
        writePages(bm, movieFile,
                MOVIES_SCHEMA,
                List.of(makeMovieRecord(MOVIES_SCHEMA, "tt0000001", "Alpha")));
        writePages(bm, workedonProjFile,
                WORKEDON_PROJ_SCHEMA,
                List.of(wkProj("tt9999999", "nm0000001")));

        Join join = join1(new Scan(bm, movieFile, MOVIES_SCHEMA),
                          new Scan(bm, workedonProjFile, WORKEDON_PROJ_SCHEMA), 5);
        join.open();
        assertNull(join.next());
        join.close();
    }

    /**
     * One movie matched by three WorkedOn records with the same movieId.
     * Join must return exactly 3 records, all carrying the movie's title.
     */
    @Test
    void join_oneToMany() throws Exception {
        writePages(bm, movieFile,
                MOVIES_SCHEMA,
                List.of(makeMovieRecord(MOVIES_SCHEMA, "tt0000001", "Alpha")));
        writePages(bm, workedonProjFile,
                WORKEDON_PROJ_SCHEMA,
                List.of(
                        wkProj("tt0000001", "nm0000001"),
                        wkProj("tt0000001", "nm0000002"),
                        wkProj("tt0000001", "nm0000003")
                ));

        Join join = join1(new Scan(bm, movieFile, MOVIES_SCHEMA),
                          new Scan(bm, workedonProjFile, WORKEDON_PROJ_SCHEMA), 5);
        join.open();

        int count = 0;
        GenericRecord r;
        while ((r = join.next()) != null) {
            assertEquals("Alpha", fromFixedBytes(r.getFieldBytes("title")));
            count++;
        }
        join.close();

        assertEquals(3, count);
    }

    /**
     * The join attribute from the inner relation must NOT appear twice in the output.
     * The output schema has movieId from the outer; the inner's movieId is dropped.
     * Accessing "category" (a field outside the output schema) must throw.
     */
    @Test
    void join_outputSchemaDropsDuplicateKey() throws Exception {
        writePages(bm, movieFile,
                MOVIES_SCHEMA,
                List.of(makeMovieRecord(MOVIES_SCHEMA, "tt0000001", "Alpha")));
        writePages(bm, workedonProjFile,
                WORKEDON_PROJ_SCHEMA,
                List.of(wkProj("tt0000001", "nm0000001")));

        Join join = join1(new Scan(bm, movieFile, MOVIES_SCHEMA),
                          new Scan(bm, workedonProjFile, WORKEDON_PROJ_SCHEMA), 5);
        join.open();
        GenericRecord r = join.next();
        assertNotNull(r);

        // outer's movieId is in output
        assertEquals("tt0000001", fromFixedBytes(r.getFieldBytes("movieId")));
        // inner's extra field is in output
        assertEquals("nm0000001", fromFixedBytes(r.getFieldBytes("personId")));
        // a field that belongs to neither schema must throw
        assertThrows(IllegalArgumentException.class, () -> r.getFieldBytes("category"));

        join.close();
    }

    /**
     * With blockSize=1 the outer spans 2+ pages, requiring multiple blocks.
     * The inner must be rewound between blocks so every outer record finds its match.
     * Uses 110 movie records (> 1 page capacity of 104) so the join crosses a block boundary.
     */
    @Test
    void join_blockBoundary_spansMultipleBlocks() throws Exception {
        int total = 110;
        List<GenericRecord> movies = new ArrayList<>();
        List<GenericRecord> wk     = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            String mid = String.format("tt%07d", i);
            String pid = String.format("nm%07d", i);
            movies.add(makeMovieRecord(MOVIES_SCHEMA, mid, "Title" + i));
            wk.add(wkProj(mid, pid));
        }
        writePages(bm, movieFile,        MOVIES_SCHEMA,       movies);
        writePages(bm, workedonProjFile, WORKEDON_PROJ_SCHEMA, wk);

        Join join = join1(new Scan(bm, movieFile, MOVIES_SCHEMA),
                          new Scan(bm, workedonProjFile, WORKEDON_PROJ_SCHEMA),
                          1 /* blockSize = 1 page → forces at least 2 blocks */);
        join.open();

        int count = 0;
        while (join.next() != null) count++;
        join.close();

        assertEquals(total, count,
                "every outer record must be joined across both blocks");
    }

    /**
     * Full two-level join: Movies ⋈ WorkedOn ⋈ People.
     * Verifies that (title, name) pairs in the output exactly match the expected set.
     */
    @Test
    void chainedJoins_titlesAndNamesMatch() throws Exception {
        // ---- data -----------------------------------------------------------
        List<GenericRecord> movies = List.of(
                makeMovieRecord(MOVIES_SCHEMA, "tt0000001", "The Shining"),
                makeMovieRecord(MOVIES_SCHEMA, "tt0000002", "2001"),
                makeMovieRecord(MOVIES_SCHEMA, "tt0000003", "Psycho")  // no director
        );
        List<GenericRecord> wk = List.of(
                wkProj("tt0000001", "nm0000001"),
                wkProj("tt0000002", "nm0000002")
        );
        List<GenericRecord> people = List.of(
                person("nm0000001", "Director A"),
                person("nm0000002", "Director B")
        );

        writePages(bm, movieFile,        MOVIES_SCHEMA,       movies);
        writePages(bm, workedonProjFile, WORKEDON_PROJ_SCHEMA, wk);
        writePages(bm, peopleFile,       PEOPLE_SCHEMA,       people);

        // ---- operator tree --------------------------------------------------
        Join join1 = join1(
                new Scan(bm, movieFile,        MOVIES_SCHEMA),
                new Scan(bm, workedonProjFile, WORKEDON_PROJ_SCHEMA),
                5);

        Join join2 = join2(
                join1,
                new Scan(bm, peopleFile, PEOPLE_SCHEMA),
                5);

        // ---- execute --------------------------------------------------------
        join2.open();
        Set<String> results = new HashSet<>();
        GenericRecord r;
        while ((r = join2.next()) != null) {
            String title = fromFixedBytes(r.getFieldBytes("title"));
            String name  = fromFixedBytes(r.getFieldBytes("name"));
            results.add(title + "|" + name);
        }
        join2.close();

        // ---- assert ---------------------------------------------------------
        assertEquals(2, results.size());
        assertTrue(results.contains("The Shining|Director A"));
        assertTrue(results.contains("2001|Director B"));
        // Psycho has no match — must not appear
        assertFalse(results.stream().anyMatch(s -> s.startsWith("Psycho")));
    }
}
