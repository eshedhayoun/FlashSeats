import { useMemo } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  Elements,
  PaymentElement,
  useElements,
  useStripe
} from "@stripe/react-stripe-js";

import { stripeEnabled, stripePromise } from "../stripe";
import type { EventDetails, ActiveHold } from "../api/types";
import { Countdown } from "../shared/Countdown";
import { HomeButton } from "../shared/HomeButton";
import { useCheckoutSubmit, type PaymentDriver } from "./useCheckoutSubmit";
import { useState } from "react";

type CheckoutProps = {
  event: EventDetails;
  eventId: number;
  hold: ActiveHold;
  onRefresh: () => void;
  onCompleted: (orderNumber: string) => void;
};

export function CheckoutPage(props: CheckoutProps) {
  const tier = props.event.tiers.find(
    (candidate) => candidate.tierId === props.hold.tierId
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

  /*
   * Which gateway is live is a server decision, and the client follows it.
   * With no publishable key configured the backend's default stub is what is
   * answering, so offering a card form would be a lie about where the money
   * goes. See ../stripe.ts and ADR-058.
   */
  if (!stripeEnabled) {
    return <StubCheckout {...props} />;
  }

  return (
    <StripeCheckout
      {...props}
      amount={tier.priceCents * props.hold.quantity}
      currency={tier.currency.toLowerCase()}
    />
  );
}

function StripeCheckout({
  amount,
  currency,
  ...props
}: CheckoutProps & { amount: number; currency: string }) {
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
    <Elements stripe={stripePromise} options={elementsOptions}>
      <StripeCheckoutForm {...props} />
    </Elements>
  );
}

function StripeCheckoutForm(props: CheckoutProps) {
  const stripe = useStripe();
  const elements = useElements();

  const driver: PaymentDriver = {
    ready: Boolean(stripe && elements),

    async createPaymentMethod(email) {
      if (!stripe || !elements) {
        return { error: "The payment form is still loading." };
      }

      // Validate the PaymentElement before asking for a payment method.
      const { error: submitError } = await elements.submit();

      if (submitError) {
        return {
          error: submitError.message ?? "Please check your payment details."
        };
      }

      const { error, paymentMethod } = await stripe.createPaymentMethod({
        elements,
        params: { billing_details: { email } }
      });

      if (error) {
        return {
          error: error.message ?? "Your payment details could not be processed."
        };
      }

      if (!paymentMethod) {
        return { error: "Stripe did not create a payment method." };
      }

      return { paymentMethodId: paymentMethod.id };
    },

    async authenticate(clientSecret) {
      if (!stripe) {
        return { error: "The payment form is still loading." };
      }

      const { error } = await stripe.handleNextAction({ clientSecret });

      return error
        ? {
            error:
              error.message ??
              "Your bank could not verify the payment. Please try again."
          }
        : {};
    }
  };

  return <CheckoutLayout {...props} driver={driver} payment={<PaymentElement options={{ layout: "tabs" }} />} />;
}

/**
 * The stub gateway's documented outcomes, spelled exactly as it switches on
 * them (StubPaymentGateway). This is the only way to walk a decline, a gateway
 * outage or a 3-D Secure challenge in a browser without a Stripe account.
 */
const STUB_PAYMENT_METHODS = [
  { id: "pm_card_ok", label: "Succeeds" },
  { id: "pm_card_declined", label: "Declined — seats kept, retry allowed" },
  { id: "pm_card_error", label: "Gateway unreachable — 503, seats kept" },
  {
    id: "pm_card_authenticationRequired",
    label: "3-D Secure — authenticates, then settles"
  }
];

function StubCheckout(props: CheckoutProps) {
  const [paymentMethodId, setPaymentMethodId] = useState(
    STUB_PAYMENT_METHODS[0].id
  );

  const driver: PaymentDriver = {
    ready: true,
    async createPaymentMethod() {
      return { paymentMethodId };
    },
    async authenticate() {
      // No bank page to show. Re-posting the same body is the whole retry, and
      // the server settles the parked intent (ADR-054).
      return {};
    }
  };

  return (
    <CheckoutLayout
      {...props}
      driver={driver}
      payment={
        <Stack spacing={1}>
          <TextField
            select
            label="Test card outcome"
            value={paymentMethodId}
            onChange={(event) => setPaymentMethodId(event.target.value)}
            fullWidth
          >
            {STUB_PAYMENT_METHODS.map((method) => (
              <MenuItem key={method.id} value={method.id}>
                {method.label}
              </MenuItem>
            ))}
          </TextField>
          <Typography variant="caption" color="text.secondary">
            No card is charged. The server is running its stub gateway because
            no Stripe key is configured.
          </Typography>
        </Stack>
      }
    />
  );
}

function CheckoutLayout({
  event,
  eventId,
  hold,
  driver,
  payment,
  onRefresh,
  onCompleted
}: CheckoutProps & { driver: PaymentDriver; payment: React.ReactNode }) {
  const {
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
  } = useCheckoutSubmit({
    event,
    eventId,
    hold,
    driver,
    onRefresh,
    onCompleted
  });

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
                {total == null ? "—" : `${total.toFixed(2)} ${tier?.currency}`}
              </Typography>

              <Typography>
                Reservation expires in <Countdown expiresAt={hold.expiresAt} />
              </Typography>
            </Stack>
          </CardContent>
        </Card>

        {message && <Alert severity={messageSeverity}>{message}</Alert>}

        <TextField
          label="Email for your tickets"
          type="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          autoComplete="email"
          fullWidth
        />

        <Typography variant="h6">Payment</Typography>

        {payment}

        <Button
          variant="contained"
          size="large"
          disabled={submitting || payDisabled || !driver.ready}
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
