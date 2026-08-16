package catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.junit.jupiter.api.Test;
import storage.RawPage;

class ImdbSchemasTest {

    @Test
    void smallSchemasKeepExistingPhysicalLayout() {
        assertEquals(Map.of("movieId", 9, "title", 30), ImdbSchemas.MOVIES);
        assertEquals(
                Map.of("movieId", 9, "personId", 10, "category", 20),
                ImdbSchemas.WORKED_ON);
        assertEquals(Map.of("personId", 10, "name", 105), ImdbSchemas.PEOPLE);
    }

    @Test
    void compactSchemasFitSnapshotHeapsBelowTenGibibytes() {
        assertEquals(Map.of("movieId", 8, "title", 482), ImdbSchemas.BENCHMARK_MOVIES);
        assertEquals(
                Map.of("movieId", 8, "personId", 8, "category", 1),
                ImdbSchemas.BENCHMARK_WORKED_ON);
        assertEquals(Map.of("personId", 8, "name", 105), ImdbSchemas.BENCHMARK_PEOPLE);
        assertEquals("6", ImdbSchemas.BENCHMARK_DIRECTOR);

        long pages = pageCount(12_717_779, ImdbSchemas.BENCHMARK_MOVIES)
                + pageCount(101_214_175, ImdbSchemas.BENCHMARK_WORKED_ON)
                + pageCount(15_576_470, ImdbSchemas.BENCHMARK_PEOPLE);
        assertEquals(10_011_152_384L, pages * RawPage.MAX_PAGE_LEN);
    }

    private static int recordSize(Map<String, Integer> schema) {
        return schema.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static long pageCount(long rows, Map<String, Integer> schema) {
        int rowsPerPage = (RawPage.MAX_PAGE_LEN - Integer.BYTES) / recordSize(schema);
        return (rows + rowsPerPage - 1) / rowsPerPage;
    }
}
