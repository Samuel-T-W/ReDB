import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end orchestration test for the one-JVM shared-engine benchmark. */
public class EngineBenchmarkTest {

    @TempDir
    Path outputDir;

    @BeforeAll
    static void loadTables() throws IOException {
        SyntheticQueryFixtures.load();
    }

    @AfterAll
    static void deleteTables() {
        for (String file : new String[]{"movies.db", "workedon.db", "people.db", "title.idx"}) {
            new File(file).delete();
        }
    }

    @Test
    public void oversubscribedClientsProduceValidatedMetricsAndCleanUp() throws Exception {
        EngineBenchmark.Config config = new EngineBenchmark.Config(
                List.of(
                        new EngineBenchmark.Workload(
                                "early titles", "carmencita", "carmencita-0099"),
                        new EngineBenchmark.Workload(
                                "middle titles", "carmencita-0100", "carmencita-0299")),
                18, // bufferSize
                2, // maxConcurrent
                4, // clients
                2, // repetitions
                1, // warmups
                false, // useIndex
                outputDir);

        EngineBenchmark.RunSummary summary = EngineBenchmark.run(config);

        assertTrue(summary.successful());
        assertEquals(4, summary.queries().size());
        assertEquals(4, summary.successfulQueries());
        assertEquals(0, summary.failedQueries());
        assertEquals(0, summary.resultMismatches());
        assertEquals(0, summary.residualPins());
        assertTrue(summary.catalogClean());
        assertEquals(0, summary.residualBufferFileIds());
        assertEquals(9, summary.config().frameBudget());
        assertEquals(2, summary.makespansNanos().size(), "one makespan per repetition");
        for (long makespanNanos : summary.makespansNanos()) {
            assertTrue(makespanNanos >= 0);
        }

        for (EngineBenchmark.QueryMetric query : summary.queries()) {
            assertTrue(query.resultCount() > 0);
            assertTrue(query.clientLatencyNanos() >= 0);
            assertTrue(query.admissionWaitNanos() >= 0);
            assertTrue(query.executionNanos() >= 0);
            assertEquals(
                    query.clientLatencyNanos(),
                    query.admissionWaitNanos() + query.executionNanos());
        }

        try (var files = Files.list(outputDir)) {
            assertFalse(files.findAny().isPresent(), "benchmark result files must be deleted");
        }
    }
}
