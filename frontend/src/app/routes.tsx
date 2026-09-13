import { Navigate, Route, Routes } from "react-router-dom";
import { EventPage } from "../sale/EventPage";
import { ConfirmationPage } from "../orders/ConfirmationPage";
import { getEvents } from "../api/endpoints";
import { useEffect, useState } from "react";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { ApiError } from "../api/errors";

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

  useEffect(() => {
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
      </Container>
    );
  }

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
      </Stack>
    </Container>
  );
}
