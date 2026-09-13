import { Navigate, Route, Routes } from "react-router-dom";
import { EventPage } from "../sale/EventPage";

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/events/:eventId/*" element={<EventPage />} />
      <Route path="/orders/:orderNumber" element={<PlaceholderView title="Order confirmation" />} />
      <Route path="/" element={<PlaceholderView title="FlashSeats" />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function PlaceholderView({ title }: { title: string }) {
  return (
    <main>
      <h1>{title}</h1>
      <p>The frontend foundation is ready. Feature views will be implemented incrementally.</p>
    </main>
  );
}
