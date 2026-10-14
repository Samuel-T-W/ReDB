import java.time.Duration;

/**
 * Thrown by {@link QueryEngine} when a query exceeds its deadline, either while
 * waiting for admission (it never ran) or while executing (it was cancelled by
 * interrupting its thread). Either way its pins, temp files, catalog entries
 * and admission slot have been released by the time this is thrown.
 */
public class QueryTimeoutException extends RuntimeException {

    private final boolean admitted;

    QueryTimeoutException(Duration timeout, boolean admitted, Throwable cause) {
        super((admitted ? "query exceeded its " : "query timed out waiting for admission after ")
                + timeout.toMillis() + " ms deadline", cause);
        this.admitted = admitted;
    }

    /** True if the query started executing before the deadline cut it off. */
    public boolean admitted() {
        return admitted;
    }
}
