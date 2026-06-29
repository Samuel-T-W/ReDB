package trace;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One ordered replay event in a query trace. */
public record TraceEvent(
        long seq,
        long timeMs,
        TraceEventType type,
        @Nullable String operatorId,
        @Nullable TracePageRef page,
        @Nullable TraceFrameRef frame,
        @Nullable TraceRecordRef recordId,
        @Nullable TraceJoinDetail join,
        @Nullable TraceBTreeDetail btree,
        @Nullable TraceResultDetail result,
        @Nullable String message) {

    public TraceEvent {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be non-negative");
        }
        if (timeMs < 0) {
            throw new IllegalArgumentException("timeMs must be non-negative");
        }
        Objects.requireNonNull(type, "type");
    }

    public static TraceEvent of(long seq, long timeMs, TraceEventType type, String operatorId) {
        return new TraceEvent(seq, timeMs, type, operatorId, null, null, null, null, null, null, null);
    }
}
