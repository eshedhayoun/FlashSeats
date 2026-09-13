import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import type { Availability, EventTier } from "../api/types";

const availabilityCopy: Record<Availability, string> = {
  PLENTY: "Available",
  LIMITED: "Limited",
  SOLD_OUT: "Sold out",
  UNKNOWN: "Checking availability"
};

export function TierCard({
  tier,
  selected,
  onSelect
}: {
  tier: EventTier;
  selected: boolean;
  onSelect: (tier: EventTier) => void;
}) {
  const soldOut = tier.availability === "SOLD_OUT";

  return (
    <Card
      component="button"
      type="button"
      disabled={soldOut}
      onClick={() => onSelect(tier)}
      aria-pressed={selected}
      sx={{
        textAlign: "left",
        border: selected ? 2 : 1,
        borderColor: selected ? "primary.main" : "divider",
        opacity: soldOut ? 0.55 : 1,
        cursor: soldOut ? "not-allowed" : "pointer"
      }}
    >
      <CardContent>
        <Stack direction="row" justifyContent="space-between" spacing={2}>
          <Stack spacing={0.5}>
            <Typography fontWeight={600}>{tier.tierName}</Typography>
            <Typography color="text.secondary">
              {(tier.priceCents / 100).toFixed(2)} {tier.currency} · up to{" "}
              {tier.maxPerOrder} per order
            </Typography>
          </Stack>
          <Chip
            label={availabilityCopy[tier.availability]}
            color={
              tier.availability === "PLENTY"
                ? "success"
                : tier.availability === "LIMITED"
                  ? "warning"
                  : "default"
            }
          />
        </Stack>
      </CardContent>
    </Card>
  );
}
