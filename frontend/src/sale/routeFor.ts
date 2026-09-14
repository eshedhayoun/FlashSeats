import type { SaleState } from "../api/types";

export type TerminalReason = "SOLD_OUT" | "SALE_CLOSED";

export type SaleRoute =
  | { view: "checkout"; hold: NonNullable<SaleState["hold"]> }
  | { view: "select"; admissionExpiresAt: string | null }
  | { view: "promoted"; passToken: string }
  | { view: "queue"; queue: NonNullable<SaleState["queue"]> }
  | { view: "confirmation"; orderNumber: string }
  | { view: "terminal"; reason: TerminalReason }
  | { view: "landing" }
  | { view: "degraded"; sections: string[] };

function isPartial(state: SaleState, section: string) {
  return state.partial.includes(section);
}

export function routeFor(
  state: SaleState,
  options: { buyMore?: boolean } = {}
): SaleRoute {
  if (state.hold) {
    return { view: "checkout", hold: state.hold };
  }

  if (isPartial(state, "hold")) {
    return { view: "degraded", sections: state.partial };
  }

  if (state.queue?.state === "ADMITTED") {
    return {
      view: "select",
      admissionExpiresAt: state.queue.admissionExpiresAt
    };
  }

  if (state.queue?.state === "PROMOTED" && state.queue.passToken) {
    return { view: "promoted", passToken: state.queue.passToken };
  }

  if (isPartial(state, "queue")) {
    return { view: "degraded", sections: state.partial };
  }

  if (state.queue?.state === "WAITING") {
    return { view: "queue", queue: state.queue };
  }

  if (!options.buyMore && state.order?.status === "CONFIRMED") {
    return {
      view: "confirmation",
      orderNumber: state.order.orderNumber
    };
  }

  if (isPartial(state, "order")) {
    return { view: "degraded", sections: state.partial };
  }

  if (state.queue?.state === "EXHAUSTED") {
    return { view: "terminal", reason: "SOLD_OUT" };
  }

  if (state.windowStatus === "CLOSED" || state.queue?.state === "CLOSED") {
    return { view: "terminal", reason: "SALE_CLOSED" };
  }

  return { view: "landing" };
}
