package trace;

import org.jspecify.annotations.Nullable;

/** Extra detail for B+ tree search and range scan events. */
public record TraceBTreeDetail(
        @Nullable String indexFileId,
        @Nullable Integer nodePageId,
        @Nullable TraceBTreeNodeType nodeType,
        @Nullable String key,
        @Nullable String rangeStart,
        @Nullable String rangeEnd) {

    public TraceBTreeDetail {
        if (nodePageId != null && nodePageId < 0) {
            throw new IllegalArgumentException("nodePageId must be non-negative");
        }
    }
}
