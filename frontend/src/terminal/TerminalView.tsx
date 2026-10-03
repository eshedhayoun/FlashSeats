import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import EventBusyRounded from "@mui/icons-material/EventBusyRounded";
import HourglassEmptyRounded from "@mui/icons-material/HourglassEmptyRounded";
import { useEffect, type ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";
import type { EventDetails } from "../api/types";
import { formatDateTime } from "../format/dates";
import { EventStrip } from "../landing/EventHero";
import type { TerminalReason } from "../sale/routeFor";
import { PageTitle } from "../ui/PageTitle";

/** Sold out is derived from live stock, so it can reverse (ADR-035): keep asking. */
const SOLD_OUT_POLL_MS = 15_000;

/**
 * V6 — the sale has nothing for this buyer right now (FE_SPEC V6). "Sold out" and "ended" are
 * different facts and each gets its own words. Sold out never dead-ends: a reservation that runs out
 * puts seats back, and this page notices by itself.
 */
export function TerminalView({
  reason,
  event,
  notice,
  onRefresh
}: {
  reason: TerminalReason;
  event: EventDetails;
  notice?: ReactNode;
  onRefresh: () => Promise<void>;
}) {
  const soldOut = reason === "SOLD_OUT";

  useEffect(() => {
    if (!soldOut) return;
    const timer = window.setInterval(() => void onRefresh(), SOLD_OUT_POLL_MS);
    return () => window.clearInterval(timer);
  }, [soldOut, onRefresh]);

  const Icon = soldOut ? HourglassEmptyRounded : EventBusyRounded;

  return (
    <Container maxWidth="sm" sx={{ py: { xs: 3, sm: 5 } }}>
      <Stack spacing={3}>
        <EventStrip event={event} />
        {notice}
        <Card>
          <CardContent sx={{ p: { xs: 3, sm: 4 } }}>
            <Stack spacing={2} alignItems="flex-start">
              <Icon sx={{ fontSize: 44, color: "text.secondary" }} aria-hidden />
              <PageTitle variant="h3">{soldOut ? "This sale has sold out" : "This sale has ended"}</PageTitle>
              <Typography color="text.secondary" data-testid="terminal-reason" data-reason={reason}>
                {soldOut
                  ? "Every ticket is taken right now. Tickets sometimes come back when someone's reservation ends, so we'll keep checking — this page updates by itself, and your place in line is kept."
                  : `Ticket sales closed on ${formatDateTime(event.saleEndTime)}.`}
              </Typography>
              <Stack direction="row" spacing={1.5} useFlexGap flexWrap="wrap" sx={{ pt: 1 }}>
                {soldOut && (
                  <Button variant="contained" onClick={() => void onRefresh()}>
                    Check again now
                  </Button>
                )}
                <Button component={RouterLink} to="/" variant={soldOut ? "text" : "contained"}>
                  Browse other events
                </Button>
              </Stack>
            </Stack>
          </CardContent>
        </Card>
      </Stack>
    </Container>
  );
}
