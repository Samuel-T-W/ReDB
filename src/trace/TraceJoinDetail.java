package trace;

/** Extra detail for block nested loop join events. */
public record TraceJoinDetail(Integer blockId, TraceJoinSide side, String key, Integer matches) {

    public TraceJoinDetail {
        if (blockId != null && blockId < 0) {
            throw new IllegalArgumentException("blockId must be non-negative");
        }
        if (matches != null && matches < 0) {
            throw new IllegalArgumentException("matches must be non-negative");
        }
    }
}
