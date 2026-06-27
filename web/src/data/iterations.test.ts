import { describe, expect, it } from "vitest";
import { getIteration, ITERATIONS, LATEST_IMPLEMENTED } from "./iterations";

describe("iterations data", () => {
  it("has unique, contiguous ids starting at 1", () => {
    const ids = ITERATIONS.map((it) => it.id);
    expect(ids).toEqual(Array.from({ length: ITERATIONS.length }, (_, i) => i + 1));
  });

  it("requires implemented iterations to carry an explanation", () => {
    for (const it of ITERATIONS) {
      expect(it.explanation.length).toBeGreaterThan(0);
      if (it.status === "implemented") {
        expect(it.name).not.toBe("");
        expect(it.tagline).not.toBe("");
      }
    }
  });

  it("looks up an iteration by id and misses cleanly", () => {
    expect(getIteration(1)).toBe(ITERATIONS[0]);
    expect(getIteration(999)).toBeUndefined();
  });

  it("points LATEST_IMPLEMENTED at the highest implemented iteration", () => {
    expect(LATEST_IMPLEMENTED.status).toBe("implemented");
    const highestImplemented = [...ITERATIONS]
      .filter((it) => it.status === "implemented")
      .sort((a, b) => b.id - a.id)[0];
    expect(LATEST_IMPLEMENTED).toBe(highestImplemented);
  });
});
