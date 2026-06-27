import { describe, expect, it } from "vitest";
import { generateTrace, type TraceParams } from "./generateTrace";
import { replay } from "./replay";
import { CURRENT_SCHEMA_VERSION, type TraceEventType } from "../types/trace";

const INDEXED: TraceParams = { start: "M", end: "Mz", bufferSize: 5, indexed: true };
const SCAN: TraceParams = { start: "M", end: "Mz", bufferSize: 5, indexed: false };

function countType(trace: ReturnType<typeof generateTrace>, type: TraceEventType): number {
  return trace.events.filter((e) => e.type === type).length;
}

describe("generateTrace", () => {
  it("is deterministic for identical params", () => {
    // run.startedAt is wall-clock by design; everything else must be reproducible.
    const strip = (t: ReturnType<typeof generateTrace>) => ({
      ...t,
      run: { ...t.run, startedAt: "" },
    });
    expect(strip(generateTrace(INDEXED))).toEqual(strip(generateTrace(INDEXED)));
  });

  it("produces a different trace for indexed vs. scan", () => {
    expect(generateTrace(INDEXED)).not.toEqual(generateTrace(SCAN));
  });

  it("emits the current schema version", () => {
    expect(generateTrace(INDEXED).schemaVersion).toBe(CURRENT_SCHEMA_VERSION);
  });

  it("brackets the run with open and complete events", () => {
    const { events } = generateTrace(INDEXED);
    expect(events.length).toBeGreaterThan(0);
    expect(events[0].type).toBe("operator.open");
    expect(events[events.length - 1].type).toBe("query.complete");
  });

  it("has strictly increasing seq and non-decreasing timeMs", () => {
    const { events } = generateTrace(SCAN);
    for (let i = 1; i < events.length; i++) {
      expect(events[i].seq).toBe(events[i - 1].seq + 1);
      expect(events[i].timeMs).toBeGreaterThanOrEqual(events[i - 1].timeMs);
    }
  });

  it("uses the B+ tree path only when indexed", () => {
    const indexed = generateTrace(INDEXED);
    const scan = generateTrace(SCAN);

    expect(countType(indexed, "btree.node_visit")).toBeGreaterThan(0);
    expect(countType(scan, "btree.node_visit")).toBe(0);

    expect(countType(scan, "scan.page_begin")).toBeGreaterThan(0);
    expect(JSON.stringify(indexed.plan)).toContain("INDEX_SCAN");
    expect(JSON.stringify(scan.plan)).toContain("SCAN");
  });

  it("keeps the summary consistent with the event log", () => {
    const trace = generateTrace(INDEXED);
    expect(trace.summary.bufferMisses).toBe(countType(trace, "buffer.miss"));
    expect(trace.summary.bufferHits).toBe(countType(trace, "buffer.hit"));
    expect(trace.summary.evictions).toBe(countType(trace, "buffer.evict"));
    expect(trace.summary.btreeNodeVisits).toBe(countType(trace, "btree.node_visit"));
    expect(trace.summary.recordsEmitted).toBe(countType(trace, "query.result"));
    // Every miss reads exactly one page from disk in this model.
    expect(trace.summary.pagesRead).toBe(trace.summary.bufferMisses);
  });

  it("never loads more live frames than the buffer size", () => {
    for (const bufferSize of [3, 5, 8, 12]) {
      const trace = generateTrace({ ...INDEXED, bufferSize });
      const end = replay(trace, trace.events.length - 1);
      expect(end.frames).toHaveLength(bufferSize);
      const live = end.frames.filter((f) => f !== null).length;
      expect(live).toBeLessThanOrEqual(bufferSize);
    }
  });
});
