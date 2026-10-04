import { useCallback, useEffect, useRef, useState } from "react";
import { getEvent } from "../api/endpoints";
import { asApiError, type ApiError } from "../api/errors";
import type { EventDetails } from "../api/types";
import { serverClock } from "../clock/serverClock";

export type UseEventResult = {
  event: EventDetails | null;
  error: ApiError | null;
  loading: boolean;
  /** A fresh read — after `INSUFFICIENT_STOCK`, or on entering seat selection, where availability matters. */
  reload: () => Promise<void>;
};

/**
 * How long to wait before asking again while the sale has not opened (FE_SPEC V1): every 30 s, every
 * 10 s in the final minute, then just after the opening instant, then every 2 s until the server agrees.
 */
export function upcomingPollDelayMs(remainingMs: number): number {
  if (remainingMs > 60_000) return Math.min(30_000, remainingMs - 60_000 + 250);
  if (remainingMs > 0) return Math.min(10_000, remainingMs + 250);
  return 2_000;
}

/** One event's details. Refreshes in the background: once loaded, it never empties the screen. */
export function useEvent(eventId: number): UseEventResult {
  const [event, setEvent] = useState<EventDetails | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const mounted = useRef(true);

  const reload = useCallback(async () => {
    try {
      const next = await getEvent(eventId);
      if (!mounted.current) return;
      setEvent(next);
      setError(null);
    } catch (cause) {
      if (!mounted.current) return;
      setError(asApiError(cause, "The event could not be loaded."));
    }
  }, [eventId]);

  useEffect(() => {
    mounted.current = true;
    void reload();
    return () => {
      mounted.current = false;
    };
  }, [reload]);

  // Before the sale opens, keep asking — so the page opens itself at T-0 with no reload (U-4).
  useEffect(() => {
    if (!event || event.windowStatus !== "UPCOMING") return;
    const timer = window.setTimeout(
      () => void reload(),
      upcomingPollDelayMs(serverClock.remainingMs(event.saleStartTime))
    );
    return () => window.clearTimeout(timer);
  }, [event, reload]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === "visible") void reload();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [reload]);

  return { event, error, loading: event === null && error === null, reload };
}
