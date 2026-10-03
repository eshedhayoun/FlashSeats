import { ApiError, CLIENT_ERROR, NETWORK_ERROR } from "../api/errors";

export type ProblemCopy = {
  /** One short sentence: what happened. */
  title: string;
  /** What it means for the buyer's seats and money, then what to do next. */
  message: string;
};

/**
 * What the buyer is told for every code they can meet (`05` §2, FE_SPEC §7). Calm, in the buyer's
 * terms, and honest about the two things they care about: their seats and their money.
 *
 * Switch on `code` only. The server's `detail` is copy that will change; it is the fallback for a
 * code this table does not know, never the thing a branch decides on.
 */
const COPY: Record<string, ProblemCopy> = {
  VALIDATION_FAILED: {
    title: "Something in the form needs another look",
    message: "Check the details you entered and try again."
  },
  INTERNAL_ERROR: {
    title: "Something went wrong on our side",
    message: "It isn't anything you did. Please try again in a moment."
  },
  SERVICE_BUSY: {
    title: "We're handling a lot of traffic",
    message: "Nothing you've done has been lost. Please try again in a moment."
  },
  NOT_FOUND: {
    title: "We couldn't find that",
    message: "The link may be out of date."
  },
  RATE_LIMITED: {
    title: "We're handling a lot of traffic",
    message: "Please wait a moment and try again. Your place and your seats are not affected."
  },
  BOT_VERIFICATION_FAILED: {
    title: "We couldn't verify your browser",
    message: "Reload the page and try again."
  },
  IP_BLOCKED: {
    title: "We can't take requests from your network",
    message: "If you think this is a mistake, please contact support."
  },
  SESSION_INVALID: {
    title: "Your session needs refreshing",
    message: "Reload the page to continue."
  },
  EVENT_NOT_FOUND: {
    title: "We couldn't find that event",
    message: "It may have been removed. Have a look at the sales that are on now."
  },
  TIER_NOT_FOUND: {
    title: "That ticket type isn't available",
    message: "Pick another tier."
  },
  SALE_NOT_OPEN: {
    title: "The sale hasn't opened yet",
    message: "It opens soon. This page lets you in as soon as it does."
  },
  SALE_CLOSED: {
    title: "This sale has ended",
    message: "Ticket sales for this event are closed."
  },
  SALE_PAUSED: {
    title: "Sales are paused for a moment",
    message: "Your place is kept. You can carry on as soon as they resume."
  },
  INVENTORY_UNAVAILABLE: {
    title: "We're having trouble reading availability",
    message: "The sale is still on, and nothing was reserved. Please try again in a moment."
  },
  QUEUE_PASS_INVALID: {
    title: "We couldn't let you in",
    message: "Your turn couldn't be confirmed. Join the line again and we'll get you in."
  },
  ADMISSION_EXPIRED: {
    title: "Your turn to choose seats has ended",
    message: "Nothing was reserved or charged. Join the line again to try once more."
  },
  ADMISSION_REQUIRED: {
    title: "You need a turn first",
    message: "Join the line to get your turn to choose seats."
  },
  INSUFFICIENT_STOCK: {
    title: "Those seats just sold",
    message: "Pick another tier, or try fewer tickets."
  },
  HOLD_NOT_FOUND: {
    title: "We couldn't find your reservation",
    message: "It may have ended. Nothing was charged."
  },
  HOLD_EXPIRED: {
    title: "Your reservation ended",
    message: "Nothing was charged. You can join the line again."
  },
  HOLD_LIMIT_EXCEEDED: {
    title: "You already have seats reserved",
    message: "Finish that purchase or release those seats first."
  },
  QUANTITY_EXCEEDS_LIMIT: {
    title: "That's more tickets than this tier allows",
    message: "Choose fewer tickets."
  },
  PAYMENT_DECLINED: {
    title: "Your card was declined",
    message: "Try a different card. Your seats are still held."
  },
  PAYMENT_ATTEMPTS_EXHAUSTED: {
    title: "This reservation has no payment attempts left",
    message: "Nothing was charged. Release your seats to start again."
  },
  PAYMENT_ACTION_REQUIRED: {
    title: "Your bank needs to verify this payment",
    message: "Your seats are still held, and verifying doesn't use up an attempt."
  },
  PAYMENT_GATEWAY_UNAVAILABLE: {
    title: "The payment provider is having trouble",
    message: "That's on our side, not yours. Your seats are still held and no attempt was used."
  },
  DUPLICATE_PAYMENT: {
    title: "A payment is already in progress",
    message: "We're finishing it now. Please don't pay again."
  },
  ORDER_NOT_FOUND: {
    title: "We couldn't find that order",
    message: "Use the link from your confirmation email, or open it in the browser you bought with."
  },
  CHECKOUT_WINDOW_CLOSED: {
    title: "This sale has closed",
    message: "It's too late to complete this purchase. Nothing was charged."
  },
  INSUFFICIENT_TIME_REMAINING: {
    title: "Not enough time left to pay safely",
    message: "Nothing was charged. Release your seats and reserve again to get a fresh hold."
  },
  ORDER_REFUNDED: {
    title: "Your payment was refunded",
    message:
      "The payment went through, but your reservation ended before the order could be completed, " +
      "so we refunded it in full. It can take a few days to appear."
  },
  REFUND_FAILED: {
    title: "Our team is returning your payment",
    message:
      "The payment went through but the order couldn't be completed, and the automatic refund " +
      "didn't go through. A member of our team will return it. There's nothing you need to do."
  },
  TICKET_NOT_AVAILABLE: {
    title: "Your ticket isn't ready yet",
    message: "It usually takes a few seconds."
  },
  [NETWORK_ERROR]: {
    title: "We couldn't reach FlashSeats",
    message: "Check your connection. Your place and your seats are kept while you're away."
  },
  [CLIENT_ERROR]: {
    title: "Something went wrong",
    message: "Please try again."
  }
};

/** Every code a buyer can meet, for the test that keeps this table complete. */
export const BUYER_CODES = Object.keys(COPY);

export function problemCopy(error: ApiError): ProblemCopy {
  return (
    COPY[error.code] ?? {
      title: error.problem.title || "Something went wrong",
      message: error.problem.detail
    }
  );
}

/** The copy for a code on its own, for a state that arrived without a problem document. */
export function copyForCode(code: string): ProblemCopy {
  return COPY[code] ?? COPY[CLIENT_ERROR];
}

/** A reference worth quoting to support: only for server faults, where someone will need it. */
export function supportReference(error: ApiError): string | null {
  return error.status >= 500 && error.problem.traceId ? error.problem.traceId : null;
}
