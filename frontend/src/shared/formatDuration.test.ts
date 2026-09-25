import { describe, expect, it } from "vitest";
import { describeDuration, formatDuration } from "./formatDuration";

describe("formatDuration", () => {
  it("rounds up partial seconds so a live second is not hidden", () => {
    expect(formatDuration(1_001)).toBe("0:02");
  });

  it("formats hours without layout-changing digit widths", () => {
    expect(formatDuration(3_661_000)).toBe("1:01:01");
  });

  it("clamps expired durations to zero", () => {
    expect(formatDuration(-1)).toBe("0:00");
    expect(describeDuration(-1)).toBe("Time remaining: zero");
  });
});
