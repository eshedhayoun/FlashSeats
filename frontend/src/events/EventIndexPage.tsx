import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardActionArea from "@mui/material/CardActionArea";
import CardContent from "@mui/material/CardContent";
import Container from "@mui/material/Container";
import Grid from "@mui/material/Grid2";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import EventAvailableRounded from "@mui/icons-material/EventAvailableRounded";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link as RouterLink, useLocation } from "react-router-dom";
import { getEvents } from "../api/endpoints";
import { asApiError, type ApiError } from "../api/errors";
import type { EventListItem } from "../api/types";
import { HERO_GRADIENT } from "../app/theme";
import { formatDateTime } from "../format/dates";
import { EventFacts } from "../landing/EventHero";
import { RecentOrders } from "../orders/RecentOrders";
import { EmptyState } from "../ui/EmptyState";
import { ErrorState } from "../ui/ErrorState";
import { LoadingState } from "../ui/LoadingState";
import { PageTitle } from "../ui/PageTitle";
import { WindowChip } from "../ui/StatusChip";

const ORDER: Record<EventListItem["windowStatus"], number> = { OPEN: 0, PAUSED: 1, UPCOMING: 2, CLOSED: 3 };

/** On sale first, then opening soonest, then the ones that have ended — most recent first. */
export function sortEvents(events: EventListItem[]): EventListItem[] {
  return [...events].sort((a, b) => {
    const byStatus = ORDER[a.windowStatus] - ORDER[b.windowStatus];
    if (byStatus !== 0) return byStatus;
    const byOpening = Date.parse(a.saleStartTime) - Date.parse(b.saleStartTime);
    return a.windowStatus === "CLOSED" ? -byOpening : byOpening;
  });
}

/** Every sale on offer, and every ticket this browser has bought. */
export function EventIndexPage() {
  const [events, setEvents] = useState<EventListItem[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const location = useLocation();
  const ticketsRef = useRef<HTMLElement>(null);

  const load = useCallback(async () => {
    try {
      setEvents(sortEvents(await getEvents()));
      setError(null);
    } catch (cause) {
      setError(asApiError(cause, "The events could not be loaded."));
    }
  }, []);

  useEffect(() => {
    void load();
    const onVisible = () => {
      if (document.visibilityState === "visible") void load();
    };
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("online", load);
    return () => {
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("online", load);
    };
  }, [load]);

  // "My tickets" in the header lands here.
  useEffect(() => {
    if (location.hash === "#tickets" && events !== null) {
      ticketsRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
      ticketsRef.current?.focus({ preventScroll: true });
    }
  }, [location.hash, events]);

  return (
    <>
      <Box sx={{ background: HERO_GRADIENT, color: "#FFFFFF", py: { xs: 6, sm: 9 } }}>
        <Container maxWidth="lg">
          <Stack spacing={2} sx={{ maxWidth: 720 }}>
            <PageTitle variant="h1">Fair, fast ticket sales</PageTitle>
            <Typography sx={{ fontSize: { xs: "1.05rem", sm: "1.2rem" }, opacity: 0.95 }}>
              Join the line, get your turn, and choose your seats without the scramble. Your place is kept even
              if you close the page.
            </Typography>
          </Stack>
        </Container>
      </Box>

      <Container maxWidth="lg" sx={{ py: { xs: 4, sm: 6 } }}>
        <Stack spacing={6}>
          <Box component="section" aria-labelledby="sales-heading">
            <Typography id="sales-heading" variant="h3" component="h2" sx={{ mb: 2.5 }}>
              Sales
            </Typography>
            {events === null && !error && <LoadingState shape="list" label="Loading events" />}
            {error && events === null && <ErrorState error={error} onRetry={() => void load()} asPageTitle={false} />}
            {events !== null && events.length === 0 && (
              <EmptyState icon={<EventAvailableRounded sx={{ fontSize: 40 }} />} title="No sales right now">
                New sales appear here as soon as they're announced.
              </EmptyState>
            )}
            {events !== null && events.length > 0 && (
              <Grid container spacing={2.5} component="ul" sx={{ listStyle: "none", p: 0, m: 0 }}>
                {events.map((event) => (
                  <Grid key={event.eventId} size={{ xs: 12, md: 6 }} component="li">
                    <EventCard event={event} />
                  </Grid>
                ))}
              </Grid>
            )}
          </Box>

          <Box component="section" id="tickets" ref={ticketsRef} tabIndex={-1} aria-labelledby="tickets-heading" sx={{ outline: "none", scrollMarginTop: 88 }}>
            <Typography id="tickets-heading" variant="h3" component="h2" sx={{ mb: 2.5 }}>
              Your tickets
            </Typography>
            <RecentOrders />
          </Box>
        </Stack>
      </Container>
    </>
  );
}

function EventCard({ event }: { event: EventListItem }) {
  const when =
    event.windowStatus === "UPCOMING"
      ? `Sale opens ${formatDateTime(event.saleStartTime)}`
      : event.windowStatus === "PAUSED"
        ? "Sales are paused for a moment — you can still join the line"
        : event.windowStatus === "OPEN"
          ? "Tickets on sale now"
          : "This sale has ended";

  return (
    <Card sx={{ height: "100%" }}>
      <CardActionArea
        component={RouterLink}
        to={`/events/${event.eventId}`}
        sx={{ height: "100%", alignItems: "stretch" }}
        data-testid={`event-card-${event.eventId}`}
      >
        <CardContent sx={{ p: 3, height: "100%" }}>
          <Stack spacing={1.5} sx={{ height: "100%" }}>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
              <Typography variant="h4" component="h3" dir="auto">
                {event.title}
              </Typography>
              <WindowChip status={event.windowStatus} />
            </Stack>
            <EventFacts event={event} muted />
            <Box sx={{ flex: 1 }} />
            <Typography variant="body2" fontWeight={650} color="primary.main">
              {when} →
            </Typography>
          </Stack>
        </CardContent>
      </CardActionArea>
    </Card>
  );
}

