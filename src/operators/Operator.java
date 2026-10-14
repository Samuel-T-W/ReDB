package operators;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
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

    /**
     * Fails if this thread has been interrupted, which is how a query deadline
     * cancels execution. Leaf operators call this before every page pin: cache
     * hits never touch a channel, so a query whose working set is resident would
     * otherwise never notice the interrupt. The interrupt flag is left set.
     */
    static void checkNotCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new UncheckedIOException(
                    new InterruptedIOException("query cancelled by interrupt"));
        }
    }
}
