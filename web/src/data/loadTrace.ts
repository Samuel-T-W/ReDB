import { z } from "zod";
import { generateTrace, type TraceParams } from "./generateTrace";
import {
  CURRENT_SCHEMA_VERSION,
  type QueryTrace,
  type TraceBTreeNodeType,
  type TraceEventType,
  type TraceJoinSide,
  type TracePlanNodeType,
} from "../types/trace";

export const DEFAULT_TRACE_URL = "data/query-trace-default.json";

const traceEventTypeValues = [
  "operator.open",
  "operator.next",
  "operator.emit",
  "operator.close",
  "buffer.hit",
  "buffer.miss",
  "buffer.page_load",
  "buffer.evict",
  "buffer.pin",
  "buffer.unpin",
  "buffer.flush",
  "scan.page_begin",
  "scan.page_end",
  "filter.pass",
  "filter.reject",
  "bnl.block_begin",
  "bnl.block_end",
  "bnl.hash_build",
  "bnl.probe",
  "bnl.match",
  "btree.search_begin",
  "btree.node_visit",
  "btree.range_leaf_begin",
  "btree.range_emit",
  "btree.search_end",
  "query.result",
  "query.complete",
] as const satisfies readonly TraceEventType[];

const planNodeTypeValues = [
  "PROJECT",
  "BNL_JOIN",
  "SELECTION",
  "SCAN",
  "INDEX_SCAN",
  "MATERIALIZE",
] as const satisfies readonly TracePlanNodeType[];

const btreeNodeTypeValues = ["INTERNAL", "LEAF"] as const satisfies readonly TraceBTreeNodeType[];
const joinSideValues = ["LEFT", "RIGHT"] as const satisfies readonly TraceJoinSide[];

const nonNegative = z.number().int().nonnegative();
const positive = z.number().int().positive();

const traceRangeSchema = z.object({
  start: z.string(),
  end: z.string(),
});

const traceRunSchema = z.object({
  id: z.string().min(1),
  command: z.literal("run_query"),
  startedAt: z.string().datetime({ offset: true }),
  range: traceRangeSchema,
  bufferSize: positive,
  indexed: z.boolean(),
  wallClockMs: nonNegative,
});

const traceSummarySchema = z.object({
  pagesRead: nonNegative,
  bufferHits: nonNegative,
  bufferMisses: nonNegative,
  evictions: nonNegative,
  recordsExamined: nonNegative,
  recordsEmitted: nonNegative,
  operatorNextCalls: nonNegative,
  btreeNodeVisits: nonNegative,
});

const tracePlanNodeSchema: z.ZodType<QueryTrace["plan"]> = z.lazy(() =>
  z.object({
    id: z.string().min(1),
    type: z.enum(planNodeTypeValues),
    label: z.string().min(1),
    detail: z.string().optional(),
    children: z.array(tracePlanNodeSchema),
  }),
);

const traceTableSchema = z.object({
  fileId: z.string().min(1),
  recordSize: positive,
  recordCount: nonNegative.optional(),
});

const tracePageRefSchema = z.object({
  fileId: z.string().min(1),
  pageId: nonNegative,
});

const traceFrameRefSchema = z.object({
  frameId: nonNegative,
  dirty: z.boolean().optional(),
  pinCount: nonNegative.optional(),
  evictedPage: tracePageRefSchema.nullable().optional(),
});

const traceRecordRefSchema = z.object({
  pageId: nonNegative,
  slotId: nonNegative,
});

const traceJoinDetailSchema = z.object({
  blockId: nonNegative.optional(),
  side: z.enum(joinSideValues).optional(),
  key: z.string().optional(),
  matches: nonNegative.optional(),
});

const traceBTreeDetailSchema = z.object({
  indexFileId: z.string().optional(),
  nodePageId: nonNegative.optional(),
  nodeType: z.enum(btreeNodeTypeValues).optional(),
  key: z.string().optional(),
  rangeStart: z.string().optional(),
  rangeEnd: z.string().optional(),
});

const traceResultDetailSchema = z.object({
  ordinal: nonNegative,
  fields: z.record(z.string(), z.string()),
});

const traceEventSchema = z.object({
  seq: nonNegative,
  timeMs: nonNegative,
  type: z.enum(traceEventTypeValues),
  operatorId: z.string().optional(),
  page: tracePageRefSchema.optional(),
  frame: traceFrameRefSchema.optional(),
  recordId: traceRecordRefSchema.optional(),
  join: traceJoinDetailSchema.optional(),
  btree: traceBTreeDetailSchema.optional(),
  result: traceResultDetailSchema.optional(),
  message: z.string().optional(),
});

export const queryTraceSchema: z.ZodType<QueryTrace> = z
  .object({
    schemaVersion: z.literal(CURRENT_SCHEMA_VERSION),
    run: traceRunSchema,
    summary: traceSummarySchema,
    plan: tracePlanNodeSchema,
    tables: z.record(z.string(), traceTableSchema),
    events: z.array(traceEventSchema).min(1),
  })
  .superRefine((trace, ctx) => {
    for (let i = 0; i < trace.events.length; i++) {
      if (trace.events[i].seq !== i) {
        ctx.addIssue({
          code: "custom",
          path: ["events", i, "seq"],
          message: "event seq must match its array index",
        });
      }
      if (i > 0 && trace.events[i].timeMs < trace.events[i - 1].timeMs) {
        ctx.addIssue({
          code: "custom",
          path: ["events", i, "timeMs"],
          message: "event timeMs must be non-decreasing",
        });
      }
    }
  });

export type TraceSource = "saved" | "synthetic";

export interface LoadedTrace {
  trace: QueryTrace;
  source: TraceSource;
}

export async function loadTrace(
  params: TraceParams,
  fetcher: typeof fetch = globalThis.fetch,
): Promise<LoadedTrace> {
  try {
    const response = await fetcher(DEFAULT_TRACE_URL);
    if (!response.ok) {
      throw new Error(`trace fetch failed: ${response.status}`);
    }
    const parsed = queryTraceSchema.parse(await response.json());
    if (matchesParams(parsed, params)) {
      return { trace: parsed, source: "saved" };
    }
  } catch {
    // Missing or invalid artifacts should not break the showcase; synthetic
    // generation remains the development and comparison fallback.
  }

  return { trace: generateTrace(params), source: "synthetic" };
}

export function matchesParams(trace: QueryTrace, params: TraceParams): boolean {
  return (
    trace.run.range.start === params.start &&
    trace.run.range.end === params.end &&
    trace.run.bufferSize === params.bufferSize &&
    trace.run.indexed === params.indexed
  );
}
