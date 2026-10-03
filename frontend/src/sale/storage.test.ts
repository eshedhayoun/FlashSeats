import { beforeEach, describe, expect, it } from "vitest";
import {
  clearHoldStorage,
  getAdmissionToken,
  getCheckoutEmail,
  getHoldToken,
  getIdempotencyKey,
  getRecentOrders,
  getSaleValue,
  markPaymentInFlight,
  rememberOrder,
  removeAdmissionToken,
  setAdmissionToken,
  setCheckoutEmail,
  setHoldToken,
  wasPaymentInFlight
} from "./storage";

describe("event-scoped storage", () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  it("keeps values for concurrent sales isolated", () => {
    setHoldToken(1, "hold-a");
    setHoldToken(2, "hold-b");

    expect(getHoldToken(1)).toBe("hold-a");
    expect(getHoldToken(2)).toBe("hold-b");
  });

  it("keeps an admission token when a sale is reopened in another tab", () => {
    setAdmissionToken(1, "admission-a");

    expect(getAdmissionToken(1)).toBe("admission-a");
    expect(sessionStorage.getItem("fs.1.admissionToken")).toBeNull();

    removeAdmissionToken(1);

    expect(getAdmissionToken(1)).toBeNull();
  });

  it("reuses one idempotency key for a hold", () => {
    const first = getIdempotencyKey(1, "hold-a");
    const second = getIdempotencyKey(1, "hold-a");

    expect(second).toBe(first);
    expect(getSaleValue(1, "idem.hold-a")).toBe(first);
  });

  it("does not clear another event when settling a hold", () => {
    setHoldToken(1, "hold-a");
    setHoldToken(2, "hold-b");
    getIdempotencyKey(1, "hold-a");
    getIdempotencyKey(2, "hold-b");

    clearHoldStorage(1, "hold-a");

    expect(getHoldToken(1)).toBeNull();
    expect(getHoldToken(2)).toBe("hold-b");
    expect(getSaleValue(2, "idem.hold-b")).not.toBeNull();
  });

  it("clears everything a settled hold kept, and nothing of another hold", () => {
    setHoldToken(1, "hold-a");
    getIdempotencyKey(1, "hold-a");
    setCheckoutEmail(1, "hold-a", "b@example.com");
    markPaymentInFlight(1, "hold-a");

    clearHoldStorage(1, "hold-a");

    expect(getSaleValue(1, "idem.hold-a")).toBeNull();
    expect(getCheckoutEmail(1, "hold-a")).toBe("");
    expect(wasPaymentInFlight(1, "hold-a")).toBe(false);
  });

  it("leaves a newer hold's token alone when clearing an older one", () => {
    setHoldToken(1, "hold-new");

    clearHoldStorage(1, "hold-old");

    expect(getHoldToken(1)).toBe("hold-new");
  });

  it("lists recent orders newest first, once each", () => {
    rememberOrder({ orderNumber: "TK-1", receiptToken: "r1" }, "Sale A");
    rememberOrder({ orderNumber: "TK-2", receiptToken: "r2" }, "Sale B");
    rememberOrder({ orderNumber: "TK-1", receiptToken: "r1" }, "Sale A");

    expect(getRecentOrders().map((order) => order.orderNumber)).toEqual(["TK-1", "TK-2"]);
  });
});
