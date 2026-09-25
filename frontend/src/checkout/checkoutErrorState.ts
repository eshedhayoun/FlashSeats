import type { ApiError } from "../api/errors";

export type CheckoutErrorState = {
  message: string;
  severity: "error" | "info";
  payDisabled: boolean;
  duplicatePayment: boolean;
  clearHold: boolean;
  refreshSale: boolean;
};

export function checkoutErrorState(error: ApiError): CheckoutErrorState {
  switch (error.code) {
    case "PAYMENT_DECLINED":
      return {
        message: `${error.message} Your seats are still held${
          error.problem.attemptsRemaining == null
            ? ""
            : ` — ${error.problem.attemptsRemaining} attempt(s) left`
        }.`,
        severity: "error",
        payDisabled: false,
        duplicatePayment: false,
        clearHold: false,
        refreshSale: false
      };

    case "PAYMENT_GATEWAY_UNAVAILABLE":
      return {
        message: `${error.message} The payment provider is having trouble. Your seats are still held.`,
        severity: "error",
        payDisabled: false,
        duplicatePayment: false,
        clearHold: false,
        refreshSale: false
      };

    case "PAYMENT_ACTION_REQUIRED":
      return {
        message:
          "Your bank needs to verify the payment. Your seats are still held.",
        severity: "info",
        payDisabled: true,
        duplicatePayment: false,
        clearHold: false,
        refreshSale: false
      };

    case "PAYMENT_ATTEMPTS_EXHAUSTED":
      return terminal(
        `${error.message} Release your seats to start over.`
      );

    case "DUPLICATE_PAYMENT":
      return {
        message: "Finishing a payment that is already in progress…",
        severity: "info",
        payDisabled: true,
        duplicatePayment: true,
        clearHold: false,
        refreshSale: true
      };

    case "INSUFFICIENT_TIME_REMAINING":
      return terminal(
        `${error.message} Release your seats and reserve again.`
      );

    case "HOLD_EXPIRED":
      return {
        ...terminal("Your reservation expired. Nothing was charged."),
        clearHold: true,
        refreshSale: true
      };

    case "ORDER_REFUNDED":
      return {
        ...terminal(
          `${error.message} The charge succeeded but was refunded.`
        ),
        clearHold: true,
        refreshSale: true
      };

    default:
      return {
        message: error.message,
        severity: "error",
        payDisabled: false,
        duplicatePayment: false,
        clearHold: false,
        refreshSale: false
      };
  }
}

function terminal(message: string): CheckoutErrorState {
  return {
    message,
    severity: "error",
    payDisabled: true,
    duplicatePayment: false,
    clearHold: false,
    refreshSale: false
  };
}