import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import type { Availability, SaleQueueState } from "../api/types";
import { useQueueStream } from "./useQueueStream";
import { HomeButton } from "../shared/HomeButton";

export function QueuePage({
  eventId,
  queue,
  onRefresh
}: {
  eventId: number;
  queue: SaleQueueState;
  onRefresh: () => void;
}) {
  const stream = useQueueStream(eventId, onRefresh);
  const availability = stream.availability?.tiers ?? [];

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
              {stream.estWaitSeconds ?? queue.estWaitSeconds ?? 0} seconds estimated
              wait
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
                    Tier {tier.tierId}: {availabilityLabel(tier.level)}
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
