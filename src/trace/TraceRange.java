package trace;

import java.util.Objects;

/** Inclusive title range used by run_query. */
public record TraceRange(String start, String end) {

    public TraceRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }
}
