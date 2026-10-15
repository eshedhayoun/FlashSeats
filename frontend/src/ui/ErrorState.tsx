import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import ErrorOutlineRounded from "@mui/icons-material/ErrorOutlineRounded";
import WifiOffRounded from "@mui/icons-material/WifiOffRounded";
import { Link as RouterLink } from "react-router-dom";
import { NETWORK_ERROR, type ApiError } from "../api/errors";
import { problemCopy, supportReference } from "../copy/problemCopy";
import { PageTitle } from "./PageTitle";

/**
 * A view that could not render its content. Says what happened in the buyer's terms, offers the one
 * action that can help, and a way home. A server fault carries its reference, for support.
 */
export function ErrorState({
  error,
  onRetry,
  title,
  message,
  asPageTitle = true
}: {
  error?: ApiError | null;
  onRetry?: () => void;
  title?: string;
  message?: string;
  asPageTitle?: boolean;
}) {
  const copy = error ? problemCopy(error) : null;
  const heading = title ?? copy?.title ?? "Something went wrong";
  const body = message ?? copy?.message ?? "Please try again.";
  const reference = error ? supportReference(error) : null;
  const Icon = error?.code === NETWORK_ERROR ? WifiOffRounded : ErrorOutlineRounded;

  return (
    <Stack spacing={2} alignItems="flex-start" sx={{ py: { xs: 4, sm: 6 } }} data-testid="error-state">
      <Box sx={{ color: "error.main", display: "flex" }} aria-hidden>
        <Icon sx={{ fontSize: 44 }} />
      </Box>
      {asPageTitle ? (
        <PageTitle variant="h3">{heading}</PageTitle>
      ) : (
        <Typography variant="h3" component="h2">
          {heading}
        </Typography>
      )}
      <Typography color="text.secondary" sx={{ maxWidth: 560 }}>
        {body}
      </Typography>
      <Stack direction="row" spacing={1.5} sx={{ pt: 1 }}>
        {onRetry && (
          <Button variant="contained" onClick={onRetry}>
            Try again
          </Button>
        )}
        <Button component={RouterLink} to="/" variant={onRetry ? "text" : "contained"}>
          Back to all events
        </Button>
      </Stack>
      {reference && (
        <Typography variant="caption" color="text.secondary">
          Reference for support: <span className="tabular">{reference}</span>
        </Typography>
      )}
    </Stack>
  );
}
