import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Container from "@mui/material/Container";
import LinearProgress from "@mui/material/LinearProgress";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { leaveQueue } from "../api/endpoints";
import { asApiError, type ApiError } from "../api/errors";
import type { EventDetails, SaleQueueState, WindowStatus } from "../api/types";
import { problemCopy } from "../copy/problemCopy";
import { formatWait, queueProgress } from "../format/queue";
import { EventStrip } from "../landing/EventHero";
import { getQueueStart, removeQueueStart, setQueueStart } from "../sale/storage";
import { AvailabilityChip } from "../ui/StatusChip";
import { ConfirmDialog } from "../ui/ConfirmDialog";
import { ConnectionIndicator } from "../ui/ConnectionIndicator";
import { Notice } from "../ui/Notice";
import { PageTitle } from "../ui/PageTitle";
import { QueuePosition } from "../ui/QueuePosition";
import { useQueueStream } from "./useQueueStream";

/**
 * V2 — the waiting room (FE_SPEC V2). Built to lower anxiety: the position only ever goes down, the
 * estimate is honest about not knowing, a dropped connection says the place is safe, and there is no
 * refresh button to hammer — the page updates itself.
 */
export function QueueView({
  event,
  queue,
  windowStatus,
  notice,
  onRefresh,
  onLeft
}: {
  event: EventDetails;
  queue: SaleQueueState;
  windowStatus: WindowStatus;
  notice?: ReactNode;
  onRefresh: () => void;
  onLeft: () => Promise<void>;
}) {
  const eventId = event.eventId;
  const stream = useQueueStream(eventId, onRefresh, windowStatus === "PAUSED");

  // Monotonic across both sources: a refresh can carry an older number than the stream.
  const shown = useRef<number | null>(null);
  const candidates = [stream.position, queue.position, shown.current].filter((value): value is number => value !== null);
  const position = candidates.length > 0 ? Math.min(...candidates) : null;
  shown.current = position;

  const [start, setStart] = useState<number | null>(() => getQueueStart(eventId));
  useEffect(() => {
    if (position === null) return;
    if (start === null || start < position) {
      setQueueStart(eventId, position);
      setStart(position);
    }
  }, [eventId, position, start]);

  const progress = queueProgress(start, position);
  const paused = stream.paused;
  const estimate = stream.estWaitSeconds ?? queue.estWaitSeconds;

  const [confirmLeave, setConfirmLeave] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [leaveError, setLeaveError] = useState<ApiError | null>(null);

  const leave = async () => {
    setLeaving(true);
    setLeaveError(null);
    try {
      await leaveQueue(eventId);
      removeQueueStart(eventId);
      setConfirmLeave(false);
      await onLeft();
    } catch (cause) {
      setLeaveError(asApiError(cause, "We couldn't take you out of the line. Please try again."));
    } finally {
      setLeaving(false);
    }
  };

  return (
    <Container maxWidth="sm" sx={{ py: { xs: 3, sm: 5 } }}>
      <Stack spacing={3}>
        <EventStrip event={event} />
        {notice}

        <Card>
          <CardContent sx={{ p: { xs: 3, sm: 4 } }}>
            <Stack spacing={3} alignItems="center" textAlign="center">
              <PageTitle variant="h4">You're in the line</PageTitle>
              <QueuePosition position={position} />
              <Box sx={{ width: "100%" }}>
                {/* Indeterminate means "moving, amount unknown" — never shown while paused. */}
                <LinearProgress
                  variant={progress === null && !paused ? "indeterminate" : "determinate"}
                  value={progress ?? 0}
                  aria-label="Your progress through the line"
                />
              </Box>
              {paused ? (
                <Typography color="text.secondary">The line will move again as soon as sales resume.</Typography>
              ) : (
                <Typography color="text.secondary" data-testid="queue-estimate">
                  {formatWait(estimate)}
                </Typography>
              )}
              <ConnectionIndicator state={stream.connection} />
            </Stack>
          </CardContent>
        </Card>

        {paused && (
          <Notice severity="warning" title="Sales are paused for a moment" testId="paused">
            Your place is kept. Nobody is let in while sales are paused, so the line holds still until they
            resume.
          </Notice>
        )}

        <Card>
          <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
            <Typography variant="h5" component="h2" gutterBottom>
              Availability right now
            </Typography>
            <Stack component="ul" aria-label="Live ticket availability" sx={{ listStyle: "none", m: 0, p: 0 }}>
              {event.tiers.map((tier) => (
                <Stack
                  component="li"
                  key={tier.tierId}
                  direction="row"
                  justifyContent="space-between"
                  alignItems="center"
                  sx={{ py: 1, borderBottom: 1, borderColor: "divider", "&:last-of-type": { borderBottom: 0 } }}
                >
                  <Typography>{tier.tierName}</Typography>
                  <AvailabilityChip level={stream.availability[tier.tierId] ?? tier.availability} />
                </Stack>
              ))}
            </Stack>
          </CardContent>
        </Card>

        <Typography variant="body2" color="text.secondary">
          Keep this page open and we'll take you to your seats the moment it's your turn. If you close it,
          your place is kept — come back to this page to carry on.
        </Typography>

        <Box>
          <Button color="inherit" onClick={() => setConfirmLeave(true)} data-testid="leave-queue">
            Leave the line
          </Button>
        </Box>
      </Stack>

      <ConfirmDialog
        open={confirmLeave}
        title="Leave the line?"
        confirmLabel="Leave the line"
        cancelLabel="Stay in line"
        destructive
        busy={leaving}
        onConfirm={() => void leave()}
        onCancel={() => setConfirmLeave(false)}
      >
        If you join again later, you'll start at the back of the line.
        {leaveError && (
          <Box component="span" sx={{ display: "block", mt: 1.5, color: "error.main" }}>
            {problemCopy(leaveError).title}. {problemCopy(leaveError).message}
          </Box>
        )}
      </ConfirmDialog>
    </Container>
  );
}
