import { describe, expect, it } from "vitest";
import { BUFFER_SIZES, RANGE_PRESETS } from "./presets";

describe("range presets", () => {
  it("has unique ids", () => {
    const ids = RANGE_PRESETS.map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("orders every preset's start at or before its end", () => {
    for (const p of RANGE_PRESETS) {
      expect(p.start.localeCompare(p.end)).toBeLessThanOrEqual(0);
      expect(p.label).not.toBe("");
    }
  });
});

describe("buffer sizes", () => {
  it("is a sorted list of positive, unique sizes", () => {
    expect(BUFFER_SIZES.length).toBeGreaterThan(0);
    expect(new Set(BUFFER_SIZES).size).toBe(BUFFER_SIZES.length);
    expect([...BUFFER_SIZES].sort((a, b) => a - b)).toEqual(BUFFER_SIZES);
    expect(BUFFER_SIZES.every((n) => n > 0)).toBe(true);
  });
});
