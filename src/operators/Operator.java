package operators;

import java.util.List;
import org.jspecify.annotations.Nullable;
import storage.GenericRecord;

/**
 * Common interface for query operators.
 *
 * <p>
 * An operator is opened before use, produces zero or more records via
 * {@link #next()}, and is closed when execution is complete.
 *
 * <p>
 * Operators are also self-describing: {@link #children()}, {@link #label()},
 * and {@link #detail()} expose enough structure to derive a plan tree from any
 * operator tree without maintaining a parallel description of it.
 */
public interface Operator {
    /**
     * Returns the next available record from this operator.
     *
     * @return the next record, or {@code null} when the operator is exhausted
     */
    GenericRecord next();

    /** Initializes any state or resources needed before reading records. */
    void open();

    /** Releases any resources held by this operator. */
    void close();

    /** The child operators this operator pulls from, in plan order; empty for leaves. */
    default List<Operator> children() {
        return List.of();
    }

    /** A short human-readable description of this operator, derived from its own state. */
    default String label() {
        return getClass().getSimpleName();
    }

    /** Optional extra description (e.g. access-method notes), or {@code null}. */
    default @Nullable String detail() {
        return null;
    }
}
