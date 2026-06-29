package trace;

/** Operator categories rendered in the demo plan tree. */
public enum TracePlanNodeType {
    PROJECT,
    BNL_JOIN,
    SELECTION,
    SCAN,
    INDEX_SCAN,
    MATERIALIZE
}
