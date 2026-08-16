package util.preprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import buffer.BufferManager;
import catalog.ImdbSchemas;
import catalog.TableEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.GenericPage;
import storage.GenericRecord;
import storage.Page;
import util.RecordUtils;

class PreProcessorUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void compactMovieRowRoundTripsBySchemaColumnOrder() throws Exception {
        Path csv = tempDir.resolve("title.csv");
        Files.writeString(
                csv,
                "movieId,title\n10001000,\"Café, Noir\"\n");

        GenericRecord record = loadFirstRecord(csv, ImdbSchemas.BENCHMARK_MOVIES);

        assertEquals("10001000", value(record, "movieId"));
        assertEquals("Café, Noir", value(record, "title"));
    }

    @Test
    void smallMovieRowKeepsExistingLayout() throws Exception {
        Path csv = tempDir.resolve("legacy-title.csv");
        Files.writeString(csv, "movieId,title\ntt0000001,Carmencita\n");

        GenericRecord record = loadFirstRecord(csv, ImdbSchemas.MOVIES);

        assertEquals("tt0000001", value(record, "movieId"));
        assertEquals("Carmencita", value(record, "title"));
    }

    @Test
    void reorderedHeaderIsRejected() throws Exception {
        Path csv = tempDir.resolve("bad-title.csv");
        Files.writeString(csv, "title,movieId\nCarmencita,tt0000001\n");
        String database = tempDir.resolve("bad.db").toString();
        BufferManager bufferManager = new BufferManager(2);
        bufferManager.register(new TableEntry(database, ImdbSchemas.MOVIES));

        assertThrows(
                IOException.class,
                () -> PreProcessorUtils.loadTable(
                        bufferManager, csv.toString(), database, ImdbSchemas.MOVIES));
    }

    private GenericRecord loadFirstRecord(Path csv, Map<String, Integer> schema) throws Exception {
        String database = tempDir.resolve(csv.getFileName() + ".db").toString();
        BufferManager bufferManager = new BufferManager(2);
        bufferManager.register(new TableEntry(database, schema));
        PreProcessorUtils.loadTable(bufferManager, csv.toString(), database, schema);
        Page page = bufferManager.getPage(database, 0);
        GenericRecord record = (GenericRecord) new GenericPage(page, schema).getRecord(0);
        bufferManager.unpinPage(database, 0);
        return record;
    }

    private static String value(GenericRecord record, String field) {
        return RecordUtils.fromFixedBytes(record.getFieldBytes(field));
    }
}
