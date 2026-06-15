package util;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

/**
 * Engine instrumentation, kept separate from the normal run path in Main.
 *
 * Emits one machine-parseable line to stderr (stdout is reserved for the query's
 * CSV results). The benchmark harness parses it back out via the REDB_METRICS
 * prefix in benchmark/metrics.py.
 */
public final class Metrics {

    private Metrics() {
    }

    /** Print one REDB_METRICS line describing the just-completed query run. */
    public static void report(long elapsedNanos, long resultCount) {
        // Heap snapshot taken after the query; reflects live retained memory at this point.
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        // Total CPU time consumed by this process (may be unavailable on some platforms).
        long cpuNanos = ProcessHandle.current()
                .info()
                .totalCpuDuration()
                .map(duration -> duration.toNanos())
                .orElse(-1L);
        // Machine-parseable line on stderr, separate from query_results.csv.
        System.err.printf(
                "REDB_METRICS elapsed_ns=%d result_count=%d "
                        + "heap_used_bytes=%d heap_committed_bytes=%d "
                        + "heap_pool_peak_sum_bytes=%d cpu_ns=%d%n",
                elapsedNanos,
                resultCount,
                heap.getUsed(),
                heap.getCommitted(),
                heapPoolPeakSumBytes(),
                cpuNanos);
    }

    // Sum of peak usage across all heap memory pools (e.g. Eden, Survivor, Old Gen).
    // Approximates the high-water mark of heap demand over the whole run, unlike the
    // post-query snapshot which only captures memory still live at the end.
    private static long heapPoolPeakSumBytes() {
        long peakBytes = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                MemoryUsage peak = pool.getPeakUsage();
                if (peak != null) {
                    peakBytes += peak.getUsed();
                }
            }
        }
        return peakBytes;
    }
}
