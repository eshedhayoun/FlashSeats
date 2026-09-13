import { useEffect, useRef, useState } from "react";
import { getQueueStatus } from "../api/endpoints";
import type {
  PositionUpdateEvent,
  QueuePromotedEvent,
  SaleClosedEvent,
  SaleExhaustedEvent,
  SaleQueueState,
  TierAvailabilityEvent
} from "../api/types";

type ConnectionState = "connecting" | "open" | "reconnecting";

export type QueueStreamState = {
  connection: ConnectionState;
  position: number | null;
  aheadOfYou: number | null;
  estWaitSeconds: number | null;
  promoted: QueuePromotedEvent | null;
  availability: TierAvailabilityEvent | null;
  terminal: SaleClosedEvent | SaleExhaustedEvent | null;
};

const initialState: QueueStreamState = {
  connection: "connecting",
  position: null,
  aheadOfYou: null,
  estWaitSeconds: null,
  promoted: null,
  availability: null,
  terminal: null
};

export function useQueueStream(eventId: number, onRefresh: () => void) {
  const [state, setState] = useState<QueueStreamState>(initialState);
  const attempt = useRef(0);
  const reconnectTimer = useRef<number | null>(null);
  const pollingTimer = useRef<number | null>(null);

  useEffect(() => {
    let disposed = false;
    let stream: EventSource | null = null;

    const clearPolling = () => {
      if (pollingTimer.current !== null) {
        window.clearInterval(pollingTimer.current);
        pollingTimer.current = null;
      }
    };

    const startPolling = () => {
      if (pollingTimer.current !== null) return;
      pollingTimer.current = window.setInterval(() => {
        void getQueueStatus(eventId)
          .then((status) => {
            if (disposed) return;
            setState((current) => ({
              ...current,
              position:
                status.position === null
                  ? current.position
                  : current.position === null
                    ? status.position
                    : Math.min(current.position, status.position),
              aheadOfYou: status.aheadOfYou,
              estWaitSeconds: status.estWaitSeconds,
              promoted: status.passToken
                ? { passToken: status.passToken, expiresInSeconds: 0 }
                : current.promoted
            }));
            if (status.passToken) onRefresh();
          })
          .catch(() => undefined);
      }, 5000);
    };

    const connect = () => {
      if (disposed) return;
      setState((current) => ({ ...current, connection: "connecting" }));
      stream = new EventSource(`/api/v1/queue/stream?eventId=${eventId}`, {
        withCredentials: true
      });

      stream.onopen = () => {
        attempt.current = 0;
        clearPolling();
        setState((current) => ({ ...current, connection: "open" }));
      };
      stream.onerror = () => {
        stream?.close();
        setState((current) => ({ ...current, connection: "reconnecting" }));
        if (attempt.current >= 2) startPolling();
        const delay = Math.random() * Math.min(30_000, 1_000 * 2 ** attempt.current++);
        reconnectTimer.current = window.setTimeout(() => {
          reconnectTimer.current = null;
          connect();
        }, delay);
      };
      stream.addEventListener("position-update", (event) => {
        const update = JSON.parse((event as MessageEvent).data) as PositionUpdateEvent;
        setState((current) => ({
          ...current,
          position:
            current.position === null
              ? update.position
              : Math.min(current.position, update.position),
          aheadOfYou: update.aheadOfYou,
          estWaitSeconds: update.estWaitSeconds
        }));
      });
      stream.addEventListener("queue-promoted", (event) => {
        const promoted = JSON.parse((event as MessageEvent).data) as QueuePromotedEvent;
        setState((current) => ({ ...current, promoted }));
        stream?.close();
        onRefresh();
      });
      stream.addEventListener("tier-availability", (event) => {
        const availability = JSON.parse((event as MessageEvent).data) as TierAvailabilityEvent;
        setState((current) => ({ ...current, availability }));
      });
      stream.addEventListener("sale-exhausted", (event) => {
        const terminal = JSON.parse((event as MessageEvent).data) as SaleExhaustedEvent;
        setState((current) => ({ ...current, terminal }));
        stream?.close();
        onRefresh();
      });
      stream.addEventListener("sale-closed", (event) => {
        const terminal = JSON.parse((event as MessageEvent).data) as SaleClosedEvent;
        setState((current) => ({ ...current, terminal }));
        stream?.close();
        onRefresh();
      });
    };

    const onOnline = () => {
      attempt.current = 0;
      onRefresh();
      stream?.close();
      connect();
    };
    const onOffline = () =>
      setState((current) => ({ ...current, connection: "reconnecting" }));

    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    connect();

    return () => {
      disposed = true;
      stream?.close();
      clearPolling();
      if (reconnectTimer.current !== null) {
        window.clearTimeout(reconnectTimer.current);
      }
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
    };
  }, [eventId, onRefresh]);

  return state;
}
