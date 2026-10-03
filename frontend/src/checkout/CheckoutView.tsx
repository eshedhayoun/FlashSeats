import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Divider from "@mui/material/Divider";
import FormControlLabel from "@mui/material/FormControlLabel";
import Grid from "@mui/material/Grid2";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import LockRounded from "@mui/icons-material/LockRounded";
import { Elements, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { ActiveHold, EventDetails, OrderReceipt, WindowStatus } from "../api/types";
import { serverClock } from "../clock/serverClock";
import { useClockTick } from "../clock/useClockTick";
import { formatDuration } from "../format/duration";
import { formatMoney } from "../format/money";
import { EventStrip } from "../landing/EventHero";
import type { SaleNoticeKind } from "../sale/notices";
import { stripeEnabled, stripePromise } from "../stripe";
import { ConfirmDialog } from "../ui/ConfirmDialog";
import { Notice } from "../ui/Notice";
import { PageTitle } from "../ui/PageTitle";
import { problemCopy } from "../copy/problemCopy";
import { HoldTimerBar } from "./HoldTimerBar";
import { useCheckoutSubmit, type PaymentDriver } from "./useCheckoutSubmit";

type CheckoutProps = {
  event: EventDetails;
  hold: ActiveHold;
  windowStatus: WindowStatus;
  notice?: ReactNode;
  onRefresh: () => Promise<void>;
  onCompleted: (receipt: OrderReceipt) => void;
  onEnded: (notice: SaleNoticeKind) => Promise<void>;
  onReleased: () => Promise<void>;
};

/**
 * V4 — the highest-stakes screen (FE_SPEC V4). The gateway is the server's decision and the client
 * follows it: with no publishable key the server's stub is what answers, so offering a card form
 * would be a lie about where the money goes (ADR-058).
 */
export function CheckoutView(props: CheckoutProps) {
  const tier = props.event.tiers.find((candidate) => candidate.tierId === props.hold.tierId);

  if (!tier) {
    return (
      <Container maxWidth="sm" sx={{ py: 5 }}>
        <Notice severity="error" title="We can't show this ticket type any more">
          Your reservation is still held on our side. Reload the page in a moment.
        </Notice>
      </Container>
    );
  }

  if (!stripeEnabled) return <StubCheckout {...props} />;

  return (
    <StripeElements amount={tier.priceCents * props.hold.quantity} currency={tier.currency.toLowerCase()}>
      <StripeCheckout {...props} />
    </StripeElements>
  );
}

function StripeElements({ amount, currency, children }: { amount: number; currency: string; children: ReactNode }) {
  const options = useMemo(
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
    <Elements stripe={stripePromise} options={options}>
      {children}
    </Elements>
  );
}

function StripeCheckout(props: CheckoutProps) {
  const stripe = useStripe();
  const elements = useElements();

  const driver: PaymentDriver = useMemo(
    () => ({
      ready: Boolean(stripe && elements),
      async createPaymentMethod(email) {
        if (!stripe || !elements) return { error: "The payment form is still loading." };
        const { error: submitError } = await elements.submit();
        if (submitError) return { error: submitError.message ?? "Please check your card details." };
        const { error, paymentMethod } = await stripe.createPaymentMethod({
          elements,
          params: { billing_details: { email } }
        });
        if (error) return { error: error.message ?? "Your card details couldn't be processed." };
        return paymentMethod ? { paymentMethodId: paymentMethod.id } : { error: "Your card details couldn't be processed." };
      },
      async authenticate(clientSecret) {
        if (!stripe) return { error: "The payment form is still loading." };
        const { error } = await stripe.handleNextAction({ clientSecret });
        return error ? { error: error.message ?? "Your bank couldn't verify the payment." } : {};
      }
    }),
    [stripe, elements]
  );

  return <CheckoutLayout {...props} driver={driver} payment={<PaymentElement options={{ layout: "tabs" }} />} />;
}

/**
 * The stub gateway's outcomes, spelled exactly as it switches on them, so decline, outage and 3-D
 * Secure can all be walked in a browser with no account.
 */
const STUB_OUTCOMES = [
  { id: "pm_card_visa", label: "Card that succeeds", hint: "The payment goes through." },
  { id: "pm_card_declined", label: "Card that's declined", hint: "Your seats stay held, and you can try again." },
  { id: "pm_card_error", label: "Payment provider outage", hint: "Your seats stay held, and no attempt is used." },
  {
    id: "pm_card_authenticationRequired",
    label: "Card that needs 3-D Secure",
    hint: "Your bank verifies it, then the payment completes."
  }
];

function StubCheckout(props: CheckoutProps) {
  const [outcome, setOutcome] = useState(STUB_OUTCOMES[0].id);

  const driver: PaymentDriver = useMemo(
    () => ({
      ready: true,
      async createPaymentMethod() {
        return { paymentMethodId: outcome };
      },
      async authenticate() {
        // No bank page to show: re-posting the same body is the whole retry (ADR-054).
        return {};
      }
    }),
    [outcome]
  );

  return (
    <CheckoutLayout
      {...props}
      driver={driver}
      payment={
        <Stack spacing={1.5}>
          <Notice severity="info" title="Demo payments">
            This server uses its test gateway, so no card is charged. Choose how this payment should go.
          </Notice>
          <RadioGroup
            aria-label="Demo payment outcome"
            value={outcome}
            onChange={(change) => setOutcome(change.target.value)}
            sx={{ gap: 1 }}
          >
            {STUB_OUTCOMES.map((option) => (
              <FormControlLabel
                key={option.id}
                value={option.id}
                control={<Radio />}
                data-testid={`stub-${option.id}`}
                label={
                  <Box sx={{ py: 0.5 }}>
                    <Typography fontWeight={650}>{option.label}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {option.hint}
                    </Typography>
                  </Box>
                }
                sx={{
                  m: 0,
                  pr: 2,
                  border: 1,
                  borderRadius: 3,
                  borderColor: outcome === option.id ? "primary.main" : "divider",
                  alignItems: "flex-start",
                  "& .MuiRadio-root": { mt: 0.5 }
                }}
              />
            ))}
          </RadioGroup>
        </Stack>
      }
    />
  );
}

function CheckoutLayout({
  event,
  hold,
  windowStatus,
  notice,
  driver,
  payment,
  onRefresh,
  onCompleted,
  onEnded,
  onReleased
}: CheckoutProps & { driver: PaymentDriver; payment: ReactNode }) {
  const tier = event.tiers.find((candidate) => candidate.tierId === hold.tierId)!;
  const total = formatMoney(tier.priceCents * hold.quantity, tier.currency);
  const checkout = useCheckoutSubmit({
    eventId: event.eventId,
    hold,
    driver,
    onRefresh,
    onCompleted,
    onEnded,
    onReleased
  });
  const [confirmRelease, setConfirmRelease] = useState(false);

  const inFlight = checkout.phase !== "ready";
  const showEmailError = checkout.emailTouched && !checkout.emailValid;
  const failure = checkout.failure;

  // A failure is announced (role="alert") and also brought into view: the buyer is looking at the
  // Pay button, and the answer is above it.
  const feedback = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (failure || checkout.message) feedback.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [failure, checkout.message]);

  return (
    <Container maxWidth="lg" sx={{ py: { xs: 3, sm: 5 } }}>
      <Stack spacing={3}>
        <EventStrip event={event} />
        <PageTitle variant="h3">Complete your purchase</PageTitle>
        <HoldTimerBar expiresAt={checkout.expiresAt} paying={inFlight} onExpire={checkout.onTimerZero} />

        <Grid container spacing={3} alignItems="flex-start">
          <Grid size={{ xs: 12, md: 5 }} sx={{ order: { xs: 0, md: 1 }, position: { md: "sticky" }, top: { md: 160 } }}>
            <Card>
              <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
                <Typography variant="h5" component="h2" gutterBottom>
                  Order summary
                </Typography>
                <Stack spacing={1.5}>
                  <Stack direction="row" justifyContent="space-between" spacing={2}>
                    <Box>
                      <Typography fontWeight={650}>
                        {tier.tierName} × {hold.quantity}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        {formatMoney(tier.priceCents, tier.currency)} each
                      </Typography>
                    </Box>
                    <Typography className="tabular">{total}</Typography>
                  </Stack>
                  <Divider />
                  <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                    <Typography fontWeight={700}>Total</Typography>
                    <Typography variant="h4" component="p" className="tabular" data-testid="checkout-total">
                      {total}
                    </Typography>
                  </Stack>
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ xs: 12, md: 7 }}>
            <Stack spacing={3}>
              {notice}
              {windowStatus === "PAUSED" && (
                <Notice severity="info" title="Sales are paused for a moment">
                  You can still pay for the seats you're holding.
                </Notice>
              )}
              <Box ref={feedback} sx={{ scrollMarginTop: 180, display: "grid", gap: 2 }}>
                {checkout.message && (
                  <Notice severity={checkout.message.severity} title={checkout.message.title} testId="checkout-message">
                    {checkout.message.text}
                  </Notice>
                )}
                {failure && (
                  <Notice severity={failure.severity} title={failure.copy.title} testId="checkout-error">
                    {failure.copy.message}
                  </Notice>
                )}
              </Box>

              <Card>
                <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
                  <Stack spacing={3}>
                    <Box>
                      <Typography variant="h5" component="h2" gutterBottom>
                        Where should we send your tickets?
                      </Typography>
                      <TextField
                        label="Email address"
                        type="email"
                        autoComplete="email"
                        value={checkout.email}
                        onChange={(change) => checkout.setEmail(change.target.value)}
                        onBlur={checkout.touchEmail}
                        error={showEmailError}
                        helperText={
                          showEmailError
                            ? "Enter an email address like name@example.com."
                            : "Your tickets go here — please check it carefully."
                        }
                        disabled={inFlight}
                        slotProps={{ htmlInput: { "data-testid": "email", maxLength: 255, inputMode: "email" } }}
                      />
                    </Box>

                    <Box>
                      <Typography variant="h5" component="h2" gutterBottom>
                        Payment
                      </Typography>
                      {payment}
                    </Box>

                    <PayButton
                      total={total}
                      phase={checkout.phase}
                      disabled={!driver.ready || failure?.pay === "disabled"}
                      retryAt={checkout.retryAt}
                      expiresAt={checkout.expiresAt}
                      onPay={() => void checkout.submit()}
                    />

                    <Stack direction="row" spacing={1} alignItems="center" sx={{ color: "text.secondary" }}>
                      <LockRounded fontSize="small" aria-hidden />
                      <Typography variant="body2">
                        You're charged once, even if you press Pay again or reload this page.
                      </Typography>
                    </Stack>
                  </Stack>
                </CardContent>
              </Card>

              <Box>
                <Button
                  variant={failure?.offerRelease ? "outlined" : "text"}
                  color={failure?.offerRelease ? "primary" : "inherit"}
                  onClick={() => setConfirmRelease(true)}
                  disabled={inFlight}
                  data-testid="release"
                >
                  Release my seats
                </Button>
              </Box>
            </Stack>
          </Grid>
        </Grid>
      </Stack>

      <ConfirmDialog
        open={confirmRelease}
        title="Release your seats?"
        confirmLabel="Release seats"
        cancelLabel="Keep my seats"
        destructive
        busy={checkout.releasing}
        onConfirm={() => void checkout.release()}
        onCancel={() => setConfirmRelease(false)}
      >
        They'll go straight back on sale for someone else, and you'll leave this checkout.
        {checkout.releaseError && (
          <Box component="span" sx={{ display: "block", mt: 1.5, color: "error.main" }}>
            {problemCopy(checkout.releaseError).title}. Please try again.
          </Box>
        )}
      </ConfirmDialog>
    </Container>
  );
}

