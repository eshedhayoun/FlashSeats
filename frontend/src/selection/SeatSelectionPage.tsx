import { useMemo, useState } from "react";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import FormControl from "@mui/material/FormControl";
import FormHelperText from "@mui/material/FormHelperText";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import Select, { type SelectChangeEvent } from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { createHold } from "../api/endpoints";
import { ApiError } from "../api/errors";
import type { EventDetails } from "../api/types";
import { Countdown } from "../shared/Countdown";
import { setHoldToken, getAdmissionToken } from "../sale/storage";
import { TierCard } from "../landing/TierCard";

export function SeatSelectionPage({
  event,
  eventId,
  admissionExpiresAt,
  onRefresh
}: {
  event: EventDetails;
  eventId: number;
  admissionExpiresAt: string | null;
  onRefresh: () => void;
}) {
  const [selectedTierId, setSelectedTierId] = useState<number | null>(null);
  const [quantity, setQuantity] = useState(1);
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
    setQuantity((current) => Math.min(current, tier.maxPerOrder));
    setError(null);
  };

  const submit = async () => {
    if (!selectedTier) return;

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
        { eventId, tierId: selectedTier.tierId, quantity },
        admissionToken
      );
      setHoldToken(eventId, hold.holdToken);
      onRefresh();
    } catch (cause) {
      if (!(cause instanceof ApiError)) {
        setError("Those seats could not be reserved. Please try again.");
      } else if (cause.code === "INSUFFICIENT_STOCK") {
        setError("Those seats just sold out. Pick another tier.");
        onRefresh();
      } else if (cause.code === "QUANTITY_EXCEEDS_LIMIT") {
        const serverLimit = selectedTier.maxPerOrder;
        setQuantity(serverLimit);
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

  const quantityOptions = selectedTier
    ? Array.from({ length: selectedTier.maxPerOrder }, (_, index) => index + 1)
    : [];

  return (
    <Container maxWidth="sm" sx={{ py: 6 }}>
      <Stack spacing={3}>
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
          <InputLabel id="quantity-label">Quantity</InputLabel>
          <Select
            labelId="quantity-label"
            value={String(quantity)}
            label="Quantity"
            onChange={(event: SelectChangeEvent) =>
              setQuantity(Number(event.target.value))
            }
          >
            {quantityOptions.map((value) => (
              <MenuItem key={value} value={value}>
                {value}
              </MenuItem>
            ))}
          </Select>
          <FormHelperText>
            {selectedTier
              ? `Up to ${selectedTier.maxPerOrder} per order`
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
