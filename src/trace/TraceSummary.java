package trace;

/** Aggregate counters for a completed query trace. */
public record TraceSummary(
        long pagesRead,
        long bufferHits,
        long bufferMisses,
        long evictions,
        long recordsExamined,
        long recordsEmitted,
        long operatorNextCalls,
        long btreeNodeVisits) {

    public TraceSummary {
        requireNonNegative(pagesRead, "pagesRead");
        requireNonNegative(bufferHits, "bufferHits");
        requireNonNegative(bufferMisses, "bufferMisses");
        requireNonNegative(evictions, "evictions");
        requireNonNegative(recordsExamined, "recordsExamined");
        requireNonNegative(recordsEmitted, "recordsEmitted");
        requireNonNegative(operatorNextCalls, "operatorNextCalls");
        requireNonNegative(btreeNodeVisits, "btreeNodeVisits");
    }

    private static void requireNonNegative(long value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
    }
}
