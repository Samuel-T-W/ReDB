package trace;

import org.jspecify.annotations.Nullable;

/** Metadata for one relation participating in the traced run. */
public record TraceTable(String fileId, int recordSize, @Nullable Long recordCount) {

    public TraceTable {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must be non-blank");
        }
        if (recordSize < 1) {
            throw new IllegalArgumentException("recordSize must be positive");
        }
        if (recordCount != null && recordCount < 0) {
            throw new IllegalArgumentException("recordCount must be non-negative");
        }
    }

    public TraceTable(String fileId, int recordSize) {
        this(fileId, recordSize, null);
    }
}
