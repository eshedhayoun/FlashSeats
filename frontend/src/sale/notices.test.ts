import { describe, expect, it } from "vitest";
import type { SaleState } from "../api/types";
import { noticeForTransition } from "./notices";
import { routeFor } from "./routeFor";

const base: SaleState = {
  eventId: 1,
  windowStatus: "OPEN",
  serverTime: "2026-10-03T10:00:00Z",
  queue: null,
  hold: null,
  order: null,
  partial: []
};

const sale = (state: Partial<SaleState>) => {
  const full = { ...base, ...state };
  return { state: full, route: routeFor(full) };
};

const checkout = "checkout" as const;

describe("noticeForTransition", () => {
  it("says nothing was charged when a reservation simply ran out", () => {
    expect(noticeForTransition(checkout, sale({}))).toBe("EXPIRED");
    expect(
      noticeForTransition(checkout, sale({ queue: { state: "ADMITTED", position: null, estWaitSeconds: null, admissionExpiresAt: null, passToken: null } }))
    ).toBe("EXPIRED");
  });

  it("says a refund happened when the order says so, never 'nothing was charged'", () => {
    expect(noticeForTransition(checkout, sale({ order: { orderNumber: "TK-1", status: "REFUNDED" } }))).toBe("REFUNDED");
    expect(noticeForTransition(checkout, sale({ order: { orderNumber: "TK-1", status: "REFUND_FAILED" } }))).toBe(
      "REFUND_FAILED"
    );
  });

  it("says nothing when the reservation became a purchase", () => {
    expect(noticeForTransition(checkout, sale({ order: { orderNumber: "TK-1", status: "CONFIRMED" } }))).toBeNull();
  });

  it("does not report an unreadable section as an ending", () => {
    expect(noticeForTransition(checkout, sale({ partial: ["hold"] }))).toBeNull();
  });

  it("explains a turn that ended and a place that is gone", () => {
    expect(noticeForTransition("select", sale({}))).toBe("ADMISSION_ENDED");
    expect(noticeForTransition("queue", sale({}))).toBe("PLACE_LOST");
  });

  it("is silent when nothing changed", () => {
    expect(noticeForTransition("landing", sale({}))).toBeNull();
  });
});
