package trace;

/** Stable record location rendered in trace details. */
public record TraceRecordRef(int pageId, int slotId) {

    public TraceRecordRef {
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId must be non-negative");
        }
        if (slotId < 0) {
            throw new IllegalArgumentException("slotId must be non-negative");
        }
    }
}