/** Pay, with the label saying exactly what is happening. Its own component: it ticks for the retry wait. */
function PayButton({
  total,
  phase,
  disabled,
  retryAt,
  expiresAt,
  onPay
}: {
  total: string;
  phase: string;
  disabled: boolean;
  retryAt: number | null;
  expiresAt: string;
  onPay: () => void;
}) {
  useClockTick();
  const waitMs = retryAt === null ? 0 : retryAt - Date.now();
  // At 00:00 Pay waits while the server is asked (FE_SPEC §6's double-submit guard).
  const holdOver = serverClock.remainingMs(expiresAt) <= 0 && phase === "ready";

  let label: ReactNode = `Pay ${total}`;
  if (phase === "paying") label = "Processing your payment…";
  if (phase === "verifying") label = "Waiting for your bank…";
  if (phase === "finishing") label = "Finishing your payment…";
  if (phase === "ready" && waitMs > 0) label = `Try again in ${formatDuration(waitMs)}`;

  return (
    <Button
      variant="contained"
      size="large"
      onClick={onPay}
      disabled={phase !== "ready" || disabled || waitMs > 0 || holdOver}
      data-testid="pay"
      sx={{ minHeight: 52 }}
    >
      {phase !== "ready" && <CircularProgress size={20} color="inherit" sx={{ mr: 1.5 }} />}
      {label}
    </Button>
  );
}
