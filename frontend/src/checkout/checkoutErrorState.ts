import { NETWORK_ERROR, type ApiError } from "../api/errors";
import { problemCopy, type ProblemCopy } from "../copy/problemCopy";
import type { SaleNoticeKind } from "../sale/notices";

/**
 * What one checkout failure means for the screen. Every row answers the two questions FE_SPEC §3 asks
 * of each code: may the buyer press Pay again, and do they still have their seats.
 */
export type CheckoutErrorState = {
  copy: ProblemCopy;
  severity: "error" | "warning" | "info";
  /** Whether Pay can do anything. A button that cannot succeed is disabled, never left to be mashed. */
  pay: "enabled" | "disabled";
  /** Pay comes back only after this many seconds: the provider asked for a pause. */
  waitSeconds: number | null;
  /** A charge is in flight elsewhere: wait for it, polling the sale state. */
  duplicatePayment: boolean;
  /** The reservation is over. Hand the buyer back with this notice — the server decides where. */
  ended: SaleNoticeKind | null;
  /** The seats are still held but cannot be paid for: releasing them is the way forward. */
  offerRelease: boolean;
};

const base = {
  severity: "error" as const,
  pay: "enabled" as const,
  waitSeconds: null,
  duplicatePayment: false,
  ended: null,
  offerRelease: false
};

/** Our side, not theirs: a later try is the same request, and it costs them nothing. */
function noAttemptUsed(copy: ProblemCopy): ProblemCopy {
  return { title: copy.title, message: `${copy.message} Your seats are still held, and no payment attempt was used.` };
}

export function checkoutErrorState(error: ApiError): CheckoutErrorState {
  const copy = problemCopy(error);

  switch (error.code) {
    case "PAYMENT_DECLINED": {
      const left = error.problem.attemptsRemaining;
      const attempts = left == null ? "" : ` You have ${left} ${left === 1 ? "attempt" : "attempts"} left.`;
      return { ...base, copy: { title: copy.title, message: `${copy.message}${attempts}` } };
    }

    case "PAYMENT_GATEWAY_UNAVAILABLE":
      return { ...base, copy, severity: "warning", waitSeconds: error.problem.retryAfterSeconds ?? null };

    case "PAYMENT_ACTION_REQUIRED":
      return { ...base, copy, severity: "info", pay: "disabled" };

    case "PAYMENT_ATTEMPTS_EXHAUSTED":
      return { ...base, copy, pay: "disabled", offerRelease: true };

    case "DUPLICATE_PAYMENT":
      return { ...base, copy, severity: "info", pay: "disabled", duplicatePayment: true };

    // The seats are still held; the server refuses to START a charge it could not finish in time.
    // Re-routing would land the buyer straight back here, so the way out is release (FE_SPEC §3).
    case "INSUFFICIENT_TIME_REMAINING":
    case "CHECKOUT_WINDOW_CLOSED":
      return { ...base, copy, severity: "warning", pay: "disabled", offerRelease: true };

    case "HOLD_EXPIRED":
    case "HOLD_NOT_FOUND":
      return { ...base, copy, pay: "disabled", ended: "EXPIRED" };

    case "ORDER_REFUNDED":
      return { ...base, copy, severity: "warning", pay: "disabled", ended: "REFUNDED" };

    // Never "refunded": the provider refused it, and the money is with a person (ADR-069).
    case "REFUND_FAILED":
      return { ...base, copy, severity: "warning", pay: "disabled", ended: "REFUND_FAILED" };

    // The answer never arrived, so whether the charge went through is unknown. A retry is the same
    // request on the same hold: it finds a settled charge rather than making another (ADR-064).
    case NETWORK_ERROR:
      return {
        ...base,
        severity: "warning",
        copy: {
          title: "The connection dropped",
          message: "We couldn't tell whether your payment went through. Try again — you won't be charged twice for these seats."
        }
      };

    case "SERVICE_BUSY":
    case "RATE_LIMITED":
    case "INVENTORY_UNAVAILABLE":
      return { ...base, severity: "warning", copy: noAttemptUsed(copy) };

    default:
      return { ...base, copy };
  }
}
