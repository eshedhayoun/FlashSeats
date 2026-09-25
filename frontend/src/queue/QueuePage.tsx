import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import type { Availability, EventTier, SaleQueueState } from "../api/types";
import { useQueueStream } from "./useQueueStream";
import { HomeButton } from "../shared/HomeButton";

export function QueuePage({
  eventId,
  tiers,
  queue,
  onRefresh
}: {
  eventId: number;
  tiers: EventTier[];
  queue: SaleQueueState;
  onRefresh: () => void;
}) {
  const stream = useQueueStream(eventId, onRefresh);
  const availability = stream.availability?.tiers ?? [];
  const tierMap = new Map(tiers.map((t) => [t.tierId, t.tierName]));

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Card>
        <CardContent>
          <Stack spacing={3} alignItems="center">
            <HomeButton />
            <Typography color="text.secondary">You&apos;re in the queue</Typography>
            <Typography
              component="div"
              variant="h1"
              sx={{ fontVariantNumeric: "tabular-nums" }}
              aria-live="polite"
            >
              {stream.position ?? queue.position ?? "—"}
            </Typography>
            <Typography color="text.secondary" aria-live="polite">
              {formatWaitTime(stream.estWaitSeconds ?? queue.estWaitSeconds)}
            </Typography>
            <Typography color="text.secondary">
              {stream.connection === "open"
                ? "Connected"
                : "Reconnecting — your place is saved"}
            </Typography>
            {availability.length > 0 && (
              <Stack spacing={1} width="100%" aria-label="Live ticket availability">
                <Typography variant="subtitle2">Live availability</Typography>
                {availability.map((tier) => (
                  <Typography key={tier.tierId} color={availabilityColor(tier.level)}>
                    {tierMap.get(tier.tierId) || `Tier ${tier.tierId}`}:{" "}
                    {availabilityLabel(tier.level)}
                  </Typography>
                ))}
              </Stack>
            )}
            <Button variant="outlined" onClick={onRefresh}>
              Refresh status
            </Button>
          </Stack>
        </CardContent>
      </Card>
    </Container>
  );
}

function formatWaitTime(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) {
    return "About 2 minutes estimated wait";
  }
  if (seconds < 60) {
    return "Less than a minute estimated wait";
  }
  if (seconds < 300) {
    return "About 2 minutes estimated wait";
  }
  if (seconds < 600) {
    return "About 5 minutes estimated wait";
  }
  if (seconds < 1200) {
    return "About 10 minutes estimated wait";
  }
  return "About 15 minutes estimated wait";
}

function availabilityLabel(level: Availability): string {
  return level === "SOLD_OUT"
    ? "Sold out"
    : level === "UNKNOWN"
      ? "Temporarily unavailable"
      : level === "LIMITED"
        ? "Limited"
        : "Available";
}

function availabilityColor(level: Availability) {
  return level === "SOLD_OUT"
    ? "text.secondary"
    : level === "UNKNOWN"
      ? "warning.main"
      : level === "LIMITED"
        ? "warning.dark"
        : "success.main";
}
