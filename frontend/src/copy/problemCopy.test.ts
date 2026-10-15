import { describe, expect, it } from "vitest";
import { ApiError } from "../api/errors";
import { BUYER_CODES, problemCopy, supportReference } from "./problemCopy";

const error = (code: string, status = 409, extra: Record<string, unknown> = {}) =>
  new ApiError({ type: "about:blank", title: "T", status, detail: "server detail", code, ...extra });

/** Every registry code a buyer's browser can receive (`05` §2), minus the admin- and provider-only ones. */
const REACHABLE = [
  "VALIDATION_FAILED", "INTERNAL_ERROR", "SERVICE_BUSY", "NOT_FOUND", "RATE_LIMITED",
  "BOT_VERIFICATION_FAILED", "IP_BLOCKED", "SESSION_INVALID", "EVENT_NOT_FOUND", "TIER_NOT_FOUND",
  "SALE_NOT_OPEN", "SALE_CLOSED", "SALE_PAUSED", "INVENTORY_UNAVAILABLE", "QUEUE_PASS_INVALID",
  "ADMISSION_EXPIRED", "ADMISSION_REQUIRED", "INSUFFICIENT_STOCK", "HOLD_NOT_FOUND", "HOLD_EXPIRED",
  "HOLD_LIMIT_EXCEEDED", "QUANTITY_EXCEEDS_LIMIT", "PAYMENT_DECLINED", "PAYMENT_ATTEMPTS_EXHAUSTED",
  "PAYMENT_ACTION_REQUIRED", "PAYMENT_GATEWAY_UNAVAILABLE", "DUPLICATE_PAYMENT", "ORDER_NOT_FOUND",
  "CHECKOUT_WINDOW_CLOSED", "INSUFFICIENT_TIME_REMAINING", "ORDER_REFUNDED", "REFUND_FAILED",
  "TICKET_NOT_AVAILABLE"
];

describe("problemCopy", () => {
  it("has buyer copy for every code a browser can receive", () => {
    expect(REACHABLE.filter((code) => !BUYER_CODES.includes(code))).toEqual([]);
  });

  it("never calls a missing counter sold out", () => {
    const copy = problemCopy(error("INVENTORY_UNAVAILABLE", 503));
    expect(`${copy.title} ${copy.message}`.toLowerCase()).not.toContain("sold");
  });

  it("never says 'refunded' when the refund did not go through", () => {
    const copy = problemCopy(error("REFUND_FAILED"));
    expect(copy.title.toLowerCase()).not.toContain("refunded");
    expect(copy.message).not.toMatch(/\brefunded\b/);
  });

  it("never accuses a rate-limited buyer", () => {
    const copy = problemCopy(error("RATE_LIMITED", 429));
    expect(`${copy.title} ${copy.message}`.toLowerCase()).not.toMatch(/bot|abuse|suspicious/);
  });

  it("falls back to the server's words for a code it does not know", () => {
    expect(problemCopy(error("SOMETHING_NEW")).message).toBe("server detail");
  });

  it("offers a support reference only for server faults", () => {
    expect(supportReference(error("INTERNAL_ERROR", 500, { traceId: "abc" }))).toBe("abc");
    expect(supportReference(error("PAYMENT_DECLINED", 402, { traceId: "abc" }))).toBeNull();
  });
});
