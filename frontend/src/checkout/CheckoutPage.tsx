import {
  memo,
  useEffect,
  useMemo,
  useRef,
  useState
} from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  Elements,
  PaymentElement,
  useElements,
  useStripe
} from "@stripe/react-stripe-js";

import { stripePromise } from "../stripe";
import { checkout, releaseHold } from "../api/endpoints";
import { ApiError } from "../api/errors";
import type { EventDetails, ActiveHold } from "../api/types";
import { Countdown } from "../shared/Countdown";
import { serverClock } from "../clock/serverClock";
import { useClockTick } from "../clock/useClockTick";
import { decideTimerZero } from "./checkoutTimer";
import { checkoutErrorState } from "./checkoutErrorState";
import {
  clearHoldStorage,
  getIdempotencyKey,
  getHoldToken
} from "../sale/storage";
import { HomeButton } from "../shared/HomeButton";

export function CheckoutPage({
  event,
  eventId,
  hold,
  onRefresh,
  onCompleted
}: {
  event: EventDetails;
  eventId: number;
  hold: ActiveHold;
  onRefresh: () => void;
  onCompleted: (orderNumber: string) => void;
}) {
  const tier = event.tiers.find(
    (candidate) => candidate.tierId === hold.tierId
  );

  if (!tier) {
    return (
      <Container maxWidth="sm" sx={{ py: 6 }}>
        <Stack spacing={3}>
          <HomeButton />
          <Alert severity="error">
            The selected seat tier is no longer available.
          </Alert>
        </Stack>
      </Container>
    );
  }

  const amount = tier.priceCents * hold.quantity;
  const currency = tier.currency.toLowerCase();

  return (
    <StripeCheckout
      amount={amount}
      currency={currency}
      event={event}
      eventId={eventId}
      hold={hold}
      onRefresh={onRefresh}
      onCompleted={onCompleted}
    />
  );
}

function StripeCheckout({
  amount,
  currency,
  event,
  eventId,
  hold,
  onRefresh,
  onCompleted
}: {
  amount: number;
  currency: string;
  event: EventDetails;
  eventId: number;
  hold: ActiveHold;
  onRefresh: () => void;
  onCompleted: (orderNumber: string) => void;
}) {
  const elementsOptions = useMemo(
    () => ({
      mode: "payment" as const,
      amount,
      currency,
      paymentMethodCreation: "manual" as const,
      paymentMethodTypes: ["card"]
    }),
    [amount, currency]
  );
  return (
    <Elements
      stripe={stripePromise}
      options={elementsOptions}
    >
      <CheckoutForm
        event={event}
        eventId={eventId}
        hold={hold}
        onRefresh={onRefresh}
        onCompleted={onCompleted}
      />
    </Elements>
  );
}

function CheckoutForm({
  event,
  eventId,
  hold,
  onRefresh,
  onCompleted
}: {
  event: EventDetails;
  eventId: number;
  hold: ActiveHold;
  onRefresh: () => void;
  onCompleted: (orderNumber: string) => void;
}) {
  useClockTick();

  const stripe = useStripe();
  const elements = useElements();

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

    if (!stripe || !elements) {
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
      /*
      * Validate the Stripe PaymentElement first.
      */
      const { error: submitError } = await elements.submit();

      if (submitError) {
        setMessage(
          submitError.message ?? "Please check your payment details."
        );
        setPayDisabled(false);
        return;
      }

      /*
      * Create the PaymentMethod from the current card details.
      */
      const {
        error: paymentMethodError,
        paymentMethod
      } = await stripe.createPaymentMethod({
        elements,
        params: {
          billing_details: {
            email: email.trim()
          }
        }
      });

      if (paymentMethodError) {
        setMessage(
          paymentMethodError.message ??
            "Your payment details could not be processed."
        );
        setPayDisabled(false);
        return;
      }

      if (!paymentMethod) {
        setMessage("Stripe did not create a payment method.");
        setPayDisabled(false);
        return;
      }

      /*
      * IMPORTANT:
      *
      * Generate ONE idempotency key for THIS payment attempt.
      *
      * If this attempt enters 3-D Secure, the exact same request
      * (including this key) is re-posted after authentication.
      *
      * If the buyer later tries again, submit() runs again and
      * generates a NEW key.
      */
      const idempotencyKey = crypto.randomUUID();

      const checkoutRequest = {
        holdToken,
        userEmail: email.trim(),
        paymentMethodId: paymentMethod.id,
        idempotencyKey
      };

      let receipt;

      try {
        /*
        * First checkout attempt.
        */
        receipt = await checkout(checkoutRequest);
      } catch (cause) {
        /*
        * The backend says the bank requires authentication.
        */
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

        /*
        * Keep payment disabled while the bank challenge is active.
        */
        setMessage("Your bank is verifying the payment…");
        setMessageSeverity("info");
        setPayDisabled(true);
        setSubmitting(true);

        /*
        * Run 3-D Secure.
        */
        const { error: actionError } =
          await stripe.handleNextAction({
            clientSecret
          });

        if (actionError) {
          setMessage(
            actionError.message ??
              "Your bank could not verify the payment. Please try again."
          );
          setMessageSeverity("error");
          setPayDisabled(false);
          return;
        }

        /*
        * Re-post the EXACT SAME checkout request.
        *
        * Same:
        *   - holdToken
        *   - userEmail
        *   - paymentMethodId
        *   - idempotencyKey
        *
        * The backend sees the existing PROCESSING PaymentIntent
        * and retrieves it instead of creating another charge.
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
      if (
        !(cause instanceof ApiError) ||
        cause.code !== "HOLD_NOT_FOUND"
      ) {
        setMessage("The seats could not be released. Please try again.");
        return;
      }
    }

    clearHoldStorage(eventId, holdToken);
    onRefresh();
  };

  return (
    <Container maxWidth="sm" sx={{ py: 6 }}>
      <Stack spacing={3}>
        <HomeButton />

        <Typography component="h1" variant="h4">
          Complete your purchase
        </Typography>

        <Card>
          <CardContent>
            <Stack spacing={1}>
              <Typography variant="h6">
                {hold.quantity} × {tier?.tierName ?? "seat"}
              </Typography>

              <Typography color="text.secondary">
                Total:{" "}
                {total == null
                  ? "—"
                  : `${total.toFixed(2)} ${tier?.currency}`}
              </Typography>

              <Typography>
                Reservation expires in{" "}
                <Countdown expiresAt={hold.expiresAt} />
              </Typography>
            </Stack>
          </CardContent>
        </Card>

        {message && (
          <Alert severity={messageSeverity}>
            {message}
          </Alert>
        )}

        <TextField
          label="Email for your tickets"
          type="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          autoComplete="email"
          fullWidth
        />

        <Typography variant="h6">
          Payment
        </Typography>

        <PaymentElement
          options={{
            layout: "tabs"
          }}
        />

        <Button
          variant="contained"
          size="large"
          disabled={
            submitting ||
            payDisabled ||
            !stripe ||
            !elements
          }
          onClick={() => void submit()}
        >
          {submitting ? (
            <CircularProgress size={22} color="inherit" />
          ) : (
            "Pay now"
          )}
        </Button>

        <Button
          variant="outlined"
          disabled={submitting}
          onClick={() => void release()}
        >
          Release seats
        </Button>
      </Stack>
    </Container>
  );
}