import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import RadioGroup from "@mui/material/RadioGroup";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { createHold } from "../api/endpoints";
import { asApiError, type ApiError } from "../api/errors";
import type { EventDetails, WindowStatus } from "../api/types";
import { backoffMs } from "../api/client";
import { problemCopy } from "../copy/problemCopy";
import { formatMoney } from "../format/money";
import { EventStrip } from "../landing/EventHero";
import { getAdmissionToken } from "../sale/storage";
import { Countdown } from "../ui/Countdown";
import { Notice } from "../ui/Notice";
import { PageTitle } from "../ui/PageTitle";
import { QuantityStepper } from "../ui/QuantityStepper";
import { TierOption } from "../ui/TierOption";

/** `INVENTORY_UNAVAILABLE` is a fault on our side; it is retried this many times before the buyer is asked. */
const INVENTORY_RETRIES = 3;
const PAUSED_POLL_MS = 5_000;

const sleep = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms));

/**
 * V3 — the buyer's turn: a tier, a quantity, a reservation (FE_SPEC V3). The timer here is the
 * admission, and it is calm: running out costs a place in line, not money.
 */
export function SeatSelectionView({
  event,
  admissionExpiresAt,
  windowStatus,
  notice,
  onRefresh,
  onReloadEvent,
  onAdmissionLost
}: {
  event: EventDetails;
  admissionExpiresAt: string | null;
  windowStatus: WindowStatus;
  notice?: ReactNode;
  onRefresh: () => Promise<void>;
  onReloadEvent: () => Promise<void>;
  onAdmissionLost: () => Promise<void>;
}) {
  const eventId = event.eventId;
  const [tierId, setTierId] = useState<number | null>(() => {
    const open = event.tiers.filter((tier) => tier.availability !== "SOLD_OUT");
    return open.length === 1 ? open[0].tierId : null;
  });
  const [quantity, setQuantity] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [soldOutNote, setSoldOutNote] = useState<{ tierId: number; text: string } | null>(null);

  const tier = useMemo(() => event.tiers.find((candidate) => candidate.tierId === tierId) ?? null, [event.tiers, tierId]);
  const paused = windowStatus === "PAUSED";

  // Fresh availability on arrival: the event was read when the buyer joined, possibly long ago (U-8).
  useEffect(() => {
    void onReloadEvent();
  }, [onReloadEvent]);

  // While paused, ask now and then, so Reserve comes back the moment sales resume.
  useEffect(() => {
    if (!paused) return;
    const timer = window.setInterval(() => void onRefresh(), PAUSED_POLL_MS);
    return () => window.clearInterval(timer);
  }, [paused, onRefresh]);

  // A tier that sold out under the selection is deselected rather than left looking chosen.
  useEffect(() => {
    if (tier?.availability === "SOLD_OUT") setTierId(null);
  }, [tier]);

  const choose = (nextTierId: number) => {
    const next = event.tiers.find((candidate) => candidate.tierId === nextTierId);
    if (!next) return;
    setTierId(next.tierId);
    setQuantity((current) => Math.min(Math.max(1, current), next.maxPerOrder));
    setError(null);
    setSoldOutNote(null);
  };

  const reserve = async () => {
    if (!tier) return;
    const admissionToken = getAdmissionToken(eventId);
    if (!admissionToken) {
      await onAdmissionLost();
      return;
    }

    setSubmitting(true);
    setError(null);
    setSoldOutNote(null);
    try {
      for (let attempt = 0; ; attempt++) {
        try {
          await createHold({ eventId, tierId: tier.tierId, quantity }, admissionToken);
          await onRefresh(); // the server now says "checkout"
          return;
        } catch (cause) {
          const failure = asApiError(cause, "Those seats couldn't be reserved. Please try again.");
          if (failure.code === "INVENTORY_UNAVAILABLE" && attempt < INVENTORY_RETRIES) {
            setRetrying(true);
            await sleep(backoffMs(failure, attempt + 1));
            continue;
          }
          await handleFailure(failure);
          return;
        }
      }
    } finally {
      setRetrying(false);
      setSubmitting(false);
    }
  };

  const handleFailure = async (failure: ApiError) => {
    switch (failure.code) {
      case "INSUFFICIENT_STOCK":
        setSoldOutNote({
          tierId: tier!.tierId,
          text: quantity > 1 ? `Not enough left for ${quantity} — try fewer, or another tier.` : "Just sold out — pick another tier."
        });
        await onReloadEvent();
        return;
      case "QUANTITY_EXCEEDS_LIMIT":
        setQuantity((current) => Math.min(current, tier!.maxPerOrder));
        setError(failure);
        await onReloadEvent();
        return;
      case "HOLD_LIMIT_EXCEEDED":
      case "SALE_CLOSED":
        await onRefresh(); // already holding seats → checkout; ended → the closed screen
        return;
      case "ADMISSION_EXPIRED":
      case "ADMISSION_REQUIRED":
        await onAdmissionLost();
        return;
      case "SALE_PAUSED":
        setError(failure);
        await onRefresh();
        return;
      default:
        setError(failure);
    }
  };

  const errorCopy = error ? problemCopy(error) : null;
  const total = tier ? formatMoney(tier.priceCents * quantity, tier.currency) : null;

  return (
    <Container maxWidth="sm" sx={{ py: { xs: 3, sm: 5 } }}>
      <Stack spacing={3}>
        <EventStrip event={event} />
        {notice}

        <Stack spacing={1}>
          <PageTitle variant="h3">It's your turn — choose your seats</PageTitle>
          {admissionExpiresAt && (
            <Typography color="text.secondary">
              Your turn lasts another <Countdown expiresAt={admissionExpiresAt} variant="admission" onExpire={() => void onRefresh()} />.
              Take your time to compare.
            </Typography>
          )}
        </Stack>

        {paused && (
          <Notice severity="warning" title="Sales are paused for a moment" testId="paused">
            You can reserve as soon as they resume, while your turn lasts. Your turn is kept.
          </Notice>
        )}

        <RadioGroup
          aria-label="Ticket tier"
          value={tierId === null ? "" : String(tierId)}
          onChange={(change) => choose(Number(change.target.value))}
          sx={{ gap: 1.5 }}
        >
          {event.tiers.map((option) => (
            <TierOption
              key={option.tierId}
              tier={option}
              checked={option.tierId === tierId}
              note={soldOutNote?.tierId === option.tierId ? soldOutNote.text : null}
            />
          ))}
        </RadioGroup>

        <Card>
          <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
            <Stack spacing={2.5}>
              <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={2}>
                <Box>
                  <Typography fontWeight={700}>Tickets</Typography>
                  <Typography variant="body2" color="text.secondary">
                    {tier ? `Up to ${tier.maxPerOrder} for ${tier.tierName}` : "Choose a tier first"}
                  </Typography>
                </Box>
                <QuantityStepper value={quantity} max={tier?.maxPerOrder ?? 1} disabled={!tier || submitting} onChange={setQuantity} />
              </Stack>
              <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                <Typography color="text.secondary">Total</Typography>
                <Typography variant="h4" component="p" className="tabular" data-testid="selection-total">
                  {total ?? "—"}
                </Typography>
              </Stack>

              {errorCopy && (
                <Notice severity={error?.code === "SALE_PAUSED" ? "warning" : "error"} title={errorCopy.title}>
                  {errorCopy.message}
                </Notice>
              )}

              <Button
                variant="contained"
                size="large"
                onClick={() => void reserve()}
                disabled={!tier || submitting || paused}
                data-testid="reserve"
              >
                {submitting ? (
                  <>
                    <CircularProgress size={20} color="inherit" sx={{ mr: 1.5 }} />
                    {retrying ? "Having trouble — retrying…" : "Reserving your seats…"}
                  </>
                ) : (
                  `Reserve ${quantity} ${quantity === 1 ? "ticket" : "tickets"}`
                )}
              </Button>
              <Typography variant="body2" color="text.secondary">
                Reserving holds your seats while you pay. Nothing is charged until you pay.
              </Typography>
            </Stack>
          </CardContent>
        </Card>
      </Stack>
    </Container>
  );
}
