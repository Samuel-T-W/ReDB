import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import catalog.ImdbSchemas;
import org.junit.jupiter.api.Test;

class PreProcessorDatasetTest {

    @Test
    void smallDatasetKeepsExistingInputsAndOutputs() {
        PreProcessor.Dataset dataset = PreProcessor.Dataset.SMALL;

        assertEquals("data/title.csv", dataset.moviesCsv);
        assertEquals("data/workedon.csv", dataset.workedOnCsv);
        assertEquals("data/name.csv", dataset.peopleCsv);
        assertEquals("movies.db", dataset.moviesDb);
        assertEquals("workedon.db", dataset.workedOnDb);
        assertEquals("people.db", dataset.peopleDb);
        assertEquals("title.idx", dataset.titleIndex);
        assertEquals(ImdbSchemas.MOVIES, dataset.moviesSchema);
        assertEquals(ImdbSchemas.WORKED_ON, dataset.workedOnSchema);
        assertEquals(ImdbSchemas.PEOPLE, dataset.peopleSchema);
    }

    @Test
    void fullDatasetUsesConvertedInputsAndSeparateOutputs() {
        PreProcessor.Dataset dataset = PreProcessor.Dataset.FULL;

        assertEquals("data/imdb-benchmark/title.csv", dataset.moviesCsv);
        assertEquals("data/imdb-benchmark/workedon.csv", dataset.workedOnCsv);
        assertEquals("data/imdb-benchmark/name.csv", dataset.peopleCsv);
        assertEquals("movies-full.db", dataset.moviesDb);
        assertEquals("workedon-full.db", dataset.workedOnDb);
        assertEquals("people-full.db", dataset.peopleDb);
        assertNull(dataset.titleIndex);
        assertEquals(ImdbSchemas.BENCHMARK_MOVIES, dataset.moviesSchema);
        assertEquals(ImdbSchemas.BENCHMARK_WORKED_ON, dataset.workedOnSchema);
        assertEquals(ImdbSchemas.BENCHMARK_PEOPLE, dataset.peopleSchema);
    }

    @Test
    void datasetNameMustBeKnown() {
        assertEquals(PreProcessor.Dataset.SMALL, PreProcessor.Dataset.parse("small"));
        assertEquals(PreProcessor.Dataset.FULL, PreProcessor.Dataset.parse("full"));
        assertThrows(IllegalArgumentException.class, () -> PreProcessor.Dataset.parse("large"));
    }
}
