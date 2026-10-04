import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { useState, type ReactNode } from "react";
import { joinQueue } from "../api/endpoints";
import { asApiError, type ApiError } from "../api/errors";
import type { EventDetails, WindowStatus } from "../api/types";
import { useHasPassed } from "../clock/useClockTick";
import { problemCopy } from "../copy/problemCopy";
import { formatDateTime } from "../format/dates";
import { challengeToken } from "../recaptcha";
import { Countdown } from "../ui/Countdown";
import { Notice } from "../ui/Notice";
import { TierRow } from "../ui/TierOption";
import { EventHero } from "./EventHero";

/**
 * V1 — the event, its tiers, and the way in (FE_SPEC V1).
 *
 * At T-0 the button unlocks on the server's clock with no reload, and nothing joins by itself: a
 * self-firing join from every open tab at exactly t=0 is indistinguishable from a bot.
 */
export function LandingView({
  event,
  windowStatus,
  notice,
  beforeJoin,
  onJoined,
  onEventStale
}: {
  event: EventDetails;
  windowStatus: WindowStatus;
  notice?: ReactNode;
  /** Anything that must happen before joining, such as dropping a pass that could not be used. */
  beforeJoin?: () => Promise<void>;
  onJoined: () => Promise<void>;
  onEventStale: () => void;
}) {
  const [joining, setJoining] = useState(false);
  const [joinError, setJoinError] = useState<ApiError | null>(null);

  // The server's clock says open: unlock now, and let the next poll confirm it.
  const openingPassed = useHasPassed(event.saleStartTime);
  const status = windowStatus === "UPCOMING" && openingPassed ? "OPEN" : windowStatus;
  const canJoin = status === "OPEN" || status === "PAUSED";
  // Every tier gone, as far as the server can read. "Checking…" is not gone (ADR-040).
  const soldOut = event.tiers.length > 0 && event.tiers.every((tier) => tier.availability === "SOLD_OUT");

  const join = async () => {
    setJoining(true);
    setJoinError(null);
    try {
      await beforeJoin?.();
      const recaptchaToken = await challengeToken("join");
      await joinQueue({ eventId: event.eventId, ...(recaptchaToken ? { recaptchaToken } : {}) });
      await onJoined();
    } catch (cause) {
      const error = asApiError(cause, "We couldn't add you to the line. Please try again.");
      if (error.code === "SALE_CLOSED") {
        await onJoined(); // the server knows it ended; rehydrate onto the closed screen
        return;
      }
      if (error.code === "SALE_NOT_OPEN") onEventStale();
      setJoinError(error);
    } finally {
      setJoining(false);
    }
  };

  const joinCopy = joinError ? problemCopy(joinError) : null;

  return (
    <>
      <EventHero
        event={event}
        eyebrow={status === "OPEN" ? "On sale now" : status === "PAUSED" ? "Sales paused" : "Sale opens soon"}
      />
      <Container maxWidth="md" sx={{ py: { xs: 3, sm: 5 } }}>
        <Stack spacing={3}>
          {notice}

          <Card>
            <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
              <Stack spacing={2}>
                {status === "UPCOMING" && (
                  <>
                    <Typography variant="h3" component="h2">
                      Sale opens in <Countdown expiresAt={event.saleStartTime} variant="presale" onExpire={onEventStale} />
                    </Typography>
                    <Typography color="text.secondary">
                      {formatDateTime(event.saleStartTime)}. Keep this page open — the button below unlocks
                      the moment the sale opens.
                    </Typography>
                  </>
                )}
                {status === "OPEN" && !soldOut && (
                  <>
                    <Typography variant="h3" component="h2">
                      Tickets are on sale now
                    </Typography>
                    <Typography color="text.secondary">
                      Join the line and we'll let you in to choose your seats in turn. Leaving this page
                      doesn't lose your place.
                    </Typography>
                  </>
                )}
                {status === "OPEN" && soldOut && (
                  <>
                    <Typography variant="h3" component="h2">
                      Sold out right now
                    </Typography>
                    <Typography color="text.secondary">
                      Every ticket is taken at the moment. Tickets can come back when someone's reservation
                      ends — join the line and you'll be ahead of anyone who joins later.
                    </Typography>
                  </>
                )}
                {status === "PAUSED" && (
                  <Notice severity="warning" title="Sales are paused for a moment">
                    You can still join the line, and your place is kept. We'll let people in again as soon as
                    sales resume.
                  </Notice>
                )}
                {status === "CLOSED" && (
                  <Typography variant="h3" component="h2">
                    This sale has ended
                  </Typography>
                )}

                {joinCopy && (
                  <Notice severity="error" title={joinCopy.title}>
                    {joinCopy.message}
                  </Notice>
                )}

                <Button
                  variant="contained"
                  size="large"
                  onClick={() => void join()}
                  disabled={!canJoin || joining}
                  sx={{ alignSelf: { xs: "stretch", sm: "flex-start" }, minWidth: 220 }}
                  data-testid="join"
                >
                  {joining ? (
                    <>
                      <CircularProgress size={20} color="inherit" sx={{ mr: 1.5 }} />
                      Joining the line…
                    </>
                  ) : status === "PAUSED" || soldOut ? (
                    "Join the line"
                  ) : (
                    "Join the sale"
                  )}
                </Button>
              </Stack>
            </CardContent>
          </Card>

          {event.description && (
            <Typography color="text.secondary" sx={{ whiteSpace: "pre-line" }}>
              {event.description}
            </Typography>
          )}

          <Card>
            <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
              <Typography variant="h4" component="h2" gutterBottom>
                Tickets
              </Typography>
              {event.tiers.length === 0 ? (
                <Typography color="text.secondary">Ticket types haven't been announced yet.</Typography>
              ) : (
                event.tiers.map((tier) => <TierRow key={tier.tierId} tier={tier} />)
              )}
              <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
                You'll choose a tier and how many tickets when it's your turn.
              </Typography>
            </CardContent>
          </Card>
        </Stack>
      </Container>
    </>
  );
}
