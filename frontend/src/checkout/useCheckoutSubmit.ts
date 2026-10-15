import { useCallback, useEffect, useRef, useState } from "react";
import { checkout, releaseHold } from "../api/endpoints";
import { NETWORK_ERROR, asApiError, type ApiError } from "../api/errors";
import type { ActiveHold, CheckoutRequest, OrderReceipt } from "../api/types";
import type { SaleNoticeKind } from "../sale/notices";
import {
  clearHoldStorage,
  clearPaymentInFlight,
  getCheckoutEmail,
  getIdempotencyKey,
  markPaymentInFlight,
  setCheckoutEmail,
  wasPaymentInFlight
} from "../sale/storage";
import { checkoutErrorState, type CheckoutErrorState } from "./checkoutErrorState";
import { decideTimerZero } from "./checkoutTimer";

/**
 * How a payment method is obtained, and how a bank challenge is answered. Stripe's element and the
 * stub's outcome picker differ only in these two steps; everything after them is one journey.
 */
export type PaymentDriver = {
  ready: boolean;
  createPaymentMethod(email: string): Promise<{ paymentMethodId?: string; error?: string }>;
  /**
   * Answers a `PAYMENT_ACTION_REQUIRED`. The stub has no bank page — re-posting the same body is the
   * whole retry, and the server retrieves the waiting intent (ADR-054) — so it resolves at once.
   */
  authenticate(clientSecret: string): Promise<{ error?: string }>;
};

/**
 * - `ready` — the form is the buyer's.
 * - `paying` — a payment is on its way; Pay is disabled, never debounced (FE_SPEC §6).
 * - `verifying` — the bank's challenge is open. Still in flight: the expiry branch stays frozen.
 * - `finishing` — a payment may be completing elsewhere (a duplicate, or a reload mid-charge).
 */
export type CheckoutPhase = "ready" | "paying" | "verifying" | "finishing";

export type CheckoutMessage = { severity: "error" | "warning" | "info"; title: string; text: string };

/** Checked for shape only: it catches `foo@@bar`, never `jhon@gmial.com` (FE_SPEC V4). */
const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const DUPLICATE_POLL_MS = 2_000;
/** How long a payment that may be finishing is waited for before the buyer may try again. */
const FINISHING_PATIENCE_MS = 30_000;

export function isEmailShaped(email: string): boolean {
  return EMAIL_SHAPE.test(email.trim()) && email.trim().length <= 255;
}

