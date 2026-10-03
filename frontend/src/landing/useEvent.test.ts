import { describe, expect, it } from "vitest";
import { upcomingPollDelayMs } from "./useEvent";

describe("upcomingPollDelayMs", () => {
  it("polls every 30 s while the opening is far away", () => {
    expect(upcomingPollDelayMs(10 * 60_000)).toBe(30_000);
  });

  it("lands the last slow poll on the start of the final minute", () => {
    expect(upcomingPollDelayMs(75_000)).toBe(15_250);
  });

  it("polls every 10 s in the final minute, and just after the opening instant", () => {
    expect(upcomingPollDelayMs(45_000)).toBe(10_000);
    expect(upcomingPollDelayMs(4_000)).toBe(4_250);
  });

  it("keeps asking every 2 s once the clock says open and the server has not yet", () => {
    expect(upcomingPollDelayMs(-500)).toBe(2_000);
  });
});
