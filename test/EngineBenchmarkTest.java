import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        ConcurrentQueryRanges.TitleRange earlyRange = ConcurrentQueryRanges.get(0);
        ConcurrentQueryRanges.TitleRange middleRange = ConcurrentQueryRanges.get(1);
        EngineBenchmark.Config config = new EngineBenchmark.Config(
                List.of(
                        new EngineBenchmark.Workload(
                                "early titles", earlyRange.start(), earlyRange.end()),
                        new EngineBenchmark.Workload(
                                "middle titles", middleRange.start(), middleRange.end())),
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

    @Test
    public void cliPersistsCompleteProtocolAfterQueryFilesAreCleanedUp() throws Exception {
        Path queryOutput = Files.createDirectory(outputDir.resolve("query-output"));
        Path workload = outputDir.resolve("workload.csv");
        Files.writeString(
                workload,
                "name,start_range,end_range\n"
                        + "early titles,carmencita,carmencita-0099\n"
                        + "middle titles,carmencita-0100,carmencita-0299\n");
        Path metrics = queryOutput.resolve("engine.metrics");

        Process process = startCli(workload, queryOutput, metrics);
        String stderr = new String(process.getErrorStream().readAllBytes());
        assertEquals(0, process.waitFor(), stderr);
        assertEquals("", stderr, "successful runs keep stderr diagnostic-only");

        List<String> lines = Files.readAllLines(metrics);
        assertEquals("REDB_ENGINE_PROTOCOL version=1", lines.get(0));
        assertEquals(4, lines.stream().filter(line -> line.startsWith("REDB_ENGINE_QUERY ")).count());
        String run = lines.get(lines.size() - 1);
        assertTrue(run.startsWith("REDB_ENGINE_RUN "));
        assertEquals(2, field(run, "makespans_ns").split(",").length);
        try (var files = Files.list(queryOutput)) {
            assertEquals(List.of(metrics), files.toList());
        }
    }

    @Test
    public void cliRefusesToOverwriteResultFile() throws Exception {
        Path queryOutput = Files.createDirectory(outputDir.resolve("overwrite-query-output"));
        Path workload = outputDir.resolve("overwrite-workload.csv");
        Files.writeString(
                workload,
                "name,start_range,end_range\nearly,carmencita,carmencita-0001\n");
        Path metrics = queryOutput.resolve("existing.metrics");
        Files.writeString(metrics, "keep me");

        Process process = startCli(workload, queryOutput, metrics);
        String stderr = new String(process.getErrorStream().readAllBytes());
        assertEquals(1, process.waitFor());
        assertTrue(stderr.contains(metrics.toString()));
        assertEquals("keep me", Files.readString(metrics));
    }

    private static Process startCli(Path workload, Path queryOutput, Path metrics)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("BenchmarkCli");
        command.addAll(List.of(
                "--workload", workload.toString(),
                "--buffer-size", "18",
                "--max-concurrent", "2",
                "--clients", "4",
                "--repetitions", "2",
                "--warmups", "1",
                "--output-dir", queryOutput.toString(),
                "--result-file", metrics.toString()));
        return new ProcessBuilder(command).start();
    }

    private static String field(String metric, String name) {
        String prefix = name + "=";
        return List.of(metric.split(" ")).stream()
                .filter(value -> value.startsWith(prefix))
                .findFirst()
                .orElseThrow()
                .substring(prefix.length());
    }
}