export function useCheckoutSubmit({
  eventId,
  hold,
  driver,
  onRefresh,
  onCompleted,
  onEnded,
  onReleased
}: {
  eventId: number;
  hold: ActiveHold;
  driver: PaymentDriver;
  onRefresh: () => Promise<void>;
  onCompleted: (receipt: OrderReceipt) => void;
  onEnded: (notice: SaleNoticeKind) => Promise<void>;
  onReleased: () => Promise<void>;
}) {
  const holdToken = hold.holdToken;

  const [email, setEmailValue] = useState(() => getCheckoutEmail(eventId, holdToken));
  const [emailTouched, setEmailTouched] = useState(false);
  const [phase, setPhase] = useState<CheckoutPhase>(() =>
    wasPaymentInFlight(eventId, holdToken) ? "finishing" : "ready"
  );
  const [failure, setFailure] = useState<CheckoutErrorState | null>(null);
  const [message, setMessage] = useState<CheckoutMessage | null>(() =>
    wasPaymentInFlight(eventId, holdToken)
      ? {
          severity: "info",
          title: "Checking on the payment you started",
          text: "If it went through, your order will appear in a moment. Please don't pay again yet."
        }
      : null
  );
  const [expiresAt, setExpiresAt] = useState(hold.expiresAt);
  const [retryAt, setRetryAt] = useState<number | null>(null);
  const [releasing, setReleasing] = useState(false);
  const [releaseError, setReleaseError] = useState<ApiError | null>(null);
  const inFlight = useRef(false);

  // The server's expiry wins whenever it speaks: a refresh, or a 402/409 carrying the grace (U-12).
  useEffect(() => setExpiresAt(hold.expiresAt), [hold.expiresAt]);

  const setEmail = (value: string) => {
    setEmailValue(value);
    setCheckoutEmail(eventId, holdToken, value);
  };

  // A payment that may be finishing elsewhere: ask every 2 s, and after a while let the buyer try
  // again — the retry is the same request, so it cannot charge twice (ADR-064).
  useEffect(() => {
    if (phase !== "finishing") return;
    const poll = window.setInterval(() => void onRefresh(), DUPLICATE_POLL_MS);
    const patience = window.setTimeout(() => {
      clearPaymentInFlight(eventId, holdToken);
      setFailure(null);
      setMessage({
        severity: "info",
        title: "Your last payment hasn't finished yet",
        text: "You can try again now. If the first one went through, you won't be charged twice."
      });
      setPhase("ready");
    }, FINISHING_PATIENCE_MS);
    return () => {
      window.clearInterval(poll);
      window.clearTimeout(patience);
    };
  }, [phase, eventId, holdToken, onRefresh]);

  const emailValid = isEmailShaped(email);

  const submit = useCallback(async () => {
    if (inFlight.current) return; // disabled on click, never debounced (FE_SPEC §6)
    if (!emailValid) {
      setEmailTouched(true);
      return;
    }
    if (!driver.ready) return;

    inFlight.current = true;
    setPhase("paying");
    setFailure(null);
    setMessage(null);

    try {
      const method = await driver.createPaymentMethod(email.trim());
      if (method.error || !method.paymentMethodId) {
        setMessage({
          severity: "error",
          title: "Check your payment details",
          text: method.error ?? "Your payment details couldn't be processed."
        });
        setPhase("ready");
        return;
      }

      // ONE idempotency key per hold, reused by every retry of it (FE_SPEC V4, ADR-054).
      const request: CheckoutRequest = {
        holdToken,
        userEmail: email.trim(),
        paymentMethodId: method.paymentMethodId,
        idempotencyKey: getIdempotencyKey(eventId, holdToken)
      };
      markPaymentInFlight(eventId, holdToken);

      let receipt: OrderReceipt;
      try {
        receipt = await checkout(request);
      } catch (cause) {
        const error = asApiError(cause);
        const clientSecret = error.problem.clientSecret;
        if (error.code !== "PAYMENT_ACTION_REQUIRED" || !clientSecret) throw error;

        // 3-D Secure: still in flight for the whole challenge, so the expiry branch stays frozen,
        // and no attempt is used (FE_SPEC V4).
        if (error.problem.expiresAt) setExpiresAt(error.problem.expiresAt);
        setPhase("verifying");
        const challenge = await driver.authenticate(clientSecret);
        if (challenge.error) {
          clearPaymentInFlight(eventId, holdToken);
          setMessage({
            severity: "error",
            title: "Your bank couldn't verify the payment",
            text: `${challenge.error} Your seats are still held, and no attempt was used.`
          });
          setPhase("ready");
          return;
        }
        setPhase("paying");
        // The SAME body. There is no resume endpoint, and the client must not invent one (ADR-054).
        receipt = await checkout(request);
      }

      clearHoldStorage(eventId, holdToken);
      onCompleted(receipt);
    } catch (cause) {
      const error = asApiError(cause, "That payment couldn't be completed. Please try again.");
      // A dropped connection is the one failure with no answer: the charge may still be finishing,
      // so the flag stays and a reload says so.
      if (error.code !== NETWORK_ERROR) clearPaymentInFlight(eventId, holdToken);
      if (error.problem.expiresAt) setExpiresAt(error.problem.expiresAt);

      const next = checkoutErrorState(error);
      if (next.ended) {
        clearHoldStorage(eventId, holdToken);
        await onEnded(next.ended);
        return;
      }
      setFailure(next);
      setRetryAt(next.waitSeconds ? Date.now() + next.waitSeconds * 1000 : null);
      setPhase(next.duplicatePayment ? "finishing" : "ready");
    } finally {
      inFlight.current = false;
    }
  }, [driver, email, emailValid, eventId, holdToken, onCompleted, onEnded]);

  /** At 00:00: a payment in flight will finish and must not be told otherwise; anything else asks. */
  const onTimerZero = useCallback(() => {
    if (decideTimerZero(phase !== "ready") === "complete-payment") {
      setMessage({ severity: "info", title: "Completing your purchase…", text: "Please keep this page open." });
      return;
    }
    setMessage({ severity: "info", title: "Checking your reservation…", text: "One moment." });
    void onRefresh();
  }, [phase, onRefresh]);

  const release = useCallback(async () => {
    setReleasing(true);
    setReleaseError(null);
    try {
      await releaseHold(holdToken);
    } catch (cause) {
      const error = asApiError(cause);
      // Already gone is what the buyer asked for.
      if (error.code !== "HOLD_NOT_FOUND" && error.code !== "HOLD_EXPIRED") {
        setReleaseError(error);
        setReleasing(false);
        return;
      }
    }
    clearHoldStorage(eventId, holdToken);
    await onReleased();
  }, [eventId, holdToken, onReleased]);

  return {
    email,
    setEmail,
    emailValid,
    emailTouched,
    touchEmail: () => setEmailTouched(true),
    phase,
    failure,
    message,
    expiresAt,
    retryAt,
    submit,
    onTimerZero,
    release,
    releasing,
    releaseError
  };
}
