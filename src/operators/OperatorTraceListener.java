package operators;

/**
 * Observer for operator lifecycle events, fired by {@link AbstractOperator}.
 *
 * <p>
 * Mirrors {@code buffer.BufferTraceListener}: the engine package defines a
 * small callback interface with no-op defaults, and the trace layer implements
 * it. Operators stay free of any dependency on the trace wire model.
 */
public interface OperatorTraceListener {

    /** Fired before the operator initializes itself (and thus before its children open). */
    default void operatorOpen(String operatorId) {}

    /** Fired on every pull, before the operator computes its next record. */
    default void operatorNext(String operatorId) {}

    /** Fired when a pull produced a record (not fired on exhaustion). */
    default void operatorEmit(String operatorId) {}

    /** Fired after the operator released its resources (and thus after its children closed). */
    default void operatorClose(String operatorId) {}

    /** Fired by an index access method just before it runs its range search. */
    default void indexSearchBegin(String operatorId, String indexFileId, byte[] startKey, byte[] endKey) {}

    /** Fired by an index access method right after its range search returns. */
    default void indexSearchEnd(String operatorId) {}
}
