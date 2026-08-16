package util.preprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import buffer.BufferManager;
import catalog.TableEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.GenericPage;
import storage.GenericRecord;
import storage.Page;
import util.RecordUtils;

class PreProcessorUtilsTest {

    private static final Map<String, Integer> MOVIES_SCHEMA;

    static {
        Map<String, Integer> movies = new LinkedHashMap<>();
        movies.put("movieId", 12);
        movies.put("title", 512);
        movies.put("startYear", 4);
        movies.put("endYear", 4);
        movies.put("isAdult", 1);
        movies.put("originalTitle", 512);
        movies.put("titleType", 16);
        movies.put("runtimeMinutes", 8);
        movies.put("genres", 64);
        MOVIES_SCHEMA = Collections.unmodifiableMap(movies);
    }

    @TempDir
    Path tempDir;

    @Test
    void fullMovieRowRoundTripsBySchemaColumnOrder() throws Exception {
        Path csv = tempDir.resolve("title.csv");
        Files.writeString(
                csv,
                "movieId,title,startYear,endYear,isAdult,originalTitle,titleType,runtimeMinutes,genres\n"
                        + "tt10001000,\"Café, Noir\",2024,,0,Café Noir,movie,95,\"Drama,Noir\"\n");

        GenericRecord record = loadFirstRecord(csv);

        assertEquals("tt10001000", value(record, "movieId"));
        assertEquals("Café, Noir", value(record, "title"));
        assertEquals("2024", value(record, "startYear"));
        assertEquals("", value(record, "endYear"));
        assertEquals("Drama,Noir", value(record, "genres"));
    }

    @Test
    void legacyHeaderLeavesAppendedFieldsEmpty() throws Exception {
        Path csv = tempDir.resolve("legacy-title.csv");
        Files.writeString(csv, "movieId,title\ntt0000001,Carmencita\n");

        GenericRecord record = loadFirstRecord(csv);

        assertEquals("tt0000001", value(record, "movieId"));
        assertEquals("Carmencita", value(record, "title"));
        assertEquals("", value(record, "startYear"));
        assertEquals("", value(record, "genres"));
    }

    @Test
    void reorderedHeaderIsRejected() throws Exception {
        Path csv = tempDir.resolve("bad-title.csv");
        Files.writeString(csv, "title,movieId\nCarmencita,tt0000001\n");
        String database = tempDir.resolve("bad.db").toString();
        BufferManager bufferManager = new BufferManager(2);
        bufferManager.register(new TableEntry(database, MOVIES_SCHEMA));

        assertThrows(
                IOException.class,
                () -> PreProcessorUtils.loadTable(
                        bufferManager, csv.toString(), database, MOVIES_SCHEMA));
    }

    @Test
    void overlongRowIsSkippedAndReportedWithoutFailingTheLoad() throws Exception {
        Map<String, Integer> narrow = new LinkedHashMap<>();
        narrow.put("movieId", 9);
        narrow.put("title", 10);

        Path csv = tempDir.resolve("narrow-title.csv");
        Files.writeString(
                csv,
                "movieId,title\n"
                        + "tt0000001,Carmencita\n"
                        + "tt0000002,A Title Too Long To Fit\n"
                        + "tt0000003,Le Clown\n");
        String database = tempDir.resolve("narrow.db").toString();
        BufferManager bufferManager = new BufferManager(2);
        bufferManager.register(new TableEntry(database, narrow));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            PreProcessorUtils.loadTable(bufferManager, csv.toString(), database, narrow);
        } finally {
            System.setErr(originalErr);
        }

        Page page = bufferManager.getPage(database, 0);
        GenericPage loaded = new GenericPage(page, narrow);
        assertEquals(2, recordCount(page));
        assertEquals("tt0000001", value((GenericRecord) loaded.getRecord(0), "movieId"));
        assertEquals("tt0000003", value((GenericRecord) loaded.getRecord(1), "movieId"));
        bufferManager.unpinPage(database, 0);

        String warnings = captured.toString(StandardCharsets.UTF_8);
        assertTrue(warnings.contains("narrow-title.csv:3 skipped"), warnings);
        assertTrue(warnings.contains("title requires 23 UTF-8 bytes but field allows 10"), warnings);
        assertTrue(warnings.contains("skipped 1 row(s)"), warnings);
        assertTrue(warnings.contains("2 row(s) loaded"), warnings);
    }

    private static int recordCount(Page page) {
        return ByteBuffer.wrap(page.getByteArray()).getInt(0);
    }

    private GenericRecord loadFirstRecord(Path csv) throws Exception {
        String database = tempDir.resolve(csv.getFileName() + ".db").toString();
        BufferManager bufferManager = new BufferManager(2);
        bufferManager.register(new TableEntry(database, MOVIES_SCHEMA));
        PreProcessorUtils.loadTable(
                bufferManager, csv.toString(), database, MOVIES_SCHEMA);
        Page page = bufferManager.getPage(database, 0);
        GenericRecord record = (GenericRecord) new GenericPage(page, MOVIES_SCHEMA).getRecord(0);
        bufferManager.unpinPage(database, 0);
        return record;
    }

    private static String value(GenericRecord record, String field) {
        return RecordUtils.fromFixedBytes(record.getFieldBytes(field));
    }
}
