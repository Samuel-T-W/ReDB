import buffer.BufferManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Self-checking benchmark for concurrent callers sharing one {@link QueryEngine}.
 *
 * <p>Each repetition is its own batch: every workload runs once, concurrently,
 * and the batch drains before the next repetition starts. A repetition is
 * therefore an independent sample of the same experiment rather than a longer
 * version of it, and each one reports its own makespan.
 *
 * <p>A measured interval starts when all client threads are ready and ends
 * after the last query in that batch returns. Sequential baselines, result
 * comparison, lifecycle validation, file deletion, and metric printing happen
 * outside those intervals.
 */
public final class EngineBenchmark {

    private static final Set<String> BASE_FILE_IDS =
            Set.of("movies.db", "workedon.db", "people.db", "title.idx");

    private static final int STATUS_SUCCESS = 0;
    private static final int STATUS_QUERY_FAILED = 1;
    private static final int STATUS_RESULT_MISMATCH = 2;

    /** Smallest per-query frame budget a BNL join can run in. */
    private static final int MIN_FRAME_BUDGET = 3;

    /** Loose guard on range bound length, replaced by a schema-derived limit in SAM-47. */
    private static final int MAX_RANGE_LENGTH = 100;

    private EngineBenchmark() {
    }

    /** One fixed-plan range query from the workload manifest. */
    public record Workload(String name, String startRange, String endRange) {

