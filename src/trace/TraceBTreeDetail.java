package trace;

/** Extra detail for B+ tree search and range scan events. */
public record TraceBTreeDetail(
        String indexFileId,
        Integer nodePageId,
        TraceBTreeNodeType nodeType,
        String key,
        String rangeStart,
        String rangeEnd) {

    public TraceBTreeDetail {
        if (nodePageId != null && nodePageId < 0) {
            throw new IllegalArgumentException("nodePageId must be non-negative");
        }
    }
}
