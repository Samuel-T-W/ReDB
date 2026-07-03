package operators;

import org.jspecify.annotations.Nullable;
import storage.GenericRecord;

/**
 * Base class for all operators: implements the {@link Operator} lifecycle as
 * template methods that emit trace events around the subclass logic.
 *
 * <p>
 * Tracing is defined once here, at the class level, so any operator tree -
 * however it was constructed - becomes traceable by walking it and calling
 * {@link #attachTrace} on each node. No per-plan wrapper code is needed, which
 * keeps instrumentation working unchanged once plans are no longer hand-built.
 *
 * <p>
 * Without a listener attached, the lifecycle methods delegate straight to the
 * subclass, so untraced execution pays only a null check.
 */
public abstract class AbstractOperator implements Operator {

    private @Nullable OperatorTraceListener trace;
    private @Nullable String traceId;

    /** Routes this operator's lifecycle events to {@code listener} under {@code operatorId}. */
    public final void attachTrace(OperatorTraceListener listener, String operatorId) {
        this.trace = listener;
        this.traceId = operatorId;
    }

    @Override
    public final void open() {
        if (trace != null) {
            trace.operatorOpen(traceId);
        }
        doOpen();
    }

    @Override
    public final GenericRecord next() {
        if (trace != null) {
            trace.operatorNext(traceId);
        }
        GenericRecord record = fetchNext();
        if (record != null && trace != null) {
            trace.operatorEmit(traceId);
        }
        return record;
    }

    @Override
    public final void close() {
        doClose();
        if (trace != null) {
            trace.operatorClose(traceId);
        }
    }

    /** The attached listener, or {@code null} when untraced. For subclass-specific events. */
    protected final @Nullable OperatorTraceListener trace() {
        return trace;
    }

    /** The id assigned by {@link #attachTrace}, or {@code null} when untraced. */
    protected final @Nullable String traceId() {
        return traceId;
    }

    /** Initializes any state or resources needed before reading records. */
    protected abstract void doOpen();

    /**
     * Computes the next available record from this operator.
     *
     * @return the next record, or {@code null} when the operator is exhausted
     */
    protected abstract GenericRecord fetchNext();

    /** Releases any resources held by this operator. */
    protected abstract void doClose();
}
