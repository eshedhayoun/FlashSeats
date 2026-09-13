import Button from "@mui/material/Button";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { useNavigate } from "react-router-dom";
import type { TerminalReason } from "../sale/routeFor";

export function TerminalPage({ reason }: { reason: TerminalReason }) {
  const navigate = useNavigate();
  const soldOut = reason === "SOLD_OUT";

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Stack spacing={2} alignItems="flex-start">
        <Typography component="h1" variant="h4">
          {soldOut ? "This event has sold out." : "Sales have ended."}
        </Typography>
        <Typography color="text.secondary">
          {soldOut
            ? "All tickets currently available for this event have been sold."
            : "This sale is no longer accepting reservations."}
        </Typography>
        <Button variant="outlined" onClick={() => navigate("/")}>
          Browse other events
        </Button>
      </Stack>
    </Container>
  );
}
