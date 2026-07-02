import { describe, expect, it } from "vitest";
import { replay } from "./replay";
import { generateTrace } from "./generateTrace";
import { type QueryTrace, type TraceEvent } from "../types/trace";
import { CURRENT_SCHEMA_VERSION } from "../types/traceSchema";

// A tiny hand-built trace with a 2-frame pool: load page A, load page B, hit A,
// then miss page C which evicts the LRU frame and loads C, then emit one result.
function miniTrace(): QueryTrace {
  const events: TraceEvent[] = [
    { seq: 0, timeMs: 0, type: "operator.open", operatorId: "scan" },
    {
      seq: 1,
      timeMs: 1,
      type: "buffer.miss",
      operatorId: "scan",
      page: { fileId: "t.csv", pageId: 0 },
    },
    {
      seq: 2,
      timeMs: 2,
      type: "buffer.page_load",
      operatorId: "scan",
      page: { fileId: "t.csv", pageId: 0 },
      frame: { frameId: 0, pinCount: 1, dirty: false },
    },
    {
      seq: 3,
      timeMs: 3,
      type: "buffer.miss",
      operatorId: "scan",
      page: { fileId: "t.csv", pageId: 1 },
    },
    {
      seq: 4,
      timeMs: 4,
      type: "buffer.page_load",
      operatorId: "scan",
      page: { fileId: "t.csv", pageId: 1 },
      frame: { frameId: 1, pinCount: 1, dirty: false },
    },
    {
      seq: 5,
      timeMs: 5,
      type: "buffer.hit",
      operatorId: "scan",
      page: { fileId: "t.csv", pageId: 0 },
      frame: { frameId: 0 },
    },
    {
      seq: 6,
      timeMs: 6,
      type: "buffer.miss",
      operatorId: "scan",
      page: { fileId: "t.csv", pageId: 2 },
    },
    {
      seq: 7,
      timeMs: 7,
      type: "buffer.evict",
      operatorId: "scan",
      frame: { frameId: 1, evictedPage: { fileId: "t.csv", pageId: 1 } },
    },
    {
      seq: 8,
      timeMs: 8,
      type: "buffer.page_load",
      operatorId: "scan",
      page: { fileId: "t.csv", pageId: 2 },
      frame: { frameId: 1, pinCount: 1, dirty: false },
    },
    {
      seq: 9,
      timeMs: 9,
      type: "query.result",
      operatorId: "project",
      result: { ordinal: 0, fields: { title: "Pinned", name: "Ada Pageman" } },
    },
  ];
  return {
    schemaVersion: CURRENT_SCHEMA_VERSION,
    run: {
      id: "mini",
      command: "run_query",
      startedAt: "2026-01-01T00:00:00Z",
      range: { start: "A", end: "Z" },
      bufferSize: 2,
      indexed: false,
      wallClockMs: 9,
    },
    summary: {
      pagesRead: 3,
      bufferHits: 1,
      bufferMisses: 3,
      evictions: 1,
      recordsExamined: 0,
      recordsEmitted: 1,
      operatorNextCalls: 1,
      btreeNodeVisits: 0,
    },
    plan: { id: "project", type: "PROJECT", label: "π", children: [] },
    tables: {},
    events,
  };
}

describe("replay", () => {
  it("returns an empty pool sized to bufferSize at the first event", () => {
    const state = replay(miniTrace(), 0);
    expect(state.frames).toHaveLength(2);
    expect(state.frames.every((f) => f === null)).toBe(true);
    expect(state.running.pagesRead).toBe(0);
    expect(state.results).toEqual([]);
  });

  it("loads pages into their frames", () => {
    const state = replay(miniTrace(), 4);
    expect(state.frames[0]).toMatchObject({ fileId: "t.csv", pageId: 0 });
    expect(state.frames[1]).toMatchObject({ fileId: "t.csv", pageId: 1 });
    expect(state.touchedFrameId).toBe(1);
  });

  it("counts a buffer hit and marks the touched frame", () => {
    const state = replay(miniTrace(), 5);
    expect(state.running.bufferHits).toBe(1);
    expect(state.touchedFrameId).toBe(0);
  });

  it("frees a frame on eviction before the replacement loads", () => {
    const atEvict = replay(miniTrace(), 7);
    expect(atEvict.frames[1]).toBeNull();
    expect(atEvict.evictingFrameId).toBe(1);
    expect(atEvict.running.evictions).toBe(1);

    const afterLoad = replay(miniTrace(), 8);
    expect(afterLoad.frames[1]).toMatchObject({ fileId: "t.csv", pageId: 2 });
  });

  it("accumulates the full run's counters and results", () => {
    const trace = miniTrace();
    const state = replay(trace, trace.events.length - 1);
    expect(state.running.pagesRead).toBe(trace.summary.pagesRead);
    expect(state.running.bufferHits).toBe(trace.summary.bufferHits);
    expect(state.running.bufferMisses).toBe(trace.summary.bufferMisses);
    expect(state.running.evictions).toBe(trace.summary.evictions);
    expect(state.running.recordsEmitted).toBe(trace.summary.recordsEmitted);
    expect(state.results).toEqual([{ title: "Pinned", name: "Ada Pageman" }]);
  });

  it("clamps a cursor past the end to the last event", () => {
    const trace = miniTrace();
    const last = replay(trace, trace.events.length - 1);
    expect(replay(trace, 9999)).toEqual(last);
  });

  it("matches the generated trace summary when replayed to the end", () => {
    const trace = generateTrace({ start: "S", end: "Sz", bufferSize: 8, indexed: true });
    const state = replay(trace, trace.events.length - 1);
    expect(state.running.bufferHits).toBe(trace.summary.bufferHits);
    expect(state.running.bufferMisses).toBe(trace.summary.bufferMisses);
    expect(state.running.pagesRead).toBe(trace.summary.pagesRead);
    expect(state.running.evictions).toBe(trace.summary.evictions);
    expect(state.running.btreeNodeVisits).toBe(trace.summary.btreeNodeVisits);
    expect(state.running.recordsEmitted).toBe(trace.summary.recordsEmitted);
  });
});
