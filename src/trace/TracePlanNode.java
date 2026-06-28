package trace;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Static query plan node referenced by trace events. */
public record TracePlanNode(
        String id,
        TracePlanNodeType type,
        String label,
        @Nullable String detail,
        List<TracePlanNode> children) {

    public TracePlanNode {
        requireNonBlank(id, "id");
        Objects.requireNonNull(type, "type");
        requireNonBlank(label, "label");
        children = children == null ? List.of() : List.copyOf(children);
    }

    public TracePlanNode(String id, TracePlanNodeType type, String label) {
        this(id, type, label, null, List.of());
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
    }
}
