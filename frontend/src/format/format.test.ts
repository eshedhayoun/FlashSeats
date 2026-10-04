import { describe, expect, it } from "vitest";
import { formatMoney } from "./money";
import { describePosition, formatPosition, formatWait, queueProgress } from "./queue";

describe("formatMoney", () => {
  it("renders minor units in the currency", () => {
    expect(formatMoney(7_500, "USD")).toMatch(/75\.00/);
  });

  it("survives a currency Intl does not know", () => {
    expect(formatMoney(1_000, "ZZZZ")).toBe("10.00 ZZZZ");
  });
});

describe("formatPosition", () => {
  it("never shows #0 or #1: the front of the line is 'You're next'", () => {
    expect(formatPosition(1)).toBe("You're next");
    expect(formatPosition(0)).toBe("You're next");
  });

  it("hides precision above a thousand", () => {
    expect(formatPosition(1_001)).toMatch(/^1.000\+$/);
    expect(formatPosition(1_000)).toMatch(/^#1.000$/);
  });

  it("describes the position in words", () => {
    expect(describePosition(42)).toBe("You are number 42 in line");
    expect(describePosition(null)).toBe("Finding your place in line");
  });
});

describe("formatWait", () => {
  it("admits when there is no estimate rather than inventing one", () => {
    expect(formatWait(null)).toBe("Estimating your wait…");
  });

  it("never shows seconds and has no ceiling", () => {
    expect(formatWait(30)).toBe("Less than a minute to go");
    expect(formatWait(75)).toBe("About a minute to go");
    expect(formatWait(14 * 60)).toBe("About 14 minutes to go");
    expect(formatWait(3 * 3600)).toBe("About 3 hours to go");
  });
});

describe("queueProgress", () => {
  it("measures from the first position seen", () => {
    expect(queueProgress(101, 51)).toBe(50);
    expect(queueProgress(101, 1)).toBe(100);
  });

  it("is full at the front of the line", () => {
    expect(queueProgress(1, 1)).toBe(100);
    expect(queueProgress(null, 1)).toBe(100);
  });

  it("is unknown rather than zero when there is nothing to measure", () => {
    expect(queueProgress(null, 10)).toBeNull();
  });
});
