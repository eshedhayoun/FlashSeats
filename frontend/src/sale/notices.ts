import type { BootstrappedSale } from "./saleBootstrap";
import type { SaleRoute } from "./routeFor";

export type SaleView = SaleRoute["view"];

/**
 * What just happened to the buyer, said on the next screen. Without these, a reservation that ran out
 * or a turn that ended looked like the page had simply reset — the buyer was never told whether they
 * had been charged (FE_SPEC V6: "Nothing was charged" is not optional).
 */
export type SaleNoticeKind =
  | "EXPIRED"
  | "RELEASED"
  | "LEFT_QUEUE"
  | "ADMISSION_ENDED"
  | "PLACE_LOST"
  | "PASS_FAILED"
  | "REFUNDED"
  | "REFUND_FAILED";

/**
 * The notice a move between two views implies, when no action of the buyer's explains it. A buyer's
 * own action (release, leave) supplies its own and wins.
 */
export function noticeForTransition(previous: SaleView, next: BootstrappedSale): SaleNoticeKind | null {
  const view = next.route.view;
  if (previous === view) return null;

  if (previous === "checkout" && view !== "confirmation") {
    const status = next.state.order?.status;
    if (status === "REFUNDED") return "REFUNDED";
    if (status === "REFUND_FAILED") return "REFUND_FAILED";
    // Only a reservation that is gone. A section the server could not read is not news.
    return view === "degraded" ? null : "EXPIRED";
  }
  if (previous === "select" && view === "landing") return "ADMISSION_ENDED";
  if ((previous === "queue" || previous === "promoted") && view === "landing") return "PLACE_LOST";
  return null;
}
