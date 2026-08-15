package util.preprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import buffer.BufferManager;
import catalog.ImdbSchemas;
import catalog.TableEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        bufferManager.register(new TableEntry(database, ImdbSchemas.MOVIES));

        assertThrows(
                IOException.class,
                () -> PreProcessorUtils.loadTable(
                        bufferManager, csv.toString(), database, ImdbSchemas.MOVIES));
    }

    private GenericRecord loadFirstRecord(Path csv) throws Exception {
        String database = tempDir.resolve(csv.getFileName() + ".db").toString();
        BufferManager bufferManager = new BufferManager(2);
        bufferManager.register(new TableEntry(database, ImdbSchemas.MOVIES));
        PreProcessorUtils.loadTable(
                bufferManager, csv.toString(), database, ImdbSchemas.MOVIES);
        Page page = bufferManager.getPage(database, 0);
        GenericRecord record = (GenericRecord) new GenericPage(page, ImdbSchemas.MOVIES).getRecord(0);
        bufferManager.unpinPage(database, 0);
        return record;
    }

    private static String value(GenericRecord record, String field) {
        return RecordUtils.fromFixedBytes(record.getFieldBytes(field));
    }
}
