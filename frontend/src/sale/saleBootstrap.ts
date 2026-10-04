import { getSaleState } from "../api/endpoints";
import type { SaleState } from "../api/types";
import { routeFor, type SaleRoute } from "./routeFor";
import { clearHoldStorage, getHoldToken, removeAdmissionToken, removeQueueStart, setHoldToken } from "./storage";

export type BootstrappedSale = {
  state: SaleState;
  route: SaleRoute;
};

/** The recovery protocol (FE_SPEC §3): ask the server, align the tab's hints with it, route. */
export async function bootstrapSale(
  eventId: number,
  options: { buyMore?: boolean } = {}
): Promise<BootstrappedSale> {
  const state = await getSaleState(eventId);
  synchronizeStorage(eventId, state);

  return {
    state,
    route: routeFor(state, options)
  };
}

/**
 * Drops what the server says is over, and only that. A section the server could not read is left
 * alone: "no hold" and "could not read holds" are different facts (FE_SPEC §3, `partial`).
 */
function synchronizeStorage(eventId: number, state: SaleState): void {
  if (!state.partial.includes("hold")) {
    const stored = getHoldToken(eventId);
    if (state.hold) {
      setHoldToken(eventId, state.hold.holdToken);
    } else if (stored) {
      clearHoldStorage(eventId, stored);
    }
  }

  if (state.partial.includes("queue")) return;

  const phase = state.queue?.state ?? "NOT_JOINED";
  if (phase !== "ADMITTED" && phase !== "PROMOTED") {
    removeAdmissionToken(eventId);
  }
  if (phase !== "WAITING") {
    removeQueueStart(eventId);
  }
}
