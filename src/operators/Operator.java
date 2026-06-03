package operators;

import storage.GenericRecord;

/**
 * Common interface for query operators.
 *
 * <p>
 * An operator is opened before use, produces zero or more records via
 * {@link #next()}, and is closed when execution is complete.
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

}
