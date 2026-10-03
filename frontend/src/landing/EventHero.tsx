import Box from "@mui/material/Box";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import CalendarMonthRounded from "@mui/icons-material/CalendarMonthRounded";
import PlaceRounded from "@mui/icons-material/PlaceRounded";
import type { ReactNode } from "react";
import type { EventDetails } from "../api/types";
import { HERO_GRADIENT } from "../app/theme";
import { formatDateTime } from "../format/dates";
import { PageTitle } from "../ui/PageTitle";

/** The event, large, on the brand gradient. The landing view's header (FE_SPEC V1). */
export function EventHero({ event, eyebrow }: { event: EventDetails; eyebrow?: ReactNode }) {
  return (
    <Box sx={{ background: HERO_GRADIENT, color: "#FFFFFF", py: { xs: 5, sm: 7 } }}>
      <Container maxWidth="md">
        <Stack spacing={1.5}>
          {eyebrow && (
            <Typography variant="overline" sx={{ opacity: 0.9 }}>
              {eyebrow}
            </Typography>
          )}
          <PageTitle variant="h1" dir="auto">
            {event.title}
          </PageTitle>
          <EventFacts event={event} />
        </Stack>
      </Container>
    </Box>
  );
}

/** Date and venue on one line, wrapping on a narrow screen. */
export function EventFacts({ event, muted }: { event: Pick<EventDetails, "eventStartTime" | "venueName">; muted?: boolean }) {
  return (
    <Stack
      direction="row"
      useFlexGap
      flexWrap="wrap"
      sx={{ columnGap: 2.5, rowGap: 0.75, color: muted ? "text.secondary" : "inherit", opacity: muted ? 1 : 0.95 }}
    >
      <Stack direction="row" spacing={0.75} alignItems="center">
        <CalendarMonthRounded fontSize="small" aria-hidden />
        <Typography>{formatDateTime(event.eventStartTime)}</Typography>
      </Stack>
      <Stack direction="row" spacing={0.75} alignItems="center">
        <PlaceRounded fontSize="small" aria-hidden />
        <Typography dir="auto">{event.venueName}</Typography>
      </Stack>
    </Stack>
  );
}

/** The event, small: what the buyer is buying, on every step after the landing view. */
export function EventStrip({ event }: { event: EventDetails }) {
  return (
    <Stack spacing={0.5} sx={{ pb: 1 }}>
      <Typography variant="subtitle1" component="p" color="primary.main" fontWeight={750} dir="auto">
        {event.title}
      </Typography>
      <EventFacts event={event} muted />
    </Stack>
  );
}
