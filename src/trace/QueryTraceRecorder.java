package trace;

import buffer.BufferTraceListener;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import storage.GenericRecord;

/** Collects trace events while the regular run_query operator tree executes. */
public final class QueryTraceRecorder implements BufferTraceListener {
    private final TraceRun runTemplate;
    private final TracePlanNode plan;
    private final Map<String, TraceTable> tables;
    private final List<TraceEvent> events = new ArrayList<>();

    private long seq;
    private long pagesRead;
    private long bufferHits;
    private long bufferMisses;
    private long evictions;
    private long recordsEmitted;
    private long operatorNextCalls;
    private long btreeNodeVisits;

    public QueryTraceRecorder(
            String runId,
            String startRange,
            String endRange,
            int bufferSize,
            boolean indexed,
            TracePlanNode plan,
            Map<String, TraceTable> tables) {
        this.runTemplate = new TraceRun(
                runId,
                TraceRun.RUN_QUERY_COMMAND,
                Instant.now(),
                new TraceRange(startRange, endRange),
                bufferSize,
                indexed,
                0);
        this.plan = plan;
        this.tables = new LinkedHashMap<>(tables);
    }

    public void operatorOpen(String operatorId, String message) {
        emit(TraceEvent.of(seq, seq, TraceEventType.OPERATOR_OPEN, operatorId), message);
    }

    public void operatorNext(String operatorId) {
        operatorNextCalls++;
        emit(TraceEvent.of(seq, seq, TraceEventType.OPERATOR_NEXT, operatorId), null);
    }

    public void operatorEmit(String operatorId) {
        emit(TraceEvent.of(seq, seq, TraceEventType.OPERATOR_EMIT, operatorId), null);
    }

    public void operatorClose(String operatorId) {
        emit(TraceEvent.of(seq, seq, TraceEventType.OPERATOR_CLOSE, operatorId), null);
    }

    public void btreeSearchBegin(String indexFileId, String startRange, String endRange) {
        emit(new TraceEvent(
                seq,
                seq,
                TraceEventType.BTREE_SEARCH_BEGIN,
                "movies-index",
                null,
                null,
                null,
                null,
                new TraceBTreeDetail(indexFileId, null, null, null, startRange, endRange),
                null,
                null));
    }

    public void btreeSearchEnd() {
        emit(TraceEvent.of(seq, seq, TraceEventType.BTREE_SEARCH_END, "movies-index"), null);
    }

    public void queryResult(GenericRecord result) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", fixedString(result.getFieldBytes("title")));
        fields.put("name", fixedString(result.getFieldBytes("name")));
        emit(new TraceEvent(
                seq,
                seq,
                TraceEventType.QUERY_RESULT,
                "project",
                null,
                null,
                null,
                null,
                null,
                new TraceResultDetail(recordsEmitted, fields),
                null));
        recordsEmitted++;
    }

    public void queryComplete(long resultCount) {
        emit(new TraceEvent(
                seq,
                seq,
                TraceEventType.QUERY_COMPLETE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                resultCount + " rows"));
    }

    public QueryTrace toTrace(long wallClockMs) {
        TraceSummary summary = new TraceSummary(
                pagesRead,
                bufferHits,
                bufferMisses,
                evictions,
                operatorNextCalls,
                recordsEmitted,
                operatorNextCalls,
                btreeNodeVisits);
        TraceRun run = new TraceRun(
                runTemplate.id(),
                runTemplate.command(),
                runTemplate.startedAt(),
                runTemplate.range(),
                runTemplate.bufferSize(),
                runTemplate.indexed(),
                wallClockMs);
        return new QueryTrace(QueryTrace.CURRENT_SCHEMA_VERSION, run, summary, plan, tables, events);
    }

    @Override
    public void onBufferHit(
            String fileId, int pageId, int frameId, boolean dirty, int pinCount, byte[] pageData) {
        bufferHits++;
        emit(new TraceEvent(
                seq,
                seq,
                TraceEventType.BUFFER_HIT,
                null,
                new TracePageRef(fileId, pageId),
                new TraceFrameRef(frameId, dirty, pinCount, null),
                null,
                null,
                null,
                null,
                null));
        emitIndexVisitIfNeeded(fileId, pageId, pageData);
    }

    @Override
    public void onBufferMiss(String fileId, int pageId) {
        bufferMisses++;
        pagesRead++;
        emit(new TraceEvent(
                seq,
                seq,
                TraceEventType.BUFFER_MISS,
                null,
                new TracePageRef(fileId, pageId),
                null,
                null,
                null,
                null,
                null,
                null));
    }

    @Override
    public void onPageLoad(
            String fileId, int pageId, int frameId, boolean dirty, int pinCount, byte[] pageData) {
        emit(new TraceEvent(
                seq,
                seq,
                TraceEventType.BUFFER_PAGE_LOAD,
                null,
                new TracePageRef(fileId, pageId),
                new TraceFrameRef(frameId, dirty, pinCount, null),
                null,
                null,
                null,
                null,
                null));
        emitIndexVisitIfNeeded(fileId, pageId, pageData);
    }

    @Override
    public void onPageEvict(String fileId, int pageId, int frameId, boolean dirty, int pinCount) {
        evictions++;
        emit(new TraceEvent(
                seq,
                seq,
                TraceEventType.BUFFER_EVICT,
                null,
                null,
                new TraceFrameRef(frameId, dirty, pinCount, new TracePageRef(fileId, pageId)),
                null,
                null,
                null,
                null,
                null));
    }

    @Override
    public void onBufferFlush(String fileId, int pageId) {
        emit(new TraceEvent(
                seq,
                seq,
                TraceEventType.BUFFER_FLUSH,
                null,
                new TracePageRef(fileId, pageId),
                null,
                null,
                null,
                null,
                null,
                null));
    }

    private void emitIndexVisitIfNeeded(String fileId, int pageId, byte[] pageData) {
        if (!fileId.endsWith(".idx")) {
            return;
        }
        btreeNodeVisits++;
        boolean isLeaf = ByteBuffer.wrap(pageData).getInt(0) == 1;
        TraceBTreeNodeType nodeType = isLeaf ? TraceBTreeNodeType.LEAF : TraceBTreeNodeType.INTERNAL;
        emit(new TraceEvent(
                seq,
                seq,
                TraceEventType.BTREE_NODE_VISIT,
                "movies-index",
                new TracePageRef(fileId, pageId),
                null,
                null,
                null,
                new TraceBTreeDetail(fileId, pageId, nodeType, null, null, null),
                null,
                null));
    }

    private void emit(TraceEvent event, String message) {
        TraceEvent withSeq = new TraceEvent(
                seq,
                seq,
                event.type(),
                event.operatorId(),
                event.page(),
                event.frame(),
                event.recordId(),
                event.join(),
                event.btree(),
                event.result(),
                message == null ? event.message() : message);
        events.add(withSeq);
        seq++;
    }

    private void emit(TraceEvent event) {
        emit(event, null);
    }

    private static String fixedString(byte[] bytes) {
        int len = bytes.length;
        while (len > 0 && (bytes[len - 1] == 0 || bytes[len - 1] == ' ')) {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}
