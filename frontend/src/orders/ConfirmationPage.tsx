import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Divider from "@mui/material/Divider";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import CheckCircleRounded from "@mui/icons-material/CheckCircleRounded";
import DownloadRounded from "@mui/icons-material/DownloadRounded";
import MailOutlineRounded from "@mui/icons-material/MailOutlineRounded";
import ReceiptLongRounded from "@mui/icons-material/ReceiptLongRounded";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link as RouterLink, useParams, useSearchParams } from "react-router-dom";
import { downloadTicket, getEvent, getOrder } from "../api/endpoints";
import { asApiError, type ApiError } from "../api/errors";
import type { EventDetails, OrderReceipt } from "../api/types";
import { copyForCode, problemCopy } from "../copy/problemCopy";
import { formatMoney } from "../format/money";
import { EventFacts } from "../landing/EventHero";
import { rememberOrder } from "../sale/storage";
import { CopyButton } from "../ui/CopyButton";
import { ErrorState } from "../ui/ErrorState";
import { LoadingState } from "../ui/LoadingState";
import { Notice } from "../ui/Notice";
import { PageTitle } from "../ui/PageTitle";

const PENDING_POLL_MS = 2_000;
const TICKET_POLL_MS = 3_000;
const TICKET_POLL_LIMIT = 20;

type TicketState =
  | { kind: "idle" }
  | { kind: "working" }
  | { kind: "preparing" }
  | { kind: "done" }
  | { kind: "failed"; error: ApiError };

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/**
 * V5 — the order (FE_SPEC V5). Reached right after paying (session cookie) or from the email link
 * (`?receiptToken=`, a bearer capability that stays in this one URL and nowhere else). The ticket
 * download is not a convenience: it is the recovery path for a mistyped email address (ADR-050).
 */