        public Workload {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("workload name must not be blank");
            }
            if (startRange == null || endRange == null) {
                throw new IllegalArgumentException("workload ranges must not be null");
            }
            if (startRange.length() > MAX_RANGE_LENGTH || endRange.length() > MAX_RANGE_LENGTH) {
                throw new IllegalArgumentException(
                        "workload ranges must be at most " + MAX_RANGE_LENGTH + " characters");
            }
        }
    }

    /** Complete configuration for one shared-engine benchmark run. */
    public record Config(
            List<Workload> workloads,
            int bufferSize,
            int maxConcurrent,
            int clients,
            int repetitions,
            int warmups,
            boolean useIndex,
            Path outputDir) {

        public Config {
            if (workloads == null) {
                throw new IllegalArgumentException("workloads must not be null");
            }
            workloads = List.copyOf(workloads);
            if (workloads.isEmpty()) {
                throw new IllegalArgumentException("workload must contain at least one query");
            }
            if (bufferSize < 1) {
                throw new IllegalArgumentException("buffer-size must be positive");
            }
            if (maxConcurrent < 1) {
                throw new IllegalArgumentException("max-concurrent must be positive");
            }
            int budget = frameBudgetOf(bufferSize, maxConcurrent);
            if (budget < MIN_FRAME_BUDGET) {
                throw new IllegalArgumentException(
                        "per-query frame budget must be at least " + MIN_FRAME_BUDGET
                                + " to run BNL join, got: " + budget
                                + " (buffer-size " + bufferSize
                                + " / max-concurrent " + maxConcurrent + ")");
            }
            if (clients < 1) {
                throw new IllegalArgumentException("clients must be positive");
            }
            if (repetitions < 1) {
                throw new IllegalArgumentException("repetitions must be positive");
            }
            if (warmups < 0) {
                throw new IllegalArgumentException("warmups must not be negative");
            }
            if (outputDir == null) {
                throw new IllegalArgumentException("output-dir must not be null");
            }
        }

        /**
         * Static so the compact constructor can derive the budget from its
         * parameters. An instance method would read fields that are not
         * assigned until the constructor returns.
         */
        private static int frameBudgetOf(int bufferSize, int maxConcurrent) {
            return bufferSize / maxConcurrent;
        }

        public int frameBudget() {
            return frameBudgetOf(bufferSize, maxConcurrent);
        }
    }

    /** Machine-reportable result for one measured query. */
    public record QueryMetric(
            int taskId,
            int workloadIndex,
            int repetition,
            int statusCode,
            long resultCount,
            long clientLatencyNanos,
            long admissionWaitNanos,
            long executionNanos,
            String failureDetail) {

        public boolean successful() {
            return statusCode == STATUS_SUCCESS;
        }
    }

    /**
     * Aggregate result and lifecycle checks for one benchmark run.
     *
     * <p>{@code makespansNanos} holds one entry per repetition, in order, so a
     * caller can take a median or a spread instead of trusting a single timing.
     */
    public record RunSummary(
            Config config,
            List<QueryMetric> queries,
            List<Long> makespansNanos,
            long readIOs,
            long writeIOs,
            int resultMismatches,
            int residualPins,
            boolean catalogClean,
            int residualBufferFileIds) {

        public RunSummary {
            queries = List.copyOf(queries);
            makespansNanos = List.copyOf(makespansNanos);
        }

        public int successfulQueries() {
            return (int) queries.stream().filter(QueryMetric::successful).count();
        }

        public int failedQueries() {
            return queries.size() - successfulQueries();
        }

        public boolean successful() {
            return failedQueries() == 0
                    && resultMismatches == 0
                    && residualPins == 0
                    && catalogClean
                    && residualBufferFileIds == 0;
        }
    }

    /** Runs the benchmark without printing, so orchestration is directly testable. */
    public static RunSummary run(Config config) throws Exception {
        Files.createDirectories(config.outputDir());
        List<Path> resultFiles = new ArrayList<>();
        Exception runFailure = null;
        try {
            Map<SequentialBaselines.Spec, List<String>> baselines =
                    computeBaselines(config, resultFiles);
            QueryEngine engine = new QueryEngine(config.bufferSize(), config.maxConcurrent());

            for (int warmup = 0; warmup < config.warmups(); warmup++) {
                List<TaskSpec> warmupTasks = createTasks(config, warmup, "warmup", resultFiles);
                BatchResult warmupBatch = executeBatch(engine, warmupTasks, config.clients(), false);
                ValidationResult warmupValidation = validate(warmupBatch.tasks(), baselines);
                QueryMetric failedWarmup = warmupValidation.queries().stream()
                        .filter(query -> !query.successful())
                        .findFirst()
                        .orElse(null);
                if (failedWarmup != null) {
                    throw new IllegalStateException(
                            "warmup query failed validation: " + failedWarmup.failureDetail());
                }
            }

            List<QueryMetric> queries = new ArrayList<>();
            List<Long> makespansNanos = new ArrayList<>();
            int mismatches = 0;
            for (int repetition = 0; repetition < config.repetitions(); repetition++) {
                List<TaskSpec> measuredTasks =
                        createTasks(config, repetition, "measured", resultFiles);
                BatchResult measuredBatch =
                        executeBatch(engine, measuredTasks, config.clients(), repetition == 0);
                ValidationResult validation = validate(measuredBatch.tasks(), baselines);
                queries.addAll(validation.queries());
                mismatches += validation.mismatches();
                makespansNanos.add(measuredBatch.makespanNanos());
            }

            BufferManager shared = engine.getBufferManager();
            int residualPins = shared.getTotalPinCount();
            boolean catalogClean = BASE_FILE_IDS.equals(shared.catalogFileNames());
            int residualBufferFileIds = (int) shared.bufferedFileIds().stream()
                    .filter(fileId -> !BASE_FILE_IDS.contains(fileId))
                    .count();

            return new RunSummary(
                    config,
                    queries,
                    makespansNanos,
                    shared.getReadIOCount(),
                    shared.getWriteIOCount(),
                    mismatches,
                    residualPins,
                    catalogClean,
                    residualBufferFileIds);
        } catch (Exception failure) {
            runFailure = failure;
            throw failure;
        } finally {
            try {
                deleteResultFiles(resultFiles);
            } catch (IOException cleanupFailure) {
                if (runFailure == null) {
                    throw cleanupFailure;
                }
                runFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static Map<SequentialBaselines.Spec, List<String>> computeBaselines(
            Config config, List<Path> resultFiles) throws IOException {
        Map<SequentialBaselines.Spec, List<String>> baselines = new LinkedHashMap<>();
        for (Workload workload : config.workloads()) {
            SequentialBaselines.Spec spec = new SequentialBaselines.Spec(
                    workload.startRange(), workload.endRange(), config.useIndex());
            if (baselines.containsKey(spec)) {
                continue;
            }

            Path outputPath = createResultFile(config.outputDir(), "baseline", resultFiles);
            List<String> baseline = SequentialBaselines.compute(
                    spec,
                    config.frameBudget(),
                    config.frameBudget(),
                    outputPath);
            if (baseline.isEmpty()) {
                throw new IllegalStateException(
                        "baseline produced no rows for workload " + workload.name());
            }
            baselines.put(spec, baseline);
        }
        return baselines;
    }

    /** Builds the tasks for one batch: every workload once, run concurrently. */
    private static List<TaskSpec> createTasks(
            Config config,
            int repetition,
            String phase,
            List<Path> resultFiles) throws IOException {
        int workloadCount = config.workloads().size();
        List<TaskSpec> tasks = new ArrayList<>();
        for (int workloadIndex = 0; workloadIndex < workloadCount; workloadIndex++) {
            Path outputPath = createResultFile(config.outputDir(), phase, resultFiles);
            tasks.add(new TaskSpec(
                    repetition * workloadCount + workloadIndex,
                    workloadIndex,
                    repetition,
                    outputPath,
                    config.workloads().get(workloadIndex),
                    config.useIndex()));
        }
        return tasks;
    }

    private static BatchResult executeBatch(
            QueryEngine engine,
            List<TaskSpec> tasks,
            int clients,
            boolean resetIOCounts) throws Exception {
        TaskExecution[] results = new TaskExecution[tasks.size()];
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(clients);
        List<Future<Void>> workers = new ArrayList<>();
        try {
            for (int workerIndex = 0; workerIndex < clients; workerIndex++) {
                int assignedWorker = workerIndex;
                workers.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int taskIndex = assignedWorker;
                            taskIndex < tasks.size();
                            taskIndex += clients) {
                        TaskSpec task = tasks.get(taskIndex);
                        results[taskIndex] = executeTask(engine, task);
                    }
                    return null;
                }));
            }

            ready.await();
            if (resetIOCounts) {
                engine.getBufferManager().resetIOCounts();
            }
            long startNanos = System.nanoTime();
            start.countDown();
            waitForWorkers(workers);
            long makespanNanos = System.nanoTime() - startNanos;
            return new BatchResult(List.of(results), makespanNanos);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static TaskExecution executeTask(QueryEngine engine, TaskSpec task) {
        try {
            QueryEngine.QueryOutcome outcome = engine.runMeasuredQuery(
                    task.workload().startRange(),
                    task.workload().endRange(),
                    task.useIndex(),
                    task.outputPath());
            return new TaskExecution(task, outcome, null);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new TaskExecution(task, null, failure);
        }
    }

    private static void waitForWorkers(List<Future<Void>> workers) throws Exception {
        for (Future<Void> worker : workers) {
            try {
                worker.get();
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new RuntimeException(cause);
            }
        }
    }

    private static ValidationResult validate(
            List<TaskExecution> executions,
            Map<SequentialBaselines.Spec, List<String>> baselines) {
        List<QueryMetric> queries = new ArrayList<>();
        int mismatches = 0;
        for (TaskExecution execution : executions) {
            TaskSpec task = execution.task();
            if (execution.failure() != null) {
                queries.add(failedMetric(task, STATUS_QUERY_FAILED, execution.failure().toString()));
                continue;
            }

            QueryEngine.QueryOutcome outcome = execution.outcome();
            try {
                List<String> actual = SequentialBaselines.sortedRows(task.outputPath());
                List<String> expected = baselines.get(new SequentialBaselines.Spec(
                        task.workload().startRange(),
                        task.workload().endRange(),
                        task.useIndex()));
                if (outcome.resultCount() != actual.size() || !expected.equals(actual)) {
                    mismatches++;
                    queries.add(new QueryMetric(
                            task.taskId(),
                            task.workloadIndex(),
                            task.repetition(),
                            STATUS_RESULT_MISMATCH,
                            outcome.resultCount(),
                            outcome.admissionWaitNanos() + outcome.executionNanos(),
                            outcome.admissionWaitNanos(),
                            outcome.executionNanos(),
                            "result count or rows differ from sequential baseline"));
                    continue;
                }

                queries.add(new QueryMetric(
                        task.taskId(),
                        task.workloadIndex(),
                        task.repetition(),
                        STATUS_SUCCESS,
                        outcome.resultCount(),
                        outcome.admissionWaitNanos() + outcome.executionNanos(),
                        outcome.admissionWaitNanos(),
                        outcome.executionNanos(),
                        ""));
            } catch (IOException validationFailure) {
                mismatches++;
                queries.add(new QueryMetric(
                        task.taskId(),
                        task.workloadIndex(),
                        task.repetition(),
                        STATUS_RESULT_MISMATCH,
                        outcome.resultCount(),
                        outcome.admissionWaitNanos() + outcome.executionNanos(),
                        outcome.admissionWaitNanos(),
                        outcome.executionNanos(),
                        "could not validate output: " + validationFailure));
            }
        }
        return new ValidationResult(queries, mismatches);
    }

    private static QueryMetric failedMetric(TaskSpec task, int statusCode, String failureDetail) {
        return new QueryMetric(
                task.taskId(),
                task.workloadIndex(),
                task.repetition(),
                statusCode,
                -1,
                -1,
                -1,
                -1,
                failureDetail);
    }

    private static Path createResultFile(
            Path outputDir, String phase, List<Path> resultFiles) throws IOException {
        Path outputPath = Files.createTempFile(
                outputDir, "redb-engine-benchmark-" + phase + "-", ".csv");
        resultFiles.add(outputPath);
        return outputPath;
    }

    private static void deleteResultFiles(List<Path> resultFiles) throws IOException {
        IOException failure = null;
        for (Path resultFile : resultFiles) {
            try {
                Files.deleteIfExists(resultFile);
            } catch (IOException deleteFailure) {
                if (failure == null) {
                    failure = new IOException("could not delete benchmark result files");
                }
                failure.addSuppressed(deleteFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record TaskSpec(
            int taskId,
            int workloadIndex,
            int repetition,
            Path outputPath,
            Workload workload,
            boolean useIndex) {
    }

    private record TaskExecution(
            TaskSpec task, QueryEngine.QueryOutcome outcome, Exception failure) {
    }

    private record BatchResult(List<TaskExecution> tasks, long makespanNanos) {
    }

    private record ValidationResult(List<QueryMetric> queries, int mismatches) {
    }
}
