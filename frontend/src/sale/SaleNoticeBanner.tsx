import { copyForCode } from "../copy/problemCopy";
import { Notice } from "../ui/Notice";
import type { SaleNoticeKind } from "./notices";
import type { SaleRoute } from "./routeFor";

type View = SaleRoute["view"];

/**
 * The words for what just happened, adjusted to where the buyer has landed: "join the line again"
 * means something on the landing view and nothing on seat selection.
 */
function noticeContent(kind: SaleNoticeKind, view: View, orderNumber?: string) {
  const again =
    view === "select"
      ? " You can choose seats again while your turn lasts."
      : view === "landing"
        ? " Join the line again to get another turn."
        : "";
  switch (kind) {
    case "EXPIRED":
      return { severity: "info" as const, title: "Your reservation ended", text: `Nothing was charged.${again}` };
    case "RELEASED":
      return {
        severity: "success" as const,
        title: "Your seats were released",
        text: `They're back on sale for someone else.${again}`
      };
    case "LEFT_QUEUE":
      return {
        severity: "info" as const,
        title: "You've left the line",
        text: "If you join again, you'll start at the back of the line."
      };
    case "ADMISSION_ENDED":
      return {
        severity: "info" as const,
        title: "Your turn to choose seats ended",
        text: "Nothing was reserved or charged. Join the line again to get another turn."
      };
    case "PLACE_LOST":
      return {
        severity: "info" as const,
        title: "You're no longer in the line",
        text: "Your turn may have come while this page was in the background. Join again to get back in line."
      };
    case "PASS_FAILED":
      return {
        severity: "warning" as const,
        title: "We couldn't let you in",
        text: "Something went wrong as your turn started. Join the line again and we'll get you a new turn."
      };
    case "REFUNDED": {
      const copy = copyForCode("ORDER_REFUNDED");
      return { severity: "warning" as const, title: copy.title, text: withOrder(copy.message, orderNumber) };
    }
    case "REFUND_FAILED": {
      const copy = copyForCode("REFUND_FAILED");
      return { severity: "warning" as const, title: copy.title, text: withOrder(copy.message, orderNumber) };
    }
  }
}

function withOrder(text: string, orderNumber?: string) {
  return orderNumber ? `${text} Your order reference is ${orderNumber}.` : text;
}

export function SaleNoticeBanner({
  kind,
  view,
  orderNumber,
  onClose
}: {
  kind: SaleNoticeKind;
  view: View;
  orderNumber?: string;
  onClose: () => void;
}) {
  const content = noticeContent(kind, view, orderNumber);
  return (
    <Notice severity={content.severity} title={content.title} onClose={onClose} testId={`notice-${kind}`}>
      {content.text}
    </Notice>
  );
}
