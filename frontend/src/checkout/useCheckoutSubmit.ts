import { useEffect, useMemo, useRef, useState } from "react";

import { checkout, releaseHold } from "../api/endpoints";
import { ApiError } from "../api/errors";
import type { ActiveHold, EventDetails } from "../api/types";
import { serverClock } from "../clock/serverClock";
import { useClockTick } from "../clock/useClockTick";
import { checkoutErrorState } from "./checkoutErrorState";
import { decideTimerZero } from "./checkoutTimer";
import {
  clearHoldStorage,
  getHoldToken,
  getIdempotencyKey
} from "../sale/storage";

/**
 * How a payment method is obtained, and how a bank challenge is answered.
 *
 * <p>The two implementations — Stripe's PaymentElement and the stub's magic
 * tokens — differ only in these two steps. Everything after them is the same
 * journey and lives here: one POST, the 3-D Secure re-post, and the error table
 * every `problem.code` maps through.
 */
export type PaymentDriver = {
  /** True once the driver can take a payment. */
  ready: boolean;
  /** Resolves a provider payment-method id, or a message to show the buyer. */
  createPaymentMethod(
    email: string
  ): Promise<{ paymentMethodId?: string; error?: string }>;
  /**
   * Answers the bank challenge for a `PAYMENT_ACTION_REQUIRED`.
   *
   * The stub has no bank page: re-posting the same body IS the retry, and the
   * server retrieves the pending intent rather than opening a second one
   * (ADR-054). So its implementation resolves without doing anything.
   */
  authenticate(clientSecret: string): Promise<{ error?: string }>;
};

export function useCheckoutSubmit({
  event,
  eventId,
  hold,
  driver,
  onRefresh,
  onCompleted
}: {
  event: EventDetails;
  eventId: number;
  hold: ActiveHold;
  driver: PaymentDriver;
  onRefresh: () => void;
  onCompleted: (orderNumber: string) => void;
}) {
  useClockTick();

  const tier = event.tiers.find(
    (candidate) => candidate.tierId === hold.tierId
  );

  const [email, setEmail] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [messageSeverity, setMessageSeverity] = useState<"error" | "info">(
    "error"
  );
  const [payDisabled, setPayDisabled] = useState(false);
  const [duplicatePayment, setDuplicatePayment] = useState(false);

  const timerCheckForHold = useRef<string | null>(null);

  const total = useMemo(
    () => (tier ? (tier.priceCents * hold.quantity) / 100 : null),
    [hold.quantity, tier]
  );

  useEffect(() => {
    if (!duplicatePayment) return;

    const timer = window.setInterval(onRefresh, 2000);

    return () => window.clearInterval(timer);
  }, [duplicatePayment, onRefresh]);

  useEffect(() => {
    if (serverClock.remainingMs(hold.expiresAt) > 0) {
      timerCheckForHold.current = null;
      return;
    }

    const paymentInFlight = submitting || duplicatePayment;

    if (decideTimerZero(paymentInFlight) === "complete-payment") {
      setMessage("Completing your purchase…");
      setMessageSeverity("info");
      setPayDisabled(true);
      return;
    }

    if (timerCheckForHold.current === hold.holdToken) return;

    timerCheckForHold.current = hold.holdToken;
    setMessage("Checking your reservation…");
    setMessageSeverity("info");

    void onRefresh();
  }, [
    duplicatePayment,
    hold.expiresAt,
    hold.holdToken,
    onRefresh,
    submitting
  ]);

  const submit = async () => {
    const holdToken = getHoldToken(eventId) ?? hold.holdToken;

    if (!holdToken || !email.trim()) {
      setMessage("Enter the email address for your tickets.");
      setMessageSeverity("error");
      return;
    }

    if (!driver.ready) {
      setMessage("The payment form is still loading. Please try again.");
      setMessageSeverity("error");
      return;
    }

    setSubmitting(true);
    setPayDisabled(true);
    setDuplicatePayment(false);
    setMessage(null);
    setMessageSeverity("error");

    try {
      const { paymentMethodId, error } = await driver.createPaymentMethod(
        email.trim()
      );

      if (error || !paymentMethodId) {
        setMessage(error ?? "Your payment details could not be processed.");
        setPayDisabled(false);
        return;
      }

      /*
       * ONE key per hold, reused across every retry of that hold — FE_SPEC §3.
       *
       * A fresh key per attempt would open a SECOND PaymentIntent, so a buyer
       * could authenticate one payment and be billed for two. Re-posting the
       * same key is safe because the server retrieves the pending intent by
       * hold instead of charging again (ADR-054).
       */
      const idempotencyKey = getIdempotencyKey(eventId, holdToken);

      const checkoutRequest = {
        holdToken,
        userEmail: email.trim(),
        paymentMethodId,
        idempotencyKey
      };

      let receipt;

      try {
        receipt = await checkout(checkoutRequest);
      } catch (cause) {
        if (
          !(cause instanceof ApiError) ||
          cause.code !== "PAYMENT_ACTION_REQUIRED"
        ) {
          throw cause;
        }

        const clientSecret = cause.problem.clientSecret;

        if (!clientSecret) {
          setMessage(
            "Your bank requires verification, but the payment could not continue."
          );
          setPayDisabled(false);
          return;
        }

        // Keep payment disabled while the bank challenge is active.
        setMessage("Your bank is verifying the payment…");
        setMessageSeverity("info");
        setPayDisabled(true);
        setSubmitting(true);

        const { error: actionError } = await driver.authenticate(clientSecret);

        if (actionError) {
          setMessage(actionError);
          setMessageSeverity("error");
          setPayDisabled(false);
          return;
        }

        /*
         * Re-post the EXACT SAME body. There is no resume endpoint and the
         * client must not invent one (ADR-054, FE_SPEC §2).
         */
        receipt = await checkout(checkoutRequest);
      }

      clearHoldStorage(eventId, holdToken);
      onCompleted(receipt.orderNumber);
    } catch (cause) {
      if (!(cause instanceof ApiError)) {
        setMessage("That payment could not be completed. Please try again.");
        setMessageSeverity("error");
        setPayDisabled(false);
        return;
      }

      const next = checkoutErrorState(cause);

      setMessage(next.message);
      setMessageSeverity(next.severity);
      setPayDisabled(next.payDisabled);
      setDuplicatePayment(next.duplicatePayment);

      if (next.clearHold) {
        clearHoldStorage(eventId, hold.holdToken);
      }

      if (next.refreshSale) {
        onRefresh();
      }
    } finally {
      setSubmitting(false);
    }
  };

  const release = async () => {
    const holdToken = getHoldToken(eventId) ?? hold.holdToken;

    try {
      await releaseHold(holdToken);
    } catch (cause) {
      if (!(cause instanceof ApiError) || cause.code !== "HOLD_NOT_FOUND") {
        setMessage("The seats could not be released. Please try again.");
        return;
      }
    }

    clearHoldStorage(eventId, holdToken);
    onRefresh();
  };

  return {
    tier,
    total,
    email,
    setEmail,
    message,
    messageSeverity,
    submitting,
    payDisabled,
    submit,
    release
  };
}
