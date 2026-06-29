package trace;

import java.util.Map;
import java.util.Objects;

/** Projected query result attached to query.result events. */
public record TraceResultDetail(long ordinal, Map<String, String> fields) {

    public TraceResultDetail {
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
        fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
