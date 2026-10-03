import { describe, expect, it } from "vitest";
import { describeDuration, formatDuration } from "./duration";

describe("formatDuration", () => {
  it("rounds up partial seconds so a live second is not hidden", () => {
    expect(formatDuration(1_001)).toBe("0:02");
  });

  it("formats hours with padded minutes and seconds", () => {
    expect(formatDuration(3_661_000)).toBe("1:01:01");
  });

  it("clamps expired durations to zero", () => {
    expect(formatDuration(-1)).toBe("0:00");
  });
});

describe("describeDuration", () => {
  it("says the remainder in words", () => {
    expect(describeDuration(61_000)).toBe("1 minute and 1 second remaining");
    expect(describeDuration(120_000)).toBe("2 minutes remaining");
    expect(describeDuration(3_720_000)).toBe("1 hour and 2 minutes remaining");
  });

  it("never describes a negative remainder", () => {
    expect(describeDuration(-5)).toBe("no time remaining");
  });
});
