import { describe, expect, it } from "vitest";
import { decideTimerZero } from "./checkoutTimer";

describe("decideTimerZero", () => {
  it("keeps the buyer in completing state while payment is in flight", () => {
    expect(decideTimerZero(true)).toBe("complete-payment");
  });

  it("rehydrates sale state when no payment is in flight", () => {
    expect(decideTimerZero(false)).toBe("rehydrate");
  });
});
