import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Divider from "@mui/material/Divider";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { downloadTicket, getOrder } from "../api/endpoints";
import { ApiError } from "../api/errors";
import type { OrderReceipt } from "../api/types";
import { rememberOrder } from "../sale/storage";

export function ConfirmationPage() {
  const { orderNumber = "" } = useParams();
  const [searchParams] = useSearchParams();
  const receiptToken = searchParams.get("receiptToken") ?? undefined;
  const [receipt, setReceipt] = useState<OrderReceipt | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    void getOrder(orderNumber, receiptToken)
      .then((nextReceipt) => {
        if (!active) return;
        setReceipt(nextReceipt);
        rememberOrder(nextReceipt, "FlashSeats event");
      })
      .catch((cause: unknown) => {
        if (!active) return;
        setError(
          cause instanceof ApiError
            ? cause
            : new ApiError({
                type: "about:blank",
                title: "Order unavailable",
                status: 0,
                detail: "The order could not be loaded.",
                code: "CLIENT_ERROR"
              })
        );
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [orderNumber, receiptToken]);

  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      const blob = await downloadTicket(orderNumber, receiptToken);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${orderNumber}.pdf`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === "TICKET_NOT_AVAILABLE") {
        setDownloadError(
          cause.retryable
            ? "Your ticket is still being prepared. Try again in a moment."
            : "Your ticket could not be generated. Please contact support."
        );
      } else if (cause instanceof ApiError) {
        setDownloadError(cause.message);
      } else {
        setDownloadError("The ticket could not be downloaded. Try again.");
      }
    } finally {
      setDownloading(false);
    }
  };

  if (loading) {
    return (
      <Container sx={{ py: 8 }}>
        <CircularProgress aria-label="Loading order" />
      </Container>
    );
  }

  if (error || !receipt) {
    return (
      <Container sx={{ py: 8 }}>
        <Alert severity="error">{error?.message ?? "Order unavailable."}</Alert>
      </Container>
    );
  }

  return (
    <Container maxWidth="sm" sx={{ py: 6 }}>
      <Stack spacing={3}>
        <Stack spacing={1}>
          <Typography component="h1" variant="h4">
            Purchase confirmed
          </Typography>
          <Typography color="text.secondary">
            Your order number is
          </Typography>
          <Typography
            variant="h5"
            sx={{ fontFamily: "monospace" }}
            aria-label={`Order number ${receipt.orderNumber}`}
          >
            {receipt.orderNumber}
          </Typography>
        </Stack>
        <Card>
          <CardContent>
            <Stack spacing={2}>
              {receipt.items.map((item) => (
                <Stack
                  key={`${item.eventId}-${item.tierId}`}
                  direction="row"
                  justifyContent="space-between"
                >
                  <Typography>
                    {item.quantity} × {item.tierName}
                  </Typography>
                  <Typography>
                    {(
                      (item.unitPriceCents * item.quantity) /
                      100
                    ).toFixed(2)}{" "}
                    {receipt.currency}
                  </Typography>
                </Stack>
              ))}
              <Divider />
              <Typography fontWeight={700}>
                Total: {(receipt.totalAmountCents / 100).toFixed(2)}{" "}
                {receipt.currency}
              </Typography>
            </Stack>
          </CardContent>
        </Card>
        <Alert severity="success">
          We&apos;re sending your tickets to <strong>{receipt.userEmail}</strong>.
          They usually arrive within a minute.
        </Alert>
        {downloadError && <Alert severity="info">{downloadError}</Alert>}
        <Button
          variant="contained"
          size="large"
          disabled={downloading}
          onClick={() => void download()}
        >
          {downloading ? (
            <CircularProgress size={22} color="inherit" />
          ) : (
            "Download ticket"
          )}
        </Button>
      </Stack>
    </Container>
  );
}
