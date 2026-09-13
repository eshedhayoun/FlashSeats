import { useMemo, useState } from "react";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import FormControl from "@mui/material/FormControl";
import FormHelperText from "@mui/material/FormHelperText";
import TextField from "@mui/material/TextField";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { createHold } from "../api/endpoints";
import { ApiError } from "../api/errors";
import type { EventDetails, HoldResponse } from "../api/types";
import { Countdown } from "../shared/Countdown";
import { setHoldToken, getAdmissionToken } from "../sale/storage";
import { TierCard } from "../landing/TierCard";
import { HomeButton } from "../shared/HomeButton";

export function SeatSelectionPage({
  event,
  eventId,
  admissionExpiresAt,
  onRefresh,
  onHoldCreated
}: {
  event: EventDetails;
  eventId: number;
  admissionExpiresAt: string | null;
  onRefresh: () => Promise<void>;
  onHoldCreated: (hold: HoldResponse) => void;
}) {
  const [selectedTierId, setSelectedTierId] = useState<number | null>(null);
  const [quantity, setQuantity] = useState("1");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const selectedTier = useMemo(
    () => event.tiers.find((tier) => tier.tierId === selectedTierId) ?? null,
    [event.tiers, selectedTierId]
  );

  const selectTier = (tierId: number) => {
    const tier = event.tiers.find((candidate) => candidate.tierId === tierId);
    if (!tier) return;
    setSelectedTierId(tier.tierId);
    setQuantity((current) => {
      const parsed = Number(current);
      return String(Number.isInteger(parsed) ? Math.min(parsed, tier.maxPerOrder) : 1);
    });
    setError(null);
  };

  const submit = async () => {
    if (!selectedTier) return;

    const requestedQuantity = Number(quantity);
    if (
      !Number.isInteger(requestedQuantity) ||
      requestedQuantity < 1 ||
      requestedQuantity > selectedTier.maxPerOrder
    ) {
      setError(`Enter a whole number from 1 to ${selectedTier.maxPerOrder}.`);
      return;
    }

    const admissionToken = getAdmissionToken(eventId);
    if (!admissionToken) {
      setError("Your sale session is no longer available. Rechecking status…");
      onRefresh();
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const hold = await createHold(
        { eventId, tierId: selectedTier.tierId, quantity: requestedQuantity },
        admissionToken
      );
      setHoldToken(eventId, hold.holdToken);
      onHoldCreated(hold);
      await onRefresh();
    } catch (cause) {
      if (!(cause instanceof ApiError)) {
        setError("Those seats could not be reserved. Please try again.");
      } else if (cause.code === "INSUFFICIENT_STOCK") {
        setError("Those seats just sold out. Pick another tier.");
        onRefresh();
      } else if (cause.code === "QUANTITY_EXCEEDS_LIMIT") {
        const serverLimit = selectedTier.maxPerOrder;
        setQuantity(String(serverLimit));
        setError(`The maximum for this tier is ${serverLimit}.`);
      } else if (cause.code === "HOLD_LIMIT_EXCEEDED") {
        setError("This session already holds seats. Rechecking your reservation…");
        onRefresh();
      } else if (cause.code === "ADMISSION_EXPIRED") {
        setError("Your sale session ended. Rejoining is required.");
        onRefresh();
      } else if (cause.code === "INVENTORY_UNAVAILABLE") {
        setError("Having trouble reading availability. Nothing was reserved. Try again.");
      } else {
        setError(cause.message);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Container maxWidth="sm" sx={{ py: 6 }}>
      <Stack spacing={3}>
        <HomeButton />
        <Stack spacing={1}>
          <Typography component="h1" variant="h4">
            Choose your seats
          </Typography>
          <Typography color="text.secondary">
            Take your time — your admission session is active.
          </Typography>
          {admissionExpiresAt && (
            <Typography>
              Session ends in <Countdown expiresAt={admissionExpiresAt} />
            </Typography>
          )}
        </Stack>

        <Stack spacing={1.5}>
          {event.tiers.map((tier) => (
            <TierCard
              key={tier.tierId}
              tier={tier}
              selected={selectedTierId === tier.tierId}
              onSelect={() => selectTier(tier.tierId)}
            />
          ))}
        </Stack>

        <FormControl disabled={!selectedTier || submitting}>
          <TextField
            label="Number of tickets"
            type="number"
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
            inputProps={{
              min: 1,
              max: selectedTier?.maxPerOrder,
              step: 1,
              inputMode: "numeric"
            }}
            fullWidth
          />
          <FormHelperText>
            {selectedTier
              ? `Enter 1 to ${selectedTier.maxPerOrder} tickets`
              : "Select a tier first"}
          </FormHelperText>
        </FormControl>

        {error && <FormHelperText error>{error}</FormHelperText>}

        <Button
          variant="contained"
          size="large"
          disabled={!selectedTier || submitting}
          onClick={() => void submit()}
        >
          {submitting ? (
            <CircularProgress size={22} color="inherit" />
          ) : (
            "Reserve seats"
          )}
        </Button>
      </Stack>
    </Container>
  );
}
