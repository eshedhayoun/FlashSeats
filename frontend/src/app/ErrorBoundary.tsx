import Button from "@mui/material/Button";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { Component, type ErrorInfo, type ReactNode } from "react";

type State = { failed: boolean };

/**
 * A render failure becomes a page that says so and offers a reload, rather than a white screen. A
 * reload is safe at every point of the journey: the client rehydrates from the server (FE_SPEC §0
 * rule 3), and nothing the buyer has is held only in this page.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Unhandled render error", error, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <Container maxWidth="sm" sx={{ py: 8 }}>
        <Stack spacing={2} alignItems="flex-start">
          <Typography component="h1" variant="h3">
            Something went wrong on this page
          </Typography>
          <Typography color="text.secondary">
            Reloading is safe: your place in line, your seats and any payment are kept on our side.
          </Typography>
          <Button variant="contained" onClick={() => window.location.reload()}>
            Reload the page
          </Button>
        </Stack>
      </Container>
    );
  }
}
