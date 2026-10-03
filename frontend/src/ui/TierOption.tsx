import Box from "@mui/material/Box";
import { alpha } from "@mui/material/styles";
import Radio from "@mui/material/Radio";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import type { EventTier } from "../api/types";
import { formatMoney } from "../format/money";
import { AvailabilityChip } from "./StatusChip";

/**
 * A tier the buyer can choose: a radio inside a card-sized label, so the whole card is the target and
 * the arrow keys move between tiers as in any radio group. Sold out is disabled; "Checking…" stays
 * selectable — the reserve call is what decides (ADR-040).
 */
export function TierOption({
  tier,
  checked,
  note
}: {
  tier: EventTier;
  checked: boolean;
  note?: string | null;
}) {
  const soldOut = tier.availability === "SOLD_OUT";
  const noteId = `tier-note-${tier.tierId}`;

  return (
    <Box
      component="label"
      data-testid={`tier-${tier.tierId}`}
      sx={{
        display: "flex",
        alignItems: "center",
        gap: 1.5,
        p: 2,
        pl: 1,
        borderRadius: 3,
        border: 2,
        borderColor: checked ? "primary.main" : "divider",
        bgcolor: (theme) => (checked ? alpha(theme.palette.primary.main, 0.07) : theme.palette.background.paper),
        cursor: soldOut ? "not-allowed" : "pointer",
        opacity: soldOut ? 0.6 : 1,
        transition: "border-color 150ms ease-out, background-color 150ms ease-out",
        "&:hover": soldOut ? undefined : { borderColor: "primary.main" },
        "&:has(input:focus-visible)": { outline: "3px solid", outlineColor: "primary.main", outlineOffset: 2 }
      }}
    >
      <Radio value={String(tier.tierId)} disabled={soldOut} inputProps={{ "aria-describedby": note ? noteId : undefined }} />
      <Stack spacing={0.25} sx={{ flex: 1, minWidth: 0 }}>
        <Typography fontWeight={700} dir="auto">
          {tier.tierName}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {formatMoney(tier.priceCents, tier.currency)} each · up to {tier.maxPerOrder} per order
        </Typography>
        {note && (
          <Typography id={noteId} variant="body2" color="error.main" fontWeight={600}>
            {note}
          </Typography>
        )}
      </Stack>
      <AvailabilityChip level={tier.availability} />
    </Box>
  );
}

/** A tier shown for information only: on the event page, choosing one would mean nothing yet. */
export function TierRow({ tier }: { tier: EventTier }) {
  return (
    <Stack
      direction="row"
      alignItems="center"
      spacing={2}
      sx={{ py: 1.75, borderBottom: 1, borderColor: "divider", "&:last-of-type": { borderBottom: 0 } }}
    >
      <Stack spacing={0.25} sx={{ flex: 1, minWidth: 0 }}>
        <Typography fontWeight={700} dir="auto">
          {tier.tierName}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Up to {tier.maxPerOrder} per order
        </Typography>
      </Stack>
      <Typography className="tabular" fontWeight={700}>
        {formatMoney(tier.priceCents, tier.currency)}
      </Typography>
      <AvailabilityChip level={tier.availability} />
    </Stack>
  );
}
