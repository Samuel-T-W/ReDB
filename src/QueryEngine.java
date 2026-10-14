import buffer.BufferManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import storage.RawPage;

/**
 * Multi-query entry point over one shared {@link BufferManager}.
 *
 * <p>The one-argument constructor derives its admission limit from the pool
 * size and the 32 MiB per-query budget floor, admitting at least one query.
 * The two-argument constructor explicitly overrides that limit for tests and
 * benchmarks with tiny pools, retaining its 3-frame minimum per query.
 *
 * <p>Each admitted query runs with a fixed frame budget of
 * {@code bufferSize / maxConcurrentQueries}, and a semaphore admits at most
 * {@code maxConcurrentQueries} queries at a time (excess callers block until a
 * permit frees up; they are queued, never rejected). A query's worst-case
 * simultaneous pins are 2N BNL block pages plus one transient scan/index pin,
 * i.e. 2 * ((budget - 1) / 2) + 1 &lt;= budget, and the pin that would take a
 * query to its peak is only requested while it holds at most budget - 1 frames.
 * So under admission control the pool always has a free or evictable frame for
 * whichever query asks next, even when budgets exactly cover the pool.
 */
public class QueryEngine {

    // The 32 MiB floor sits between PostgreSQL's per-operator work_mem
    // (4 MB default, about 64 MB common) and DuckDB's per-thread guidance
    // (125 MB minimum, 1-4 GB recommended). Below it, BNL inner rescans
    // (ceil(P_outer / N) passes over the inner) dominate run time.
    // SAM-57 owns picking the operating point below the admission ceiling.
    public static final long MIN_QUERY_BUDGET_BYTES = 32L * 1024 * 1024;
    public static final int MIN_QUERY_BUDGET_FRAMES =
            (int) (MIN_QUERY_BUDGET_BYTES / RawPage.MAX_PAGE_LEN);

    /** Timings for one successfully completed query. */
    public record QueryOutcome(
            long resultCount,
            long admissionWaitNanos,
            long executionNanos) {
    }

    private final BufferManager bm;
    private final int frameBudget;
    private final Semaphore admission;

    /** Returns the pool-derived admission ceiling, with at least one permit. */
    public static int admissionCeiling(int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive, got: " + bufferSize);
        }
        return Math.max(1, bufferSize / MIN_QUERY_BUDGET_FRAMES);
    }

    public QueryEngine(int bufferSize) {
        this(bufferSize, admissionCeiling(bufferSize));
    }

    public QueryEngine(int bufferSize, int maxConcurrentQueries) {
        if (maxConcurrentQueries < 1) {
            throw new IllegalArgumentException(
                    "maxConcurrentQueries must be positive, got: " + maxConcurrentQueries);
        }
        int budget = bufferSize / maxConcurrentQueries;
        if (budget < 3) {
            throw new IllegalArgumentException(
                    "per-query frame budget must be at least 3 to run BNL join, got: "
                            + budget + " (bufferSize " + bufferSize
                            + " / maxConcurrentQueries " + maxConcurrentQueries + ")");
        }
        this.bm = new BufferManager(bufferSize);
        this.frameBudget = budget;
        this.admission = new Semaphore(maxConcurrentQueries);
        RunQuery.registerCatalog(bm);
    }

    /**
     * Runs one query, writing its result rows to {@code outputPath}. Blocks
     * until an admission permit is available; safe to call from many threads.
     *
     * @return the number of result rows written.
     */
    public long runQuery(String startRange, String endRange, boolean useIndex, Path outputPath)
            throws IOException, InterruptedException {
        return runMeasuredQuery(startRange, endRange, useIndex, outputPath).resultCount();
    }

    /**
     * Runs one query and separates time waiting for admission from time spent
     * executing after admission. Failed queries are rethrown and have no
     * successful outcome.
     */
    public QueryOutcome runMeasuredQuery(
            String startRange,
            String endRange,
            boolean useIndex,
            Path outputPath) throws IOException, InterruptedException {
        long admissionStartNanos = System.nanoTime();
        admission.acquire();
        long executionStartNanos = System.nanoTime();
        try {
            long resultCount =
                    RunQuery.run(startRange, endRange, frameBudget, useIndex, bm, outputPath);
            long completedNanos = System.nanoTime();
            return new QueryOutcome(
                    resultCount,
                    executionStartNanos - admissionStartNanos,
                    completedNanos - executionStartNanos);
        } finally {
            admission.release();
        }
    }

    // For testing only
    int getFrameBudget() {
        return frameBudget;
    }

    // For testing only
    BufferManager getBufferManager() {
        return bm;
    }
}
