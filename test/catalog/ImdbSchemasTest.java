package catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import storage.RawPage;

class ImdbSchemasTest {

    @Test
    void schemasFollowConverterColumnOrder() {
        assertEquals(
                List.of(
                        "movieId", "title", "startYear", "endYear", "isAdult",
                        "originalTitle", "titleType", "runtimeMinutes", "genres"),
                List.copyOf(ImdbSchemas.MOVIES.keySet()));
        assertEquals(
                List.of("movieId", "personId", "category", "ordering", "job"),
                List.copyOf(ImdbSchemas.WORKED_ON.keySet()));
        assertEquals(
                List.of("personId", "name", "birthYear", "deathYear", "primaryProfession"),
                List.copyOf(ImdbSchemas.PEOPLE.keySet()));
    }

    @Test
    void legacyColumnsRemainPrefixes() {
        assertEquals(List.of("movieId", "title"), firstFields(ImdbSchemas.MOVIES, 2));
        assertEquals(
                List.of("movieId", "personId", "category"),
                firstFields(ImdbSchemas.WORKED_ON, 3));
        assertEquals(List.of("personId", "name"), firstFields(ImdbSchemas.PEOPLE, 2));
    }

    @Test
    void everyRecordFitsOnOnePage() {
        assertTrue(recordSize(ImdbSchemas.MOVIES) <= RawPage.MAX_PAGE_LEN - Integer.BYTES);
        assertTrue(recordSize(ImdbSchemas.WORKED_ON) <= RawPage.MAX_PAGE_LEN - Integer.BYTES);
        assertTrue(recordSize(ImdbSchemas.PEOPLE) <= RawPage.MAX_PAGE_LEN - Integer.BYTES);
    }

    private static List<String> firstFields(Map<String, Integer> schema, int count) {
        return schema.keySet().stream().limit(count).toList();
    }

    private static int recordSize(Map<String, Integer> schema) {
        return schema.values().stream().mapToInt(Integer::intValue).sum();
    }
}
