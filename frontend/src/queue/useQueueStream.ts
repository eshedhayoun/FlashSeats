import { useEffect, useRef, useState } from "react";
import { getQueueStatus } from "../api/endpoints";
import type { Availability, PositionUpdateEvent, QueuePromotedEvent, TierAvailabilityEvent } from "../api/types";
import { getLastEventId, setLastEventId } from "../sale/storage";
import type { ConnectionState } from "../ui/ConnectionIndicator";

export type QueueStreamState = {
  connection: ConnectionState;
  /** The latest position heard, clamped so it never rises (FE_SPEC V2). */
  position: number | null;
  estWaitSeconds: number | null;
  /** Per-tier availability heard since connecting, by tier id. Seed from the event, update from this. */
  availability: Record<number, Availability>;
  paused: boolean;
};

/** Failed connection attempts before polling `/queue/status` starts alongside the retries (FE_SPEC §4). */
const POLL_AFTER_FAILURES = 3;
const POLL_INTERVAL_MS = 5_000;

function parse<T>(event: Event): T | null {
  try {
    return JSON.parse((event as MessageEvent).data) as T;
  } catch {
    return null;
  }
}

/**
 * One `EventSource` for one sale, closed when the view unmounts (FE_SPEC §0 rule 5, §4).
 *
 * Anything that changes which view the buyer belongs on — a promotion, the sale selling out or ending —
 * is answered by `onRefresh`, so the view is always the server's verdict and never this hook's guess.
 * Reconnects use full-jitter backoff; after three failures `/queue/status` is polled while the
 * stream keeps retrying, so a promotion is never lost to a dead socket (ADR-007).
 */
export function useQueueStream(
  eventId: number,
  onRefresh: () => void,
  initialPaused: boolean
): QueueStreamState {
  const [state, setState] = useState<QueueStreamState>({
    connection: "connecting",
    position: null,
    estWaitSeconds: null,
    availability: {},
    paused: initialPaused
  });
  const refresh = useRef(onRefresh);
  refresh.current = onRefresh;

  useEffect(() => {
    let disposed = false;
    let stream: EventSource | null = null;
    let failures = 0;
    let reconnectTimer: number | null = null;
    let pollTimer: number | null = null;

    const clampPosition = (current: number | null, incoming: number | null) =>
      incoming === null ? current : current === null ? incoming : Math.min(current, incoming);

    const stopPolling = () => {
      if (pollTimer !== null) {
        window.clearInterval(pollTimer);
        pollTimer = null;
      }
    };

    const startPolling = () => {
      if (pollTimer !== null) return;
      setState((current) => ({ ...current, connection: "polling" }));
      pollTimer = window.setInterval(() => {
        void getQueueStatus(eventId)
          .then((status) => {
            if (disposed) return;
            setState((current) => ({
              ...current,
              position: clampPosition(current.position, status.position),
              estWaitSeconds: status.estWaitSeconds,
              paused: status.paused
            }));
            if (status.phase !== "WAITING") refresh.current();
          })
          .catch(() => undefined);
      }, POLL_INTERVAL_MS);
    };

    const remember = (event: Event) => {
      // Only replayable frames carry an id; the rest leave the browser's last-event-id alone (ADR-058).
      const id = (event as MessageEvent).lastEventId;
      if (id) setLastEventId(eventId, id);
    };

    const scheduleReconnect = () => {
      if (disposed || reconnectTimer !== null) return;
      failures += 1;
      if (failures >= POLL_AFTER_FAILURES) startPolling();
      // Full jitter: ten thousand clients reconnecting in step after a blip is a self-inflicted DDoS.
      const delay = Math.random() * Math.min(30_000, 1_000 * 2 ** failures);
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, delay);
    };

    const connect = () => {
      if (disposed) return;
      stream?.close();
      const lastEventId = getLastEventId(eventId);
      const replay = lastEventId ? `&lastEventId=${encodeURIComponent(lastEventId)}` : "";
      stream = new EventSource(`/api/v1/queue/stream?eventId=${eventId}${replay}`, { withCredentials: true });

      stream.onopen = () => {
        failures = 0;
        stopPolling();
        setState((current) => ({ ...current, connection: "open" }));
      };
      stream.onerror = () => {
        stream?.close();
        setState((current) => ({
          ...current,
          connection: current.connection === "polling" ? "polling" : "reconnecting"
        }));
        scheduleReconnect();
      };

      stream.addEventListener("position-update", (event) => {
        remember(event);
        const update = parse<PositionUpdateEvent>(event);
        if (!update) return;
        setState((current) => ({
          ...current,
          position: clampPosition(current.position, update.position),
          estWaitSeconds: update.estWaitSeconds,
          // Positions only move while the sale runs, so one is also a resume (FE_SPEC V6).
          paused: false
        }));
      });
      // A pause is not an ending: the stream stays open and the place is kept (ADR-066).
      stream.addEventListener("sale-paused", () => setState((current) => ({ ...current, paused: true })));
      stream.addEventListener("sale-resumed", () => setState((current) => ({ ...current, paused: false })));
      stream.addEventListener("tier-availability", (event) => {
        remember(event);
        const frame = parse<TierAvailabilityEvent>(event);
        if (!frame) return;
        setState((current) => ({
          ...current,
          availability: {
            ...current.availability,
            ...Object.fromEntries(frame.tiers.map((tier) => [tier.tierId, tier.level]))
          }
        }));
      });
      const routeChanging = (event: Event) => {
        remember(event);
        stream?.close();
        refresh.current();
      };
      stream.addEventListener("queue-promoted", (event) => {
        const promoted = parse<QueuePromotedEvent>(event);
        if (promoted) routeChanging(event);
      });
      stream.addEventListener("sale-exhausted", routeChanging);
      stream.addEventListener("sale-closed", routeChanging);
    };

    const reconnectNow = () => {
      if (reconnectTimer !== null) {
        window.clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      failures = 0;
      connect();
    };

    // The previous backoff measured a dead network, not a busy server (FE_SPEC §4).
    const onOnline = () => reconnectNow();
    const onOffline = () => setState((current) => ({ ...current, connection: "reconnecting" }));
    const onVisible = () => {
      if (document.visibilityState === "visible" && stream?.readyState === EventSource.CLOSED) reconnectNow();
    };

    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    document.addEventListener("visibilitychange", onVisible);
    connect();

    return () => {
      disposed = true;
      stream?.close();
      stopPolling();
      if (reconnectTimer !== null) window.clearTimeout(reconnectTimer);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [eventId]);

  return state;
}
