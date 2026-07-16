import buffer.BufferManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

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
 */
public class QueryEngine {

    private final BufferManager bm;
    private final int frameBudget;
    private final Semaphore admission;

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
        admission.acquire();
        try {
            return RunQuery.run(startRange, endRange, frameBudget, useIndex, bm, outputPath);
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
