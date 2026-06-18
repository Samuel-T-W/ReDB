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

public class SelectionTest {

    private static final Map<String, Integer> SCHEMA = new LinkedHashMap<>();
    private static final int MOVIE_ID_SIZE = 9;
    private static final int TITLE_SIZE = 30;

    static {
        SCHEMA.put("movieId", MOVIE_ID_SIZE);
        SCHEMA.put("title", TITLE_SIZE);
    }

    private BufferManager bm;
    private String filePath;

    @BeforeEach
    void setup() throws Exception {
        bm = new BufferManager(10);
        File tmp = File.createTempFile("selection_test", ".dat");
        tmp.deleteOnExit();
        filePath = tmp.getAbsolutePath();
        bm.register(new TableEntry(filePath, SCHEMA));
    }

    @AfterEach
    void teardown() throws Exception {
        new File(filePath).delete();
    }

    @Test
    void matchingRecords_returnedInInputOrder() throws Exception {
        List<GenericRecord> records = List.of(
                makeMovieRecord(SCHEMA, "tt0000001", "Alpha"),
                makeMovieRecord(SCHEMA, "tt0000002", "Beta"),
                makeMovieRecord(SCHEMA, "tt0000003", "Another")
        );
        writePages(bm, filePath, SCHEMA, records);

        Selection selection = new Selection(
                new Scan(bm, filePath, SCHEMA),
                r -> fromFixedBytes(r.getFieldBytes("title")).startsWith("A")
        );
        selection.open();

        List<String> titles = new ArrayList<>();
        GenericRecord r;
        while ((r = selection.next()) != null) {
            titles.add(fromFixedBytes(r.getFieldBytes("title")));
        }
        selection.close();

        assertEquals(List.of("Alpha", "Another"), titles);
    }

    @Test
    void matchingMovieId_returnsOnlyEqualRecord() throws Exception {
        List<GenericRecord> records = List.of(
                makeMovieRecord(SCHEMA, "tt0000001", "Alpha"),
                makeMovieRecord(SCHEMA, "tt0000002", "Beta"),
                makeMovieRecord(SCHEMA, "tt0000003", "Another"),
                makeMovieRecord(SCHEMA, "tt0000004", "Delta")
        );
        writePages(bm, filePath, SCHEMA, records);

        Selection selection = new Selection(
                new Scan(bm, filePath, SCHEMA),
                r -> fromFixedBytes(r.getFieldBytes("movieId")).equals("tt0000003")
        );
        selection.open();

        List<String> movieIds = new ArrayList<>();
        GenericRecord r;
        while ((r = selection.next()) != null) {
            movieIds.add(fromFixedBytes(r.getFieldBytes("movieId")));
        }
        selection.close();

        assertEquals(List.of("tt0000003"), movieIds);
    }

    @Test
    void noMatchingRecords_returnsNull() throws Exception {
        List<GenericRecord> records = List.of(
                makeMovieRecord(SCHEMA, "tt0000001", "Alpha"),
                makeMovieRecord(SCHEMA, "tt0000002", "Beta")
        );
        writePages(bm, filePath, SCHEMA, records);

        Selection selection = new Selection(
                new Scan(bm, filePath, SCHEMA),
                r -> fromFixedBytes(r.getFieldBytes("title")).equals("Gamma")
        );
        selection.open();

        assertNull(selection.next());
        selection.close();
    }

    @Test
    void emptyChild_returnsNull() throws Exception {
        writePages(bm, filePath, SCHEMA, List.of());

        Selection selection = new Selection(
                new Scan(bm, filePath, SCHEMA),
                r -> true
        );
        selection.open();

        assertNull(selection.next());
        selection.close();
    }

    @Test
    void closeAndReopen_filtersFromStart() throws Exception {
        List<GenericRecord> records = List.of(
                makeMovieRecord(SCHEMA, "tt0000001", "Alpha"),
                makeMovieRecord(SCHEMA, "tt0000002", "Beta"),
                makeMovieRecord(SCHEMA, "tt0000003", "Another")
        );
        writePages(bm, filePath, SCHEMA, records);

        Selection selection = new Selection(
                new Scan(bm, filePath, SCHEMA),
                r -> fromFixedBytes(r.getFieldBytes("title")).startsWith("A")
        );

        selection.open();
        selection.next();
        selection.close();

        selection.open();
        GenericRecord first = selection.next();
        selection.close();

        assertNotNull(first);
        assertEquals("Alpha", fromFixedBytes(first.getFieldBytes("title")));
    }
}
