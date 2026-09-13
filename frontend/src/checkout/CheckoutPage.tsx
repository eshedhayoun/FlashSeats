import { useEffect, useMemo, useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { checkout, releaseHold } from "../api/endpoints";
import { ApiError } from "../api/errors";
import type { EventDetails, ActiveHold } from "../api/types";
import { Countdown } from "../shared/Countdown";
import {
  clearHoldStorage,
  getIdempotencyKey,
  getHoldToken
} from "../sale/storage";

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
  const tier = event.tiers.find((candidate) => candidate.tierId === hold.tierId);
  const [email, setEmail] = useState("");
  const [paymentMethodId, setPaymentMethodId] = useState("pm_card_visa");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [messageSeverity, setMessageSeverity] = useState<"error" | "info">("error");
  const [payDisabled, setPayDisabled] = useState(false);
  const [duplicatePayment, setDuplicatePayment] = useState(false);

  const total = useMemo(
    () => (tier ? (tier.priceCents * hold.quantity) / 100 : null),
    [hold.quantity, tier]
  );

  useEffect(() => {
    if (!duplicatePayment) return;
    const timer = window.setInterval(onRefresh, 2000);
    return () => window.clearInterval(timer);
  }, [duplicatePayment, onRefresh]);

  const submit = async () => {
    const holdToken = getHoldToken(eventId) ?? hold.holdToken;
    if (!holdToken || !email.trim()) {
      setMessage("Enter the email address for your tickets.");
      setMessageSeverity("error");
      return;
    }

    setSubmitting(true);
    setPayDisabled(true);
    setDuplicatePayment(false);
    setMessage(null);
    setMessageSeverity("error");
    try {
      const receipt = await checkout({
        holdToken,
        userEmail: email.trim(),
        paymentMethodId,
        idempotencyKey: getIdempotencyKey(eventId, holdToken)
      });
      clearHoldStorage(eventId, holdToken);
      onCompleted(receipt.orderNumber);
    } catch (cause) {
      if (!(cause instanceof ApiError)) {
        setMessage("That payment could not be completed. Please try again.");
        setPayDisabled(false);
      } else {
        handleCheckoutError(cause);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleCheckoutError = (error: ApiError) => {
    switch (error.code) {
      case "PAYMENT_DECLINED":
        setMessage(
          `${error.message} Your seats are still held${
            error.problem.attemptsRemaining == null
              ? ""
              : ` — ${error.problem.attemptsRemaining} attempt(s) left`
          }.`
        );
        setPayDisabled(false);
        return;
      case "PAYMENT_GATEWAY_UNAVAILABLE":
        setMessage(
          `${error.message} The payment provider is having trouble. Your seats are held.`
        );
        setPayDisabled(false);
        return;
      case "PAYMENT_ATTEMPTS_EXHAUSTED":
        setMessage(`${error.message} Release your seats to start over.`);
        setPayDisabled(true);
        return;
      case "DUPLICATE_PAYMENT":
        setMessage("Finishing a payment that is already in progress…");
        setMessageSeverity("info");
        setPayDisabled(true);
        setDuplicatePayment(true);
        return;
      case "INSUFFICIENT_TIME_REMAINING":
        setMessage(`${error.message} Release your seats and reserve again.`);
        setPayDisabled(true);
        return;
      case "HOLD_EXPIRED":
        clearHoldStorage(eventId, hold.holdToken);
        setMessage("Your reservation expired. Nothing was charged.");
        setPayDisabled(true);
        onRefresh();
        return;
      case "ORDER_REFUNDED":
        clearHoldStorage(eventId, hold.holdToken);
        setMessage(
          `${error.message} The charge succeeded but was refunded.`
        );
        setPayDisabled(true);
        onRefresh();
        return;
      default:
        setMessage(error.message);
        setPayDisabled(false);
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

  return (
    <Container maxWidth="sm" sx={{ py: 6 }}>
      <Stack spacing={3}>
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
                Total: {total == null ? "—" : `${total.toFixed(2)} ${tier?.currency}`}
              </Typography>
              <Typography>
                Reservation expires in{" "}
                <Countdown expiresAt={hold.expiresAt} />
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
        <TextField
          select
          label="Payment method"
          value={paymentMethodId}
          onChange={(event) => setPaymentMethodId(event.target.value)}
          SelectProps={{ native: true }}
          fullWidth
        >
          <option value="pm_card_visa">Visa ending 4242 — succeeds</option>
          <option value="pm_card_declined">Test card — declines</option>
          <option value="pm_card_error">Test card — provider error</option>
        </TextField>
        <Button
          variant="contained"
          size="large"
          disabled={submitting || payDisabled}
          onClick={() => void submit()}
        >
          {submitting ? (
            <CircularProgress size={22} color="inherit" />
          ) : (
            "Pay now"
          )}
        </Button>
        <Button variant="outlined" disabled={submitting} onClick={() => void release()}>
          Release seats
        </Button>
      </Stack>
    </Container>
  );
}
