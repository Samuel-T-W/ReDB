import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { generateTrace } from "./generateTrace";
import { loadTrace, queryTraceSchema } from "./loadTrace";

const DEFAULT_PARAMS = { start: "M", end: "Mz", bufferSize: 5, indexed: true };

function jsonResponse(body: unknown, ok = true): Response {
  return new Response(JSON.stringify(body), { status: ok ? 200 : 404 });
}

describe("loadTrace", () => {
  it("keeps the committed real trace schema-safe", () => {
    const raw = readFileSync("public/data/query-trace-default.json", "utf8");
    const trace = queryTraceSchema.parse(JSON.parse(raw));

    expect(trace.run.command).toBe("run_query");
    expect(trace.run.range).toEqual({ start: "M", end: "Mz" });
    expect(trace.run.bufferSize).toBe(5);
    expect(trace.run.indexed).toBe(true);
    expect(trace.events.at(-1)?.type).toBe("query.complete");
    expect(trace.summary.recordsEmitted).toBeGreaterThan(0);
  });

  it("loads the saved artifact when it matches the requested run", async () => {
    const saved = generateTrace(DEFAULT_PARAMS);
    const loaded = await loadTrace(DEFAULT_PARAMS, async () => jsonResponse(saved));

    expect(loaded.source).toBe("saved");
    expect(loaded.trace).toEqual(saved);
  });

  it("falls back to synthetic trace when the artifact is unavailable", async () => {
    const loaded = await loadTrace(DEFAULT_PARAMS, async () => jsonResponse({}, false));

    expect(loaded.source).toBe("synthetic");
    expect(loaded.trace.run.range).toEqual({ start: "M", end: "Mz" });
  });

  it("falls back to synthetic trace when settings do not match the artifact", async () => {
    const saved = generateTrace(DEFAULT_PARAMS);
    const loaded = await loadTrace({ ...DEFAULT_PARAMS, bufferSize: 8 }, async () => jsonResponse(saved));

    expect(loaded.source).toBe("synthetic");
    expect(loaded.trace.run.bufferSize).toBe(8);
  });
});
