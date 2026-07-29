import java.nio.file.Path;
import java.util.List;

/**
 * Self-checking benchmark for concurrent callers sharing one {@link QueryEngine}.
 *
 * <p>The measured interval starts when all client threads are ready and ends
 * after the last query returns. Sequential baselines, result comparison,
 * lifecycle validation, file deletion, and metric printing happen outside that
 * interval.
 */
public final class EngineBenchmark {

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
}
