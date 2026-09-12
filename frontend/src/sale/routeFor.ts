import type { SaleState } from "../api/types";

export type SaleView =
  | "landing"
  | "queue"
  | "select"
  | "checkout"
  | "confirmation"
  | "sold-out"
  | "closed";

export function routeFor(state: SaleState): SaleView {
  if (state.hold) return "checkout";
  if (state.queue?.state === "ADMITTED") return "select";
  if (state.queue?.state === "PROMOTED") return "select";
  if (state.queue?.state === "WAITING") return "queue";
  if (state.order?.status === "CONFIRMED") return "confirmation";
  if (state.queue?.state === "EXHAUSTED") return "sold-out";
  if (state.windowStatus === "CLOSED") return "closed";
  return "landing";
}
