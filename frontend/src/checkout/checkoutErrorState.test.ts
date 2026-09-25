import { describe, expect, it } from "vitest";
import { ApiError } from "../api/errors";
import { checkoutErrorState } from "./checkoutErrorState";

function error(code: string, detail = "Payment failed", extra = {}) {
  return new ApiError({
    type: "about:blank",
    title: "Checkout error",
    status: 409,
    detail,
    code,
    ...extra
  });
}

describe("checkoutErrorState", () => {
  it("keeps seats and enables retry after a decline", () => {
    const state = checkoutErrorState(
      error("PAYMENT_DECLINED", "Card declined", { attemptsRemaining: 2 })
    );

    expect(state.payDisabled).toBe(false);
    expect(state.message).toContain("2 attempt(s) left");
    expect(state.clearHold).toBe(false);
  });

  it("disables payment and refreshes while a duplicate payment completes", () => {
    const state = checkoutErrorState(error("DUPLICATE_PAYMENT"));

    expect(state.payDisabled).toBe(true);
    expect(state.duplicatePayment).toBe(true);
    expect(state.refreshSale).toBe(true);
  });

  it("keeps the hold after insufficient time", () => {
    const state = checkoutErrorState(error("INSUFFICIENT_TIME_REMAINING"));

    expect(state.payDisabled).toBe(true);
    expect(state.clearHold).toBe(false);
  });

  it("clears an expired hold and refreshes sale state", () => {
    const state = checkoutErrorState(error("HOLD_EXPIRED"));

    expect(state.clearHold).toBe(true);
    expect(state.refreshSale).toBe(true);
    expect(state.message).toContain("Nothing was charged");
  });
});
