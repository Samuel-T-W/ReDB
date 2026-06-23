package trace;

/** Snapshot of a buffer frame relevant to a buffer event. */
public record TraceFrameRef(int frameId, Boolean dirty, Integer pinCount, TracePageRef evictedPage) {

    public TraceFrameRef {
        if (frameId < 0) {
            throw new IllegalArgumentException("frameId must be non-negative");
        }
        if (pinCount != null && pinCount < 0) {
            throw new IllegalArgumentException("pinCount must be non-negative");
        }
    }

    public TraceFrameRef(int frameId) {
        this(frameId, null, null, null);
    }
}
