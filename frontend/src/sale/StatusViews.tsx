import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import CelebrationRounded from "@mui/icons-material/CelebrationRounded";
import { useEffect } from "react";
import { PageTitle } from "../ui/PageTitle";

const DEGRADED_POLL_MS = 5_000;

/**
 * The server could not read part of the sale (`partial`). Absent and unreadable are different facts,
 * so this never guesses that the place or the seats are gone (FE_SPEC §3) — it keeps asking.
 */
export function DegradedView({ onRefresh }: { onRefresh: () => Promise<void> }) {
  useEffect(() => {
    const timer = window.setInterval(() => void onRefresh(), DEGRADED_POLL_MS);
    return () => window.clearInterval(timer);
  }, [onRefresh]);

  return (
    <Container maxWidth="sm" sx={{ py: { xs: 5, sm: 8 } }}>
      <Stack spacing={2} alignItems="flex-start" data-testid="degraded">
        <CircularProgress size={32} aria-hidden />
        <PageTitle variant="h3">We're checking where you are in the sale</PageTitle>
        <Typography color="text.secondary">
          Part of the sale's information is taking a moment to load. Nothing has been lost — your place in
          line and any seats you're holding are kept on our side. This page will carry on by itself.
        </Typography>
        <Button variant="outlined" onClick={() => void onRefresh()}>
          Check now
        </Button>
      </Stack>
    </Container>
  );
}

/** Shown for at least 600 ms while a turn is exchanged for entry: an instant flip feels like an error. */
export function TurnView() {
  return (
    <Container maxWidth="sm" sx={{ py: { xs: 6, sm: 10 } }}>
      <Stack spacing={2.5} alignItems="center" textAlign="center" data-testid="your-turn">
        <CelebrationRounded sx={{ fontSize: 56, color: "primary.main" }} aria-hidden />
        <PageTitle variant="h2">It's your turn!</PageTitle>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <CircularProgress size={20} aria-hidden />
          <Typography color="text.secondary">Taking you to choose your seats…</Typography>
        </Stack>
      </Stack>
    </Container>
  );
}

export function OpeningOrderView() {
  return (
    <Container maxWidth="sm" sx={{ py: { xs: 6, sm: 10 } }}>
      <Stack spacing={2} alignItems="center" role="status">
        <CircularProgress aria-hidden />
        <Typography color="text.secondary">Opening your order…</Typography>
      </Stack>
    </Container>
  );
}
