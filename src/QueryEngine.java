import buffer.BufferManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Multi-query entry point over one shared {@link BufferManager}.
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
 *
 * <p>An optional per-query timeout bounds admission wait plus execution. A
 * query still queued at its deadline fails without running; a running query is
 * cancelled by interrupting its thread, which makes its next page pin (or
 * in-flight channel I/O) fail. Both surface as {@link QueryTimeoutException}.
 */
public class QueryEngine {

    /** Timings for one successfully completed query. */
    public record QueryOutcome(
            long resultCount,
            long admissionWaitNanos,
            long executionNanos) {
    }

    // One daemon thread fires every engine's deadlines; firing is just an interrupt.
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redb-query-deadlines");
                t.setDaemon(true);
                return t;
            });

    private final BufferManager bm;
    private final int frameBudget;
    private final Semaphore admission;
    private final Duration queryTimeout; // null: no limit

    /** An engine whose queries have no time limit. */
    public QueryEngine(int bufferSize, int maxConcurrentQueries) {
        this(bufferSize, maxConcurrentQueries, null);
    }

    /**
     * @param queryTimeout
     *            Limit on each query's admission wait plus execution, or null for
     *            no limit.
     */
    public QueryEngine(int bufferSize, int maxConcurrentQueries, Duration queryTimeout) {
        if (queryTimeout != null && (queryTimeout.isNegative() || queryTimeout.isZero())) {
            throw new IllegalArgumentException("queryTimeout must be positive, got: " + queryTimeout);
        }
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
        this.queryTimeout = queryTimeout;
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
     *
     * @throws QueryTimeoutException
     *             if the engine has a timeout and the query exceeds it.
     */
    public QueryOutcome runMeasuredQuery(
            String startRange,
            String endRange,
            boolean useIndex,
            Path outputPath) throws IOException, InterruptedException {
        long admissionStartNanos = System.nanoTime();
        if (queryTimeout == null) {
            admission.acquire();
        } else if (!admission.tryAcquire(queryTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new QueryTimeoutException(queryTimeout, false, null);
        }
        long executionStartNanos = System.nanoTime();
        try {
            long resultCount = queryTimeout == null
                    ? RunQuery.run(startRange, endRange, frameBudget, useIndex, bm, outputPath)
                    : runWithDeadline(startRange, endRange, useIndex, outputPath,
                            queryTimeout.toNanos() - (executionStartNanos - admissionStartNanos));
            long completedNanos = System.nanoTime();
            return new QueryOutcome(
                    resultCount,
                    executionStartNanos - admissionStartNanos,
                    completedNanos - executionStartNanos);
        } finally {
            admission.release();
        }
    }

    /**
     * Runs the query on the calling thread with a deadline that interrupts this
     * thread once {@code remainingNanos} elapse. RunQuery's cleanup runs with the
     * interrupt cleared, so a cancelled query still releases everything.
     */
    private long runWithDeadline(
            String startRange,
            String endRange,
            boolean useIndex,
            Path outputPath,
            long remainingNanos) throws IOException {
        if (remainingNanos <= 0) {
            throw new QueryTimeoutException(queryTimeout, true, null);
        }
        Deadline deadline = new Deadline(Thread.currentThread());
        ScheduledFuture<?> timer =
                DEADLINES.schedule(deadline::fire, remainingNanos, TimeUnit.NANOSECONDS);
        try {
            return RunQuery.run(startRange, endRange, frameBudget, useIndex, bm, outputPath);
        } catch (IOException | RuntimeException e) {
            if (deadline.disarm()) {
                throw new QueryTimeoutException(queryTimeout, true, e);
            }
            throw e;
        } finally {
            timer.cancel(false);
            // A deadline that fired after the query finished must not leak its
            // interrupt into the caller's thread.
            if (deadline.disarm()) {
                Thread.interrupted();
            }
        }
    }

    /**
     * Interrupts the worker unless the query has already finished. Disarming and
     * firing share one lock, so no interrupt can land after {@link #disarm}.
     */
    private static final class Deadline {
        private final Thread worker;
        private boolean armed = true;
        private boolean fired;

        Deadline(Thread worker) {
            this.worker = worker;
        }

        synchronized void fire() {
            if (armed) {
                fired = true;
                worker.interrupt();
            }
        }

        /** Stops future firing; returns whether the deadline already fired. */
        synchronized boolean disarm() {
            armed = false;
            return fired;
        }
    }

    // For testing only
    int getFrameBudget() {
        return frameBudget;
    }

    // For testing only
    Semaphore getAdmission() {
        return admission;
    }

    // For testing only
    BufferManager getBufferManager() {
        return bm;
    }
}
