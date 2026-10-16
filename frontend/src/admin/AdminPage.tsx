import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import RefreshRounded from "@mui/icons-material/RefreshRounded";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { asApiError, type ApiError } from "../api/errors";
import { formatDateTime } from "../format/dates";
import { ConfirmDialog } from "../ui/ConfirmDialog";
import { LoadingState } from "../ui/LoadingState";
import { Notice } from "../ui/Notice";
import { PageTitle } from "../ui/PageTitle";
import {
  createEvent,
  listEvents,
  runAction,
  type AdminEvent,
  type CreateEventRequest,
  type Credentials,
  type EventAction
} from "./adminApi";
import { CreateEventDialog } from "./CreateEventDialog";
import { ACTION_LABELS, EventCard } from "./EventCard";

const POLL_MS = 5_000;

const isAuthFailure = (error: ApiError) => error.status === 401 || error.status === 403;

/**
 * The operator console (FE_SPEC §11). Credentials live in this component's state and nowhere else:
 * no storage, no cookie, so a reload or a closed tab signs out (ADR-080).
 */
export default function AdminPage() {
  const [credentials, setCredentials] = useState<Credentials | null>(null);
  const [signInMessage, setSignInMessage] = useState<string | null>(null);
  // Stable, so the console's polling effect is not torn down and restarted on every render.
  const signOut = useCallback((message: string | null) => {
    setCredentials(null);
    setSignInMessage(message);
  }, []);

  if (!credentials) {
    return (
      <SignIn
        message={signInMessage}
        onSignedIn={(signedIn) => {
          setSignInMessage(null);
          setCredentials(signedIn);
        }}
      />
    );
  }
  return (
    <Console credentials={credentials} onSignOut={signOut} />
  );
}

function SignIn({ message, onSignedIn }: { message: string | null; onSignedIn: (credentials: Credentials) => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const credentials = { username, password };
    try {
      await listEvents(credentials);
      onSignedIn(credentials);
    } catch (cause) {
      const failure = asApiError(cause);
      setError(isAuthFailure(failure) ? "Wrong username or password." : failure.problem.detail);
      setBusy(false);
    }
  }

  return (
    <Container maxWidth="xs" sx={{ py: 6 }}>
      <Paper variant="outlined" sx={{ p: 3 }}>
        <form onSubmit={submit}>
          <Stack spacing={2}>
            <PageTitle variant="h5">Operator sign-in</PageTitle>
            {message && <Notice severity="info">{message}</Notice>}
            <TextField
              label="Username"
              autoComplete="username"
              required
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
            <TextField
              label="Password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            {error && <Notice severity="error">{error}</Notice>}
            <Button
              type="submit"
              variant="contained"
              disabled={busy || !username || !password}
              startIcon={busy ? <CircularProgress size={16} color="inherit" /> : undefined}
            >
              Sign in
            </Button>
          </Stack>
        </form>
      </Paper>
    </Container>
  );
}

type Flash = { severity: "success" | "warning" | "error"; text: string };

