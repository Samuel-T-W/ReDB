package trace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Top-level trace artifact for one offline query run.
 *
 * <p>
 * This model is intentionally not wired into execution yet. It documents the
 * contract the engine will eventually serialize for the static showcase demo.
 */
public record QueryTrace(
        int schemaVersion,
        TraceRun run,
        TraceSummary summary,
        TracePlanNode plan,
        Map<String, TraceTable> tables,
        List<TraceEvent> events) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public QueryTrace {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported trace schema version: " + schemaVersion);
        }
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(plan, "plan");
        tables = Map.copyOf(Objects.requireNonNull(tables, "tables"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
