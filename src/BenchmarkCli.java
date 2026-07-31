import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class BenchmarkCli {

    private BenchmarkCli() {
    }

    static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            printUsage(System.err);
            return;
        }
        try {
            EngineBenchmark.Config config = parseConfig(args);
            EngineBenchmark.RunSummary summary = EngineBenchmark.run(config);
            printMetrics(summary, System.err);
            if (!summary.successful()) {
                System.exit(1);
            }
        } catch (Exception failure) {
            System.err.println("EngineBenchmark failed: " + failure.getMessage());
            System.exit(1);
        }
    }

    static void printMetrics(EngineBenchmark.RunSummary summary, PrintStream out) {
        for (EngineBenchmark.QueryMetric query : summary.queries()) {
            out.printf(
                    "REDB_ENGINE_QUERY task_id=%d workload_index=%d repetition=%d "
                            + "status_code=%d result_count=%d client_latency_ns=%d "
                            + "admission_wait_ns=%d execution_ns=%d%n",
                    query.taskId(),
                    query.workloadIndex(),
                    query.repetition(),
                    query.statusCode(),
                    query.resultCount(),
                    query.clientLatencyNanos(),
                    query.admissionWaitNanos(),
                    query.executionNanos());
            if (!query.successful()) {
                out.printf(
                        "EngineBenchmark task %d failed: %s%n",
                        query.taskId(), query.failureDetail());
            }
        }

        EngineBenchmark.Config config = summary.config();
        out.printf(
                "REDB_ENGINE_RUN buffer_size=%d max_concurrent=%d clients=%d "
                        + "frame_budget=%d use_index=%d workloads=%d warmups=%d "
                        + "repetitions=%d queries=%d "
                        + "successful=%d failed=%d makespans_ns=%s read_ios=%d write_ios=%d "
                        + "result_mismatches=%d residual_pins=%d catalog_clean=%d "
                        + "residual_buffer_file_ids=%d%n",
                config.bufferSize(),
                config.maxConcurrent(),
                config.clients(),
                config.frameBudget(),
                config.useIndex() ? 1 : 0,
                config.workloads().size(),
                config.warmups(),
                config.repetitions(),
                summary.queries().size(),
                summary.successfulQueries(),
                summary.failedQueries(),
                summary.makespansNanos().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(",")),
                summary.readIOs(),
                summary.writeIOs(),
                summary.resultMismatches(),
                summary.residualPins(),
                summary.catalogClean() ? 1 : 0,
                summary.residualBufferFileIds());
    }

    private static EngineBenchmark.Config parseConfig(String[] args) throws IOException {
        Map<String, String> values = new HashMap<>();
        boolean useIndex = false;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if ("--index".equals(argument)) {
                if (useIndex) {
                    throw new IllegalArgumentException("duplicate option: --index");
                }
                useIndex = true;
                continue;
            }
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + argument);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + argument);
            }
            String previous = values.put(argument, args[++i]);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate option: " + argument);
            }
        }

        Set<String> knownOptions = Set.of(
                "--workload",
                "--buffer-size",
                "--max-concurrent",
                "--clients",
                "--repetitions",
                "--warmups",
                "--output-dir");
        for (String option : values.keySet()) {
            if (!knownOptions.contains(option)) {
                throw new IllegalArgumentException("unknown option: " + option);
            }
        }

        Path workloadPath = Path.of(required(values, "--workload"));
        return new EngineBenchmark.Config(
                readWorkloads(workloadPath),
                parseInteger(values, "--buffer-size"),
                parseInteger(values, "--max-concurrent"),
                parseInteger(values, "--clients"),
                parseInteger(values, "--repetitions"),
                parseInteger(values, "--warmups"),
                useIndex,
                Path.of(required(values, "--output-dir")));
    }

    private static String required(Map<String, String> values, String option) {
        String value = values.get(option);
        if (value == null) {
            throw new IllegalArgumentException("missing required option: " + option);
        }
        return value;
    }

    private static int parseInteger(Map<String, String> values, String option) {
        String value = required(values, option);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(option + " must be an integer, got: " + value);
        }
    }

    private static List<EngineBenchmark.Workload> readWorkloads(Path workloadPath)
            throws IOException {
        List<String> lines = Files.readAllLines(workloadPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("workload CSV is empty: " + workloadPath);
        }

        List<String> header = parseCsvLine(lines.get(0));
        if (!header.equals(List.of("name", "start_range", "end_range"))) {
            throw new IllegalArgumentException(
                    "workload CSV must have header name,start_range,end_range");
        }

        List<EngineBenchmark.Workload> workloads = new ArrayList<>();
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1);
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = parseCsvLine(line);
            if (fields.size() != 3) {
                throw new IllegalArgumentException(
                        "workload CSV line " + lineNumber + " must have exactly three fields");
            }
            workloads.add(new EngineBenchmark.Workload(
                    fields.get(0), fields.get(1), fields.get(2)));
        }
        return workloads;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("unterminated quoted field in workload CSV");
        }
        fields.add(field.toString());
        return fields;
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: java -cp target/classes EngineBenchmark \\");
        out.println("  --workload <name,start_range,end_range CSV> \\");
        out.println("  --buffer-size <frames> --max-concurrent <permits> \\");
        out.println("  --clients <threads> --repetitions <count> --warmups <count> \\");
        out.println("  [--index] --output-dir <result-file directory>");
    }
}
