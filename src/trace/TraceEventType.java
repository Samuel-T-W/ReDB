package trace;

import java.util.Arrays;

/** Event names use dotted wire values so generated JSON is frontend-friendly. */
public enum TraceEventType {
    OPERATOR_OPEN("operator.open"),
    OPERATOR_NEXT("operator.next"),
    OPERATOR_EMIT("operator.emit"),
    OPERATOR_CLOSE("operator.close"),

    BUFFER_HIT("buffer.hit"),
    BUFFER_MISS("buffer.miss"),
    BUFFER_PAGE_LOAD("buffer.page_load"),
    BUFFER_EVICT("buffer.evict"),
    BUFFER_PIN("buffer.pin"),
    BUFFER_UNPIN("buffer.unpin"),
    BUFFER_FLUSH("buffer.flush"),

    SCAN_PAGE_BEGIN("scan.page_begin"),
    SCAN_PAGE_END("scan.page_end"),

    FILTER_PASS("filter.pass"),
    FILTER_REJECT("filter.reject"),

    BNL_BLOCK_BEGIN("bnl.block_begin"),
    BNL_BLOCK_END("bnl.block_end"),
    BNL_HASH_BUILD("bnl.hash_build"),
    BNL_PROBE("bnl.probe"),
    BNL_MATCH("bnl.match"),

    BTREE_SEARCH_BEGIN("btree.search_begin"),
    BTREE_NODE_VISIT("btree.node_visit"),
    BTREE_RANGE_LEAF_BEGIN("btree.range_leaf_begin"),
    BTREE_RANGE_EMIT("btree.range_emit"),
    BTREE_SEARCH_END("btree.search_end"),

    QUERY_RESULT("query.result"),
    QUERY_COMPLETE("query.complete");

    private final String wireName;

    TraceEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static TraceEventType fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown trace event type: " + wireName));
    }
}
