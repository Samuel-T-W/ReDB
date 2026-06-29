package trace;

import java.time.Instant;
import java.util.Objects;

/** Metadata for the command that produced a trace. */
public record TraceRun(
        String id,
        String command,
        Instant startedAt,
        TraceRange range,
        int bufferSize,
        boolean indexed,
        long wallClockMs) {

    public static final String RUN_QUERY_COMMAND = "run_query";

    public TraceRun {
        requireNonBlank(id, "id");
        requireNonBlank(command, "command");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(range, "range");
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        if (wallClockMs < 0) {
            throw new IllegalArgumentException("wallClockMs must be non-negative");
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
    }
}
