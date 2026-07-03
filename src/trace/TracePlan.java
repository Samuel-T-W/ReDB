package trace;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import operators.AbstractOperator;
import operators.IndexScan;
import operators.Join;
import operators.Operator;
import operators.OperatorTraceListener;
import operators.Project;
import operators.Scan;
import operators.Selection;

/**
 * Turns any operator tree into a traced one: walks the tree once, attaches the
 * listener to every node, and derives the {@link TracePlanNode} tree from the
 * operators' own descriptions. Because the plan is read off the executing tree
 * itself, it can never drift out of sync with what actually runs, and no
 * per-plan wrapper code is needed.
 */
public final class TracePlan {

    private TracePlan() {}

    /**
     * Attaches {@code listener} to every operator under {@code root} and returns
     * the derived plan tree. Node ids are deterministic: lowercase node type plus
     * a per-type counter in pre-order (e.g. {@code bnl_join-2}).
     */
    public static TracePlanNode attach(Operator root, OperatorTraceListener listener) {
        return attachNode(root, listener, new EnumMap<>(TracePlanNodeType.class));
    }

    private static TracePlanNode attachNode(
            Operator op,
            OperatorTraceListener listener,
            Map<TracePlanNodeType, Integer> counters) {
        if (!(op instanceof AbstractOperator traceable)) {
            throw new IllegalStateException(
                    "operator " + op.getClass().getName()
                            + " does not extend AbstractOperator and cannot be traced");
        }
        TracePlanNodeType type = typeOf(op);
        int ordinal = counters.merge(type, 1, Integer::sum);
        String id = type.name().toLowerCase(Locale.ROOT) + "-" + ordinal;
        traceable.attachTrace(listener, id);

        List<TracePlanNode> childNodes = new ArrayList<>();
        for (Operator child : op.children()) {
            childNodes.add(attachNode(child, listener, counters));
        }
        return new TracePlanNode(id, type, op.label(), op.detail(), childNodes);
    }

    private static TracePlanNodeType typeOf(Operator op) {
        return switch (op) {
            case IndexScan ignored -> TracePlanNodeType.INDEX_SCAN;
            case Scan ignored -> TracePlanNodeType.SCAN;
            case Selection ignored -> TracePlanNodeType.SELECTION;
            case Project project -> project.isMaterializing()
                    ? TracePlanNodeType.MATERIALIZE
                    : TracePlanNodeType.PROJECT;
            case Join ignored -> TracePlanNodeType.BNL_JOIN;
            default -> throw new IllegalArgumentException(
                    "no plan node type mapped for " + op.getClass().getName());
        };
    }
}