export function ConfirmationPage() {
  const { orderNumber = "" } = useParams();
  const [params] = useSearchParams();
  const receiptToken = params.get("receiptToken") ?? undefined;

  const [receipt, setReceipt] = useState<OrderReceipt | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [event, setEvent] = useState<EventDetails | null>(null);
  const [ticket, setTicket] = useState<TicketState>({ kind: "idle" });
  const mounted = useRef(true);

  const load = useCallback(async () => {
    try {
      const next = await getOrder(orderNumber, receiptToken);
      if (!mounted.current) return;
      setReceipt(next);
      setError(null);
    } catch (cause) {
      if (mounted.current) setError(asApiError(cause, "The order could not be loaded."));
    }
  }, [orderNumber, receiptToken]);

  useEffect(() => {
    mounted.current = true;
    void load();
    return () => {
      mounted.current = false;
    };
  }, [load]);

  // A purchase still being completed — a webhook a moment behind the buyer — settles by itself.
  useEffect(() => {
    if (receipt?.status !== "PENDING") return;
    const timer = window.setInterval(() => void load(), PENDING_POLL_MS);
    return () => window.clearInterval(timer);
  }, [receipt?.status, load]);

  const eventId = receipt?.items[0]?.eventId;
  useEffect(() => {
    if (eventId == null) return;
    void getEvent(eventId)
      .then((details) => mounted.current && setEvent(details))
      .catch(() => undefined); // the order stands on its own without the event's details
  }, [eventId]);

  useEffect(() => {
    if (receipt?.status === "CONFIRMED") rememberOrder(receipt, event?.title ?? "FlashSeats event");
  }, [receipt, event?.title]);

  const download = async () => {
    setTicket({ kind: "working" });
    for (let attempt = 0; attempt <= TICKET_POLL_LIMIT; attempt++) {
      try {
        saveBlob(await downloadTicket(orderNumber, receiptToken), `${orderNumber}.pdf`);
        if (mounted.current) setTicket({ kind: "done" });
        return;
      } catch (cause) {
        const failure = asApiError(cause, "The ticket couldn't be downloaded. Please try again.");
        // Retryable means the order is still completing: wait for it rather than send the buyer away.
        if (failure.code === "TICKET_NOT_AVAILABLE" && failure.retryable && attempt < TICKET_POLL_LIMIT) {
          if (mounted.current) setTicket({ kind: "preparing" });
          await new Promise((resolve) => window.setTimeout(resolve, TICKET_POLL_MS));
          if (!mounted.current) return;
          continue;
        }
        if (mounted.current) setTicket({ kind: "failed", error: failure });
        return;
      }
    }
  };

  if (!receipt) {
    return (
      <Container maxWidth="sm">
        {error ? <ErrorState error={error} onRetry={() => void load()} /> : <LoadingState label="Loading your order" />}
      </Container>
    );
  }

  const confirmed = receipt.status === "CONFIRMED";
  const canBuyMore = confirmed && eventId != null && (event?.windowStatus === "OPEN" || event?.windowStatus === "PAUSED");

  return (
    <Container maxWidth="sm" sx={{ py: { xs: 3, sm: 5 } }}>
      <Stack spacing={3}>
        <StatusHeader receipt={receipt} eventTitle={event?.title} />

        <Card>
          <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
            <Stack spacing={2}>
              <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2}>
                <Box>
                  <Typography variant="body2" color="text.secondary">
                    Order number
                  </Typography>
                  <Typography
                    variant="h4"
                    component="p"
                    sx={{ fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", letterSpacing: "0.02em" }}
                    data-testid="order-number"
                  >
                    {receipt.orderNumber}
                  </Typography>
                </Box>
                <CopyButton value={receipt.orderNumber} label="order number" />
              </Stack>
              {event && (
                <>
                  <Divider />
                  <Box>
                    <Typography fontWeight={700} gutterBottom dir="auto">
                      {event.title}
                    </Typography>
                    <EventFacts event={event} muted />
                  </Box>
                </>
              )}
              <Divider />
              <Stack spacing={1}>
                {receipt.items.map((item) => (
                  <Stack key={`${item.eventId}-${item.tierId}`} direction="row" justifyContent="space-between" spacing={2}>
                    <Typography>
                      {item.tierName} × {item.quantity}
                    </Typography>
                    <Typography className="tabular">
                      {formatMoney(item.unitPriceCents * item.quantity, receipt.currency)}
                    </Typography>
                  </Stack>
                ))}
                <Stack direction="row" justifyContent="space-between" alignItems="baseline" sx={{ pt: 1 }}>
                  <Typography fontWeight={700}>{confirmed ? "Total paid" : "Total"}</Typography>
                  <Typography variant="h5" component="p" className="tabular" data-testid="order-total">
                    {formatMoney(receipt.totalAmountCents, receipt.currency)}
                  </Typography>
                </Stack>
              </Stack>
            </Stack>
          </CardContent>
        </Card>

        {confirmed && (
          <>
            <Notice severity="success" title="Your tickets are on their way">
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
                <MailOutlineRounded fontSize="small" aria-hidden />
                <span>
                  We're sending them to <strong data-testid="order-email">{receipt.userEmail}</strong>.
                </span>
              </Stack>
              They usually arrive within a minute. Not the right address? Download your ticket below — it's the same
              ticket.
            </Notice>

            <Stack spacing={1.5}>
              <Button
                variant="contained"
                size="large"
                onClick={() => void download()}
                disabled={ticket.kind === "working" || ticket.kind === "preparing"}
                startIcon={
                  ticket.kind === "working" || ticket.kind === "preparing" ? (
                    <CircularProgress size={18} color="inherit" />
                  ) : (
                    <DownloadRounded />
                  )
                }
                data-testid="download-ticket"
              >
                {ticket.kind === "preparing" ? "Preparing your ticket…" : "Download ticket (PDF)"}
              </Button>
              {ticket.kind === "done" && (
                <Typography color="success.main" role="status">
                  Your ticket has downloaded.
                </Typography>
              )}
              {ticket.kind === "failed" && (
                <Notice severity="error" title={problemCopy(ticket.error).title}>
                  {problemCopy(ticket.error).message}
                </Notice>
              )}
            </Stack>
          </>
        )}

        <Stack direction="row" spacing={1.5} useFlexGap flexWrap="wrap">
          {canBuyMore && (
            <Button component={RouterLink} to={`/events/${eventId}?buyMore=1`} variant="outlined">
              Buy more tickets for this event
            </Button>
          )}
          <Button component={RouterLink} to="/" variant="text">
            Back to all events
          </Button>
        </Stack>
      </Stack>
    </Container>
  );
}

function StatusHeader({ receipt, eventTitle }: { receipt: OrderReceipt; eventTitle?: string }) {
  switch (receipt.status) {
    case "CONFIRMED":
      return (
        <Stack spacing={1.5} alignItems="flex-start">
          <CheckCircleRounded sx={{ fontSize: 52, color: "success.main" }} aria-hidden />
          <PageTitle variant="h2">{eventTitle ? `You're going to ${eventTitle}!` : "Your purchase is confirmed"}</PageTitle>
        </Stack>
      );
    case "PENDING":
      return (
        <Stack spacing={1.5} alignItems="flex-start">
          <CircularProgress size={40} aria-hidden />
          <PageTitle variant="h2">We're finishing your purchase</PageTitle>
          <Typography color="text.secondary">
            This usually takes a few seconds, and this page updates by itself. Please don't pay again.
          </Typography>
        </Stack>
      );
    case "REFUNDED":
    case "REFUND_FAILED": {
      const copy = copyForCode(receipt.status === "REFUNDED" ? "ORDER_REFUNDED" : "REFUND_FAILED");
      return (
        <Stack spacing={1.5} alignItems="flex-start">
          <ReceiptLongRounded sx={{ fontSize: 48, color: "warning.main" }} aria-hidden />
          <PageTitle variant="h2">{copy.title}</PageTitle>
          <Typography color="text.secondary">{copy.message}</Typography>
        </Stack>
      );
    }
    default:
      return (
        <Stack spacing={1.5} alignItems="flex-start">
          <ReceiptLongRounded sx={{ fontSize: 48, color: "text.secondary" }} aria-hidden />
          <PageTitle variant="h2">This order wasn't completed</PageTitle>
          <Typography color="text.secondary">No ticket was issued for it.</Typography>
        </Stack>
      );
  }
}
