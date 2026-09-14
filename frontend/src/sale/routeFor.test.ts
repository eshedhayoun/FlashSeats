import { describe, expect, it } from "vitest";
import type { SaleState } from "../api/types";
import { routeFor } from "./routeFor";

const baseState: SaleState = {
  eventId: 1,
  windowStatus: "OPEN",
  serverTime: "2026-09-13T12:00:00Z",
  queue: null,
  hold: null,
  order: null,
  partial: []
};

describe("routeFor", () => {
  it("prioritizes an active hold over a closed sale", () => {
    const state: SaleState = {
      ...baseState,
      windowStatus: "CLOSED",
      hold: {
        holdToken: "hold",
        tierId: 10,
        quantity: 2,
        expiresAt: "2026-09-13T12:05:00Z",
        ttlRemainingSeconds: 300
      }
    };

    expect(routeFor(state)).toEqual({
      view: "checkout",
      hold: state.hold
    });
  });

  it("routes a promotion to admission instead of selection", () => {
    const state: SaleState = {
      ...baseState,
      queue: {
        state: "PROMOTED",
        position: null,
        estWaitSeconds: null,
        admissionExpiresAt: null,
        passToken: "pass"
      }
    };

    expect(routeFor(state)).toEqual({ view: "promoted", passToken: "pass" });
  });

  it("does not treat an unreadable queue as not joined", () => {
    const state: SaleState = {
      ...baseState,
      partial: ["queue"]
    };

    expect(routeFor(state)).toEqual({
      view: "degraded",
      sections: ["queue"]
    });
  });

  it("keeps queue state ahead of a previous confirmed order", () => {
    const state: SaleState = {
      ...baseState,
      queue: {
        state: "WAITING",
        position: 4,
        estWaitSeconds: 30,
        admissionExpiresAt: null,
        passToken: null
      },
      order: { orderNumber: "TK-1", status: "CONFIRMED" }
    };

    expect(routeFor(state).view).toBe("queue");
  });

  it("distinguishes sold out from closed", () => {
    expect(
      routeFor({
        ...baseState,
        queue: {
          state: "EXHAUSTED",
          position: null,
          estWaitSeconds: null,
          admissionExpiresAt: null,
          passToken: null
        }
      })
    ).toEqual({ view: "terminal", reason: "SOLD_OUT" });

    expect(
      routeFor({ ...baseState, windowStatus: "CLOSED" })
    ).toEqual({ view: "terminal", reason: "SALE_CLOSED" });
  });
});
