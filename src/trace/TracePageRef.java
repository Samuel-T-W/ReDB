package trace;

/** Identifies a page in a table or index file. */
public record TracePageRef(String fileId, int pageId) {

    public TracePageRef {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must be non-blank");
        }
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId must be non-negative");
        }
    }
}
