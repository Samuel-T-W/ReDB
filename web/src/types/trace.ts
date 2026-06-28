/* tslint:disable */
/* eslint-disable */

export interface QueryTrace {
    schemaVersion: number;
    run: TraceRun;
    summary: TraceSummary;
    plan: TracePlanNode;
    tables: Record<string, TraceTable>;
    events: TraceEvent[];
}

export interface TraceRun {
    id: string;
    command: string;
    startedAt: string;
    range: TraceRange;
    bufferSize: number;
    indexed: boolean;
    wallClockMs: number;
}

export interface TraceSummary {
    pagesRead: number;
    bufferHits: number;
    bufferMisses: number;
    evictions: number;
    recordsExamined: number;
    recordsEmitted: number;
    operatorNextCalls: number;
    btreeNodeVisits: number;
}

export interface TracePlanNode {
    id: string;
    type: TracePlanNodeType;
    label: string;
    detail?: string;
    children: TracePlanNode[];
}

export interface TraceTable {
    fileId: string;
    recordSize: number;
    recordCount?: number;
}

export interface TraceEvent {
    seq: number;
    timeMs: number;
    type: TraceEventType;
    operatorId?: string;
    page?: TracePageRef;
    frame?: TraceFrameRef;
    recordId?: TraceRecordRef;
    join?: TraceJoinDetail;
    btree?: TraceBTreeDetail;
    result?: TraceResultDetail;
    message?: string;
}

export interface TraceRange {
    start: string;
    end: string;
}

export interface TracePageRef {
    fileId: string;
    pageId: number;
}

export interface TraceFrameRef {
    frameId: number;
    dirty?: boolean;
    pinCount?: number;
    evictedPage?: TracePageRef;
}

export interface TraceRecordRef {
    pageId: number;
    slotId: number;
}

export interface TraceJoinDetail {
    blockId?: number;
    side?: TraceJoinSide;
    key?: string;
    matches?: number;
}

export interface TraceBTreeDetail {
    indexFileId?: string;
    nodePageId?: number;
    nodeType?: TraceBTreeNodeType;
    key?: string;
    rangeStart?: string;
    rangeEnd?: string;
}

export interface TraceResultDetail {
    ordinal: number;
    fields: Record<string, string>;
}

export type TracePlanNodeType = "PROJECT" | "BNL_JOIN" | "SELECTION" | "SCAN" | "INDEX_SCAN" | "MATERIALIZE";

export type TraceEventType = "operator.open" | "operator.next" | "operator.emit" | "operator.close" | "buffer.hit" | "buffer.miss" | "buffer.page_load" | "buffer.evict" | "buffer.pin" | "buffer.unpin" | "buffer.flush" | "scan.page_begin" | "scan.page_end" | "filter.pass" | "filter.reject" | "bnl.block_begin" | "bnl.block_end" | "bnl.hash_build" | "bnl.probe" | "bnl.match" | "btree.search_begin" | "btree.node_visit" | "btree.range_leaf_begin" | "btree.range_emit" | "btree.search_end" | "query.result" | "query.complete";

export type TraceJoinSide = "LEFT" | "RIGHT";

export type TraceBTreeNodeType = "INTERNAL" | "LEAF";
