import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { admitQueue } from "../api/endpoints";
import { ApiError } from "../api/errors";
import { LandingPage } from "../landing/LandingPage";
import { QueuePage } from "../queue/QueuePage";
import { SeatSelectionPage } from "../selection/SeatSelectionPage";
import { setAdmissionToken } from "./storage";
import { useSaleState } from "./useSaleState";
import { useEvent } from "../landing/useEvent";
import { CheckoutPage } from "../checkout/CheckoutPage";
import { DegradedState } from "../shared/DegradedState";
import { TerminalPage } from "../terminal/TerminalPage";

export function EventPage() {
  const eventId = Number(useParams().eventId);
  const navigate = useNavigate();
  const eventResult = useEvent(eventId);
  const sale = useSaleState(eventId);
  const [admitting, setAdmitting] = useState(false);
  const [admitError, setAdmitError] = useState<ApiError | null>(null);

  const refreshSale = useCallback(() => {
    void sale.refresh();
  }, [sale.refresh]);

  useEffect(() => {
    const route = sale.data?.route;
    if (route?.view !== "promoted" || admitting) return;

    setAdmitting(true);
    setAdmitError(null);
    void admitQueue({ eventId }, route.passToken)
      .then((response) => {
        setAdmissionToken(eventId, response.admissionToken);
        return sale.refresh();
      })
      .catch((cause: unknown) => {
        setAdmitError(
          cause instanceof ApiError
            ? cause
            : new ApiError({
                type: "about:blank",
                title: "Admission unavailable",
                status: 0,
                detail: "Your entry pass could not be exchanged.",
                code: "CLIENT_ERROR"
              })
        );
      })
      .finally(() => setAdmitting(false));
  }, [admitting, eventId, sale.data?.route, sale.refresh]);

  useEffect(() => {
    if (sale.data?.route.view === "confirmation") {
      navigate(`/orders/${sale.data.route.orderNumber}`, { replace: true });
    }
  }, [navigate, sale.data?.route]);

  if (!Number.isInteger(eventId) || eventId <= 0) {
    return <Typography>This event link is invalid.</Typography>;
  }

  if (sale.status === "loading" || sale.status === "idle") {
    return (
      <Container sx={{ py: 8 }}>
        <CircularProgress aria-label="Loading sale" />
      </Container>
    );
  }

  if (sale.error && !sale.data) {
    return <Typography>{sale.error.message}</Typography>;
  }
  if (eventResult.error) {
    return <Typography>{eventResult.error.message}</Typography>;
  }

  const route = sale.data?.route;
  if (!route || !eventResult.event) {
    return <Typography>Sale state is unavailable.</Typography>;
  }

  if (route.view === "landing") return <LandingPage />;
  if (route.view === "queue") {
    return (
      <QueuePage
        eventId={eventId}
        queue={route.queue}
        onRefresh={refreshSale}
      />
    );
  }
  if (route.view === "promoted" || admitting) {
    return (
      <Container sx={{ py: 8 }}>
        <Stack spacing={2} alignItems="center">
          <CircularProgress aria-label="Entering sale" />
          <Typography>It&apos;s your turn. Entering the sale…</Typography>
          {admitError && <Typography color="error">{admitError.message}</Typography>}
        </Stack>
      </Container>
    );
  }
  if (route.view === "select") {
    return <SeatSelectionPage
      event={eventResult.event}
      eventId={eventId}
      admissionExpiresAt={route.admissionExpiresAt}
      onRefresh={refreshSale}
    />;
  }
  if (route.view === "checkout") {
    return (
      <CheckoutPage
        event={eventResult.event}
        eventId={eventId}
        hold={route.hold}
        onRefresh={refreshSale}
        onCompleted={(orderNumber) => {
          navigate(`/orders/${orderNumber}`);
        }}
      />
    );
  }
  if (route.view === "degraded") {
    return <DegradedState onRetry={refreshSale} />;
  }

  if (route.view === "terminal") {
    return <TerminalPage reason={route.reason} />;
  }

  return <Typography>Opening your order…</Typography>;
}
