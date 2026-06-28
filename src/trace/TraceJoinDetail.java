package trace;

import org.jspecify.annotations.Nullable;

/** Extra detail for block nested loop join events. */
public record TraceJoinDetail(
        @Nullable Integer blockId, @Nullable TraceJoinSide side, @Nullable String key, @Nullable Integer matches) {

    public TraceJoinDetail {
        if (blockId != null && blockId < 0) {
            throw new IllegalArgumentException("blockId must be non-negative");
        }
        if (matches != null && matches < 0) {
            throw new IllegalArgumentException("matches must be non-negative");
        }
    }
}
