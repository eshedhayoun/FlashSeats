import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import type { SaleQueueState } from "../api/types";
import { useQueueStream } from "./useQueueStream";

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

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Card>
        <CardContent>
          <Stack spacing={3} alignItems="center">
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
            <Button variant="outlined" onClick={onRefresh}>
              Refresh status
            </Button>
          </Stack>
        </CardContent>
      </Card>
    </Container>
  );
}
