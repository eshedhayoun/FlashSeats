import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Divider from "@mui/material/Divider";
import Link from "@mui/material/Link";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import OpenInNewRounded from "@mui/icons-material/OpenInNewRounded";
import { formatDateTime } from "../format/dates";
import { formatMoney } from "../format/money";
import { Notice } from "../ui/Notice";
import { WindowChip } from "../ui/StatusChip";
import { actionsFor, readiness, type AdminEvent, type EventAction } from "./adminApi";

export const ACTION_LABELS: Record<EventAction, string> = {
  prewarm: "Pre-warm",
  pause: "Pause",
  resume: "Resume",
  rebuild: "Rebuild stock",
  delete: "Delete"
};

/** One sale as the operator sees it: its window, exact stock, whether it will sell, what can be done. */
export function EventCard({
  event,
  busyAction,
  onAction
}: {
  event: AdminEvent;
  busyAction: EventAction | null;
  onAction: (action: EventAction) => void;
}) {
  const live = event.status === "PUBLISHED" || event.status === "PAUSED";
  const warning = readiness(event);
  const actions = actionsFor(event);

  return (
    <Card variant="outlined" data-testid={`admin-event-${event.eventId}`}>
      <CardContent>
        <Stack spacing={1.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "flex-start", justifyContent: "space-between" }}>
            <div>
              <Typography variant="h6" component="h2" dir="auto" sx={{ fontWeight: 700 }}>
                {event.title}
              </Typography>
              <Typography variant="body2" color="text.secondary" dir="auto">
                {event.venueName} · #{event.eventId}
              </Typography>
            </div>
            <Box sx={{ flexShrink: 0 }}>
              {live ? <WindowChip status={event.windowStatus} /> : <Chip size="small" label={event.status} />}
            </Box>
          </Stack>

          <Typography variant="body2" color="text.secondary">
            Sale {formatDateTime(event.saleStartTime)} → {formatDateTime(event.saleEndTime)} · Event{" "}
            {formatDateTime(event.eventStartTime)}
            {live && (
              <>
                {" · "}
                <Link href={`/events/${event.eventId}`} target="_blank" rel="noopener">
                  Buyer page <OpenInNewRounded sx={{ fontSize: 14, verticalAlign: "middle" }} />
                </Link>
              </>
            )}
          </Typography>

          <Divider />
          <Stack spacing={0.5}>
            {event.tiers.map((tier) => (
              <Stack key={tier.tierId} direction="row" spacing={2} sx={{ justifyContent: "space-between" }}>
                <Typography variant="body2" dir="auto" sx={{ flex: 1 }}>
                  {tier.tierName}
                </Typography>
                <Typography variant="body2" sx={{ width: 96, textAlign: "right" }}>
                  {formatMoney(tier.priceCents, tier.currency)}
                </Typography>
                <Typography variant="body2" sx={{ width: 150, textAlign: "right" }}>
                  {/* No counter is unknown, never zero (ADR-040). */}
                  {tier.remaining === null ? "unknown" : tier.remaining.toLocaleString()} /{" "}
                  {tier.totalCapacity.toLocaleString()} left
                </Typography>
              </Stack>
            ))}
          </Stack>

          {warning && <Notice severity={warning.severity}>{warning.message}</Notice>}

          {actions.length > 0 && (
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", rowGap: 1 }}>
              {actions.map((action) => (
                <Button
                  key={action}
                  size="small"
                  variant={action === "delete" ? "outlined" : "contained"}
                  color={action === "delete" ? "error" : "primary"}
                  disabled={busyAction !== null}
                  onClick={() => onAction(action)}
                  startIcon={busyAction === action ? <CircularProgress size={14} color="inherit" /> : undefined}
                >
                  {ACTION_LABELS[action]}
                </Button>
              ))}
            </Stack>
          )}
        </Stack>
      </CardContent>
    </Card>
  );
}
