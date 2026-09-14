import { Navigate, Route, Routes } from "react-router-dom";
import { EventPage } from "../sale/EventPage";
import { ConfirmationPage } from "../orders/ConfirmationPage";
import { getEvents } from "../api/endpoints";
import { useEffect, useState } from "react";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Typography from "@mui/material/Typography";
import { ApiError } from "../api/errors";
import { getRecentOrders } from "../sale/storage";

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/events/:eventId/*" element={<EventPage />} />
      <Route path="/orders/:orderNumber" element={<ConfirmationPage />} />
      <Route path="/" element={<EventIndexPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function EventIndexPage() {
  const [events, setEvents] = useState<
    Awaited<ReturnType<typeof getEvents>> | null
  >(null);
  const [error, setError] = useState<ApiError | null>(null);

  const loadEvents = () => {
    setError(null);
    void getEvents()
      .then(setEvents)
      .catch((cause: unknown) => {
        setError(
          cause instanceof ApiError
            ? cause
            : new ApiError({
                type: "about:blank",
                title: "Events unavailable",
                status: 0,
                detail: "Events could not be loaded.",
                code: "CLIENT_ERROR"
              })
        );
      });
  };

  useEffect(() => {
    loadEvents();

    const onVisible = () => {
      if (document.visibilityState === "visible") loadEvents();
    };
    const onOnline = () => loadEvents();
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("online", onOnline);

    return () => {
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("online", onOnline);
    };
  }, []);

  if (!events && !error) {
    return (
      <Container sx={{ py: 8 }}>
        <CircularProgress aria-label="Loading events" />
      </Container>
    );
  }

  if (error) {
    return (
      <Container sx={{ py: 8 }}>
        <Typography color="error">{error.message}</Typography>
        <Button variant="outlined" onClick={loadEvents} sx={{ mt: 2 }}>
          Try again
        </Button>
      </Container>
    );
  }

  const recentOrders = getRecentOrders();

  return (
    <Container maxWidth="sm" sx={{ py: 6 }}>
      <Stack spacing={2}>
        <Typography component="h1" variant="h3">
          FlashSeats
        </Typography>
        <Typography color="text.secondary">
          Choose an event to view its sale.
        </Typography>
        {events?.map((event) => (
          <Button
            key={event.eventId}
            href={`/events/${event.eventId}`}
            variant="outlined"
            sx={{ justifyContent: "flex-start", textAlign: "left" }}
          >
            {event.title} · {event.venueName}
          </Button>
        ))}
        {events?.length === 0 && (
          <Typography color="text.secondary">No events are published yet.</Typography>
        )}
        {recentOrders.length > 0 && (
          <Stack spacing={1} sx={{ mt: 3 }}>
            <Typography component="h2" variant="h6">
              Your recent orders
            </Typography>
            {recentOrders.map((order) => (
              <Card key={order.orderNumber} variant="outlined">
                <CardContent>
                  <Stack spacing={1}>
                    <Typography>{order.eventTitle}</Typography>
                    <Typography color="text.secondary">
                      Order {order.orderNumber}
                    </Typography>
                    <Button
                      component="a"
                      href={`/orders/${encodeURIComponent(
                        order.orderNumber
                      )}?receiptToken=${encodeURIComponent(order.receiptToken)}`}
                      variant="text"
                      sx={{ alignSelf: "flex-start", px: 0 }}
                    >
                      View order and ticket
                    </Button>
                  </Stack>
                </CardContent>
              </Card>
            ))}
          </Stack>
        )}
      </Stack>
    </Container>
  );
}
