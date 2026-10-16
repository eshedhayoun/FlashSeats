import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import EventBusyRounded from "@mui/icons-material/EventBusyRounded";
import RefreshRounded from "@mui/icons-material/RefreshRounded";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { asApiError, type ApiError } from "../api/errors";
import { formatDateTime } from "../format/dates";
import { ConfirmDialog } from "../ui/ConfirmDialog";
import { EmptyState } from "../ui/EmptyState";
import { Notice } from "../ui/Notice";
import { PageTitle } from "../ui/PageTitle";
import {
  ACTION_LABELS,
  createEvent,
  listEvents,
  runAction,
  type AdminEvent,
  type CreateEventRequest,
  type Credentials,
  type EventAction
} from "./adminApi";
import { CreateEventDialog } from "./CreateEventDialog";
import { EventCard } from "./EventCard";

const POLL_MS = 5_000;

const isAuthFailure = (error: ApiError) => error.status === 401 || error.status === 403;

/** Sign-in checks the credentials by loading the list, and the console starts from that list. */
type Session = { credentials: Credentials; events: AdminEvent[] };

const CONFIRM = {
  delete: {
    title: "Delete this sale?",
    label: "Delete sale",
    body: (title: string) =>
      `“${title}” and its tiers are removed. This is possible only until a minute before the sale opens.`
  },
  rebuild: {
    title: "Rebuild stock?",
    label: "Rebuild stock",
    body: (title: string) =>
      `Recounts every tier of “${title}” from confirmed orders and live holds. Use it after a Redis restart, or when the console says the stock is not trusted.`
  }
} as const;

/**
 * The operator console (FE_SPEC §11). Credentials live in this component's state and nowhere else:
 * no storage, no cookie, so a reload or a closed tab signs out (ADR-080).
 */
export default function AdminPage() {
  const [session, setSession] = useState<Session | null>(null);
  const [signInMessage, setSignInMessage] = useState<string | null>(null);
  // Stable, so the console's polling effect is not torn down and restarted on every render.
  const signOut = useCallback((message: string | null) => {
    setSession(null);
    setSignInMessage(message);
  }, []);

  if (!session) {
    return (
      <SignIn
        message={signInMessage}
        onSignedIn={(signedIn) => {
          setSignInMessage(null);
          setSession(signedIn);
        }}
      />
    );
  }
  return <Console session={session} onSignOut={signOut} />;
}

function SignIn({ message, onSignedIn }: { message: string | null; onSignedIn: (session: Session) => void }) {
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
      onSignedIn({ credentials, events: await listEvents(credentials) });
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

function Console({ session, onSignOut }: { session: Session; onSignOut: (message: string | null) => void }) {
  const { credentials } = session;
  const [events, setEvents] = useState<AdminEvent[]>(session.events);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [updatedAt, setUpdatedAt] = useState(() => new Date());
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

  // The next poll is scheduled once this one settles, so a slow backend gets one request at a time
  // rather than one every interval. The first list came with the sign-in.
  useEffect(() => {
    let timer: number | undefined;
    let stopped = false;
    const tick = async () => {
      if (!document.hidden) await refresh();
      if (!stopped) timer = window.setTimeout(tick, POLL_MS);
    };
    timer = window.setTimeout(tick, POLL_MS);
    return () => {
      stopped = true;
      window.clearTimeout(timer);
    };
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
              {` · updated ${updatedAt.toLocaleTimeString()}`}
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

        {events.length === 0 ? (
          <EmptyState icon={<EventBusyRounded sx={{ fontSize: 40 }} />} title="No sales yet">
            Create one with “New sale”.
          </EmptyState>
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

      {confirm && (
        <ConfirmDialog
          open
          title={CONFIRM[confirm.action].title}
          confirmLabel={CONFIRM[confirm.action].label}
          cancelLabel="Cancel"
          destructive={confirm.action === "delete"}
          busy={busy !== null}
          onCancel={() => setConfirm(null)}
          onConfirm={() => void perform(confirm.event, confirm.action)}
        >
          {CONFIRM[confirm.action].body(confirm.event.title)}
        </ConfirmDialog>
      )}
    </Container>
  );
}