function Console({ credentials, onSignOut }: { credentials: Credentials; onSignOut: (message: string | null) => void }) {
  const [events, setEvents] = useState<AdminEvent[] | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [busy, setBusy] = useState<{ eventId: number; action: EventAction } | null>(null);
  const [confirm, setConfirm] = useState<{ event: AdminEvent; action: "rebuild" | "delete" } | null>(null);
  const [flash, setFlash] = useState<Flash | null>(null);
  const [creating, setCreating] = useState(false);
  const latest = useRef(0);

  /** Every call goes through here, so expired or changed credentials end the session wherever they surface. */
  const call = useCallback(
    async <T,>(request: (credentials: Credentials) => Promise<T>): Promise<T> => {
      try {
        return await request(credentials);
      } catch (cause) {
        const failure = asApiError(cause);
        if (isAuthFailure(failure)) onSignOut("Your session ended. Sign in again.");
        throw failure;
      }
    },
    [credentials, onSignOut]
  );

  /**
   * Only the newest refresh may write. A poll that started before an action can answer after it, and
   * showing that answer would put back a sale the operator just deleted.
   */
  const refresh = useCallback(async () => {
    const ticket = ++latest.current;
    try {
      const list = await call(listEvents);
      if (ticket !== latest.current) return;
      setEvents(list);
      setUpdatedAt(new Date());
      setLoadError(null);
    } catch (cause) {
      // The last list stays on screen; the error says it is stale.
      if (ticket === latest.current) setLoadError(asApiError(cause));
    }
  }, [call]);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => {
      if (!document.hidden) void refresh();
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [refresh]);

  async function perform(event: AdminEvent, action: EventAction) {
    setBusy({ eventId: event.eventId, action });
    setFlash(null);
    try {
      await call((c) => runAction(c, event.eventId, action));
      setFlash({ severity: "success", text: `${ACTION_LABELS[action]}: “${event.title}” done.` });
    } catch (cause) {
      setFlash({ severity: "error", text: asApiError(cause).problem.detail });
    } finally {
      setBusy(null);
      setConfirm(null);
      await refresh();
    }
  }

  async function create(request: CreateEventRequest) {
    const created = await call((c) => createEvent(c, request));
    setFlash(
      created.prewarmed
        ? { severity: "success", text: `Created “${created.title}”. It opens ${formatDateTime(created.saleStartTime)}.` }
        : { severity: "warning", text: `Created “${created.title}”, but it is not pre-warmed yet. Pre-warm it before it opens.` }
    );
    await refresh();
  }

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Stack spacing={3}>
        <Stack direction={{ xs: "column", sm: "row" }} spacing={2} sx={{ justifyContent: "space-between" }}>
          <div>
            <PageTitle variant="h4">Operator console</PageTitle>
            <Typography variant="body2" color="text.secondary">
              Signed in as {credentials.username}
              {updatedAt && ` · updated ${updatedAt.toLocaleTimeString()}`}
            </Typography>
          </div>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Button startIcon={<RefreshRounded />} onClick={() => void refresh()}>
              Refresh
            </Button>
            <Button onClick={() => onSignOut(null)}>Sign out</Button>
            <Button variant="contained" startIcon={<AddRounded />} onClick={() => setCreating(true)}>
              New sale
            </Button>
          </Stack>
        </Stack>

        {flash && (
          <Notice severity={flash.severity} onClose={() => setFlash(null)}>
            {flash.text}
          </Notice>
        )}
        {loadError && (
          <Notice severity="error" title="Could not refresh the list">
            {loadError.problem.detail}
          </Notice>
        )}

        {events === null ? (
          loadError ? null : <LoadingState label="Loading sales" shape="list" />
        ) : events.length === 0 ? (
          <Typography color="text.secondary">No sales yet. Create one with “New sale”.</Typography>
        ) : (
          events.map((event) => (
            <EventCard
              key={event.eventId}
              event={event}
              busyAction={busy?.eventId === event.eventId ? busy.action : null}
              onAction={(action) =>
                action === "rebuild" || action === "delete" ? setConfirm({ event, action }) : void perform(event, action)
              }
            />
          ))
        )}
      </Stack>

      <CreateEventDialog open={creating} onClose={() => setCreating(false)} onSubmit={create} />

      <ConfirmDialog
        open={confirm !== null}
        title={confirm?.action === "delete" ? "Delete this sale?" : "Rebuild stock?"}
        confirmLabel={confirm?.action === "delete" ? "Delete sale" : "Rebuild stock"}
        cancelLabel="Cancel"
        destructive={confirm?.action === "delete"}
        busy={busy !== null}
        onCancel={() => setConfirm(null)}
        onConfirm={() => confirm && void perform(confirm.event, confirm.action)}
      >
        {confirm?.action === "delete"
          ? `“${confirm.event.title}” and its tiers are removed. This is possible only until a minute before the sale opens.`
          : `Recounts every tier of “${confirm?.event.title ?? ""}” from confirmed orders and live holds. Use it after a Redis restart, or when the console says the stock is not trusted.`}
      </ConfirmDialog>
    </Container>
  );
}
