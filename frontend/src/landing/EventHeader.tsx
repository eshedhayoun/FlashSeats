import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import type { EventDetails } from "../api/types";
import { Countdown } from "../shared/Countdown";

export function EventHeader({ event }: { event: EventDetails }) {
  return (
    <Stack spacing={1}>
      <Typography variant="overline" color="primary">
        FlashSeats
      </Typography>
      <Typography component="h1" variant="h3">
        {event.title}
      </Typography>
      <Typography color="text.secondary">
        {event.venueName} · {new Date(event.eventStartTime).toLocaleString()}
      </Typography>
      {event.description && (
        <Typography color="text.secondary">{event.description}</Typography>
      )}
      {event.windowStatus === "UPCOMING" && (
        <Typography>
          Sale opens in{" "}
          <Countdown expiresAt={event.saleStartTime} announce />
        </Typography>
      )}
      {event.windowStatus === "OPEN" && (
        <Typography color="success.main">Sale is open</Typography>
      )}
      {event.windowStatus === "CLOSED" && (
        <Typography color="text.secondary">Sales have ended</Typography>
      )}
    </Stack>
  );
}
