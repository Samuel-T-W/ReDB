package trace;

import java.util.ArrayList;
import java.util.List;
import operators.Operator;
import org.jspecify.annotations.Nullable;
import storage.GenericRecord;

/**
 * Wraps a query operator so opening, pulling, and closing it also emits trace events, and
 * contributes a node to the captured plan tree.
 *
 * <p>
 * Composing these the same way the real operator tree is composed - wrap a leaf, pass the
 * wrapped operator to its parent's constructor, wrap that parent - keeps the plan trace from
 * ever drifting out of sync with what actually executes: there is exactly one tree, not a real
 * one plus a hand-maintained shadow copy.
 */
public final class TracingOperator implements Operator {

    private final QueryTraceRecorder recorder;
    private final Operator delegate;
    private final String id;
    private final TracePlanNode node;

    private TracingOperator(
            QueryTraceRecorder recorder,
            Operator delegate,
            String id,
            TracePlanNodeType type,
            String label,
            @Nullable String detail,
            List<TracePlanNode> childNodes) {
        this.recorder = recorder;
        this.delegate = delegate;
        this.id = id;
        this.node = new TracePlanNode(id, type, label, detail, childNodes);
    }

    /** Wraps {@code delegate} when {@code recorder} is present; otherwise returns it unwrapped. */
    public static Operator wrap(
            @Nullable QueryTraceRecorder recorder,
            Operator delegate,
            String id,
            TracePlanNodeType type,
            String label,
            Operator... children) {
        return wrap(recorder, delegate, id, type, label, null, children);
    }

    public static Operator wrap(
            @Nullable QueryTraceRecorder recorder,
            Operator delegate,
            String id,
            TracePlanNodeType type,
            String label,
            @Nullable String detail,
            Operator... children) {
        if (recorder == null) {
            return delegate;
        }
        List<TracePlanNode> childNodes = new ArrayList<>(children.length);
        for (Operator child : children) {
            childNodes.add(describeChild(child, id));
        }
        return new TracingOperator(recorder, delegate, id, type, label, detail, childNodes);
    }

    private static TracePlanNode describeChild(Operator child, String parentId) {
        if (!(child instanceof TracingOperator traced)) {
            throw new IllegalStateException(
                    "plan node '" + parentId + "' was given an untraced child; wrap it first");
        }
        return traced.describe();
    }

    @Override
    public void open() {
        recorder.operatorOpen(id, null);
        delegate.open();
    }

    @Override
    public GenericRecord next() {
        recorder.operatorNext(id);
        GenericRecord record = delegate.next();
        if (record != null) {
            recorder.operatorEmit(id);
        }
        return record;
    }

    @Override
    public void close() {
        delegate.close();
        recorder.operatorClose(id);
    }

    /** The plan node this operator, and its already-described traced children, represents. */
    public TracePlanNode describe() {
        return node;
    }
}
