import { Route, Routes, useParams } from "react-router-dom";
import { EventIndexPage } from "../events/EventIndexPage";
import { NotFoundPage } from "../notfound/NotFoundPage";
import { ConfirmationPage } from "../orders/ConfirmationPage";
import { EventPage } from "../sale/EventPage";

/**
 * Three addresses. Inside `/events/:eventId` the view is chosen by the server's state for that sale
 * (FE_SPEC §1), not by the path after it, so a reload, Back or a second tab always lands where the
 * server says the buyer is.
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<EventIndexPage />} />
      <Route path="/events/:eventId/*" element={<EventRoute />} />
      <Route path="/orders/:orderNumber" element={<ConfirmationPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}

function EventRoute() {
  const raw = useParams().eventId ?? "";
  const eventId = /^[1-9]\d{0,15}$/.test(raw) ? Number(raw) : NaN;
  if (!Number.isSafeInteger(eventId)) {
    // Never a request for /events/NaN.
    return <NotFoundPage what="event" />;
  }
  // Keyed, so moving between two sales starts each one's state afresh (FE_SPEC §0 rule 5).
  return <EventPage key={eventId} eventId={eventId} />;
}
