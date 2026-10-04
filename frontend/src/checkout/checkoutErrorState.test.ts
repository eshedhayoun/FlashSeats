import { describe, expect, it } from "vitest";
import { ApiError, NETWORK_ERROR } from "../api/errors";
import { checkoutErrorState } from "./checkoutErrorState";

function error(code: string, extra: Record<string, unknown> = {}) {
  return new ApiError({ type: "about:blank", title: "Checkout error", status: 409, detail: "detail", code, ...extra });
}

/** FE_SPEC §3 "Checkout error handling — one rule per code": the Pay button and the seats. */
describe("checkoutErrorState", () => {
  it("keeps seats and Pay after a decline, and says how many attempts are left", () => {
    const state = checkoutErrorState(error("PAYMENT_DECLINED", { attemptsRemaining: 2 }));

    expect(state.pay).toBe("enabled");
    expect(state.ended).toBeNull();
    expect(state.copy.message).toContain("2 attempts left");
    expect(state.copy.message).toContain("seats are still held");
  });

  it("waits out the provider's pause, and says no attempt was used", () => {
    const state = checkoutErrorState(error("PAYMENT_GATEWAY_UNAVAILABLE", { retryAfterSeconds: 5 }));

    expect(state.pay).toBe("enabled");
    expect(state.waitSeconds).toBe(5);
    expect(state.copy.message).toContain("no attempt was used");
  });

  it("disables Pay and offers release once attempts are exhausted", () => {
    const state = checkoutErrorState(error("PAYMENT_ATTEMPTS_EXHAUSTED"));

    expect(state.pay).toBe("disabled");
    expect(state.offerRelease).toBe(true);
    expect(state.ended).toBeNull();
  });

  it("disables Pay and polls while a duplicate payment completes", () => {
    const state = checkoutErrorState(error("DUPLICATE_PAYMENT"));

    expect(state.pay).toBe("disabled");
    expect(state.duplicatePayment).toBe(true);
  });

  it("keeps the hold after insufficient time: release, never re-route", () => {
    const state = checkoutErrorState(error("INSUFFICIENT_TIME_REMAINING"));

    expect(state.pay).toBe("disabled");
    expect(state.offerRelease).toBe(true);
    expect(state.ended).toBeNull();
    expect(state.copy.message).toContain("Nothing was charged");
  });

  it("ends the reservation on expiry, saying nothing was charged", () => {
    const state = checkoutErrorState(error("HOLD_EXPIRED"));

    expect(state.ended).toBe("EXPIRED");
    expect(state.copy.message).toContain("Nothing was charged");
  });

  it("says refunded only when the refund happened", () => {
    expect(checkoutErrorState(error("ORDER_REFUNDED")).ended).toBe("REFUNDED");
    const failed = checkoutErrorState(error("REFUND_FAILED"));
    expect(failed.ended).toBe("REFUND_FAILED");
    expect(`${failed.copy.title} ${failed.copy.message}`).not.toMatch(/\brefunded\b/);
  });

  it("never reads back-pressure or a missing counter as a decline or a sell-out", () => {
    for (const code of ["SERVICE_BUSY", "RATE_LIMITED", "INVENTORY_UNAVAILABLE"]) {
      const state = checkoutErrorState(error(code));
      expect(state.pay).toBe("enabled");
      expect(state.copy.message).toContain("no payment attempt was used");
      expect(state.copy.message.toLowerCase()).not.toContain("sold");
    }
  });

  it("promises no double charge when the connection drops mid-payment", () => {
    const state = checkoutErrorState(error(NETWORK_ERROR));

    expect(state.pay).toBe("enabled");
    expect(state.copy.message).toContain("won't be charged twice");
  });
});
