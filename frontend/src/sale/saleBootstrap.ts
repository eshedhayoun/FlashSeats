import { getSaleState } from "../api/endpoints";
import type { SaleState } from "../api/types";
import {
  getHoldToken,
  removeAdmissionToken,
  removeSaleValue,
  setHoldToken
} from "./storage";
import { routeFor, type SaleRoute } from "./routeFor";

export type BootstrappedSale = {
  state: SaleState;
  route: SaleRoute;
};

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

function synchronizeStorage(eventId: number, state: SaleState): void {
  const holdUnreadable = state.partial.includes("hold");
  const queueUnreadable = state.partial.includes("queue");
  const existingHoldToken = getHoldToken(eventId);

  if (holdUnreadable) {
    return;
  }

  if (state.hold) {
    setHoldToken(eventId, state.hold.holdToken);
  } else {
    removeSaleValue(eventId, "holdToken");
    if (existingHoldToken) {
      removeSaleValue(eventId, `idem.${existingHoldToken}`);
    }
  }

  if (queueUnreadable) return;

  if (
    !state.queue ||
    state.queue.state === "NOT_JOINED" ||
    state.queue.state === "WAITING" ||
    state.queue.state === "EXHAUSTED" ||
    state.queue.state === "CLOSED"
  ) {
    removeAdmissionToken(eventId);
  }
}
