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
import storage.RawPage;

public class ScanTest {

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
        File tmp = File.createTempFile("scan_test", ".dat");
        tmp.deleteOnExit();
        filePath = tmp.getAbsolutePath();
        bm.register(new TableEntry(filePath, SCHEMA));
    }

    @AfterEach
    void teardown() throws Exception {
        new File(filePath).delete();
    }

    @Test
    void emptyFile_returnsNullImmediately() throws Exception {
        writePages(bm, filePath, SCHEMA, List.of());
        Scan scan = new Scan(bm, filePath, SCHEMA);
        scan.open();
        assertNull(scan.next());
        scan.close();
    }

    @Test
    void singleRecord_returnedThenNull() throws Exception {
        List<GenericRecord> records = List.of(makeMovieRecord(SCHEMA, "tt0000001", "The Movie"));
        writePages(bm, filePath, SCHEMA, records);

        Scan scan = new Scan(bm, filePath, SCHEMA);
        scan.open();

        GenericRecord r = scan.next();
        assertNotNull(r);
        assertEquals("tt0000001", fromFixedBytes(r.getFieldBytes("movieId")));
        assertEquals("The Movie", fromFixedBytes(r.getFieldBytes("title")));

        assertNull(scan.next());
        scan.close();
    }

    @Test
    void multipleRecords_allReturnedInOrder() throws Exception {
        List<GenericRecord> records = List.of(
                makeMovieRecord(SCHEMA, "tt0000001", "Alpha"),
                makeMovieRecord(SCHEMA, "tt0000002", "Beta"),
                makeMovieRecord(SCHEMA, "tt0000003", "Gamma")
        );
        writePages(bm, filePath, SCHEMA, records);

        Scan scan = new Scan(bm, filePath, SCHEMA);
        scan.open();

        List<String> titles = new ArrayList<>();
        GenericRecord r;
        while ((r = scan.next()) != null) {
            titles.add(fromFixedBytes(r.getFieldBytes("title")));
        }
        scan.close();

        assertEquals(List.of("Alpha", "Beta", "Gamma"), titles);
    }

    @Test
    void recordsSpanMultiplePages_allReturnedInOrder() throws Exception {
        // Fill more records than fit on a single page.
        int capacity = (RawPage.MAX_PAGE_LEN - 4) / (MOVIE_ID_SIZE + TITLE_SIZE);
        int totalRecords = capacity + 5; // spills onto a second page

        List<GenericRecord> records = new ArrayList<>();
        for (int i = 0; i < totalRecords; i++) {
            records.add(makeMovieRecord(SCHEMA, String.format("tt%07d", i), "Title" + i));
        }
        int numPages = writePages(bm, filePath, SCHEMA, records);
        assertTrue(numPages >= 2);

        Scan scan = new Scan(bm, filePath, SCHEMA);
        scan.open();

        int count = 0;
        while (scan.next() != null) count++;
        scan.close();

        assertEquals(totalRecords, count);
    }

    @Test
    void closeAndReopen_scansFromStart() throws Exception {
        List<GenericRecord> records = List.of(
                makeMovieRecord(SCHEMA, "tt0000001", "Alpha"),
                makeMovieRecord(SCHEMA, "tt0000002", "Beta")
        );
        writePages(bm, filePath, SCHEMA, records);

        Scan scan = new Scan(bm, filePath, SCHEMA);

        scan.open();
        scan.next(); // consume first
        scan.close();

        scan.open();
        GenericRecord first = scan.next();
        scan.close();

        assertNotNull(first);
        assertEquals("Alpha", fromFixedBytes(first.getFieldBytes("title")));
    }

    @Test
    void partialPageFill_onlyInsertedRecordsReturned() throws Exception {
        // Write exactly 2 records onto a page that could hold many more.
        List<GenericRecord> records = List.of(
                makeMovieRecord(SCHEMA, "tt0000001", "One"),
                makeMovieRecord(SCHEMA, "tt0000002", "Two")
        );
        writePages(bm, filePath, SCHEMA, records);

        Scan scan = new Scan(bm, filePath, SCHEMA);
        scan.open();

        List<String> results = new ArrayList<>();
        GenericRecord r;
        while ((r = scan.next()) != null) {
            results.add(fromFixedBytes(r.getFieldBytes("title")));
        }
        scan.close();

        assertEquals(List.of("One", "Two"), results);
    }
}
