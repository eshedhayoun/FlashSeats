import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

export type ConnectionState = "connecting" | "open" | "reconnecting" | "polling";

const COPY: Record<ConnectionState, { label: string; tone: "success.main" | "warning.main" | "text.disabled" }> = {
  connecting: { label: "Connecting…", tone: "text.disabled" },
  open: { label: "Connected — updates are live", tone: "success.main" },
  // Never "disconnected": the place lives on the server, keyed on the session, and the line never
  // evicts on a missing heartbeat (ADR-026). The buyer should not have to care which transport is live.
  reconnecting: { label: "Reconnecting — your place is saved", tone: "warning.main" },
  polling: { label: "Reconnecting — your place is saved", tone: "warning.main" }
};

export function ConnectionIndicator({ state }: { state: ConnectionState }) {
  const copy = COPY[state];
  return (
    <Stack direction="row" spacing={1} alignItems="center" role="status" data-testid="connection" data-state={state}>
      <Box
        aria-hidden
        sx={{ width: 10, height: 10, borderRadius: "50%", bgcolor: copy.tone, flexShrink: 0 }}
      />
      <Typography variant="body2" color="text.secondary">
        {copy.label}
      </Typography>
    </Stack>
  );
}
