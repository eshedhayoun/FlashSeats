import { useCallback, useEffect, useRef, useState } from "react";
import { asApiError, type ApiError } from "../api/errors";
import { bootstrapSale, type BootstrappedSale } from "./saleBootstrap";

export type UseSaleStateResult = {
  /** The last answer. Kept through every later refresh, so nothing on screen is wiped by one. */
  data: BootstrappedSale | null;
  /** The last failure, cleared by the next success. With `data` present it is a background failure. */
  error: ApiError | null;
  /** True only before the first answer, which is the one time a loading view is right. */
  loading: boolean;
  refresh: () => Promise<void>;
};

/** Background retries after a failed refresh, while there is something on screen to keep current. */
const BACKGROUND_RETRY_MS = [3_000, 6_000, 12_000, 24_000];

/**
 * One sale's server state (FE_SPEC §3). Fetched on mount, on `visibilitychange` → visible, on
 * `online`, and whenever a view asks — after a stream frame, a 409 or a 410.
 *
 * Refreshes overlap constantly (a frame, a timer and a tab switch can all ask at once), so they are
 * coalesced: a request made while one is in flight runs once more after it, because the state may
 * have moved after the first one left.
 */
export function useSaleState(eventId: number, options: { buyMore?: boolean } = {}): UseSaleStateResult {
  const buyMore = options.buyMore ?? false;
  const [data, setData] = useState<BootstrappedSale | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const mounted = useRef(true);
  const inFlight = useRef<Promise<void> | null>(null);
  const again = useRef(false);
  const failures = useRef(0);

  const runOnce = useCallback(async () => {
    try {
      const next = await bootstrapSale(eventId, { buyMore });
      if (!mounted.current) return;
      failures.current = 0;
      setData(next);
      setError(null);
    } catch (cause) {
      if (!mounted.current) return;
      failures.current += 1;
      setError(asApiError(cause, "The sale could not be loaded."));
    }
  }, [eventId, buyMore]);

  const refresh = useCallback((): Promise<void> => {
    if (inFlight.current) {
      again.current = true;
      return inFlight.current;
    }
    const run = (async () => {
      do {
        again.current = false;
        await runOnce();
      } while (again.current && mounted.current);
    })().finally(() => {
      inFlight.current = null;
    });
    inFlight.current = run;
    return run;
  }, [runOnce]);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    return () => {
      mounted.current = false;
    };
  }, [refresh]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === "visible") void refresh();
    };
    const onOnline = () => void refresh();

    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("online", onOnline);
    return () => {
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("online", onOnline);
    };
  }, [refresh]);

  // A failure behind content already on screen is retried quietly, with growing gaps, so the screen
  // catches up by itself once the server answers again.
  useEffect(() => {
    if (!error || !data) return;
    const delay = BACKGROUND_RETRY_MS[Math.min(failures.current - 1, BACKGROUND_RETRY_MS.length - 1)];
    const timer = window.setTimeout(() => void refresh(), delay);
    return () => window.clearTimeout(timer);
  }, [error, data, refresh]);

  return { data, error, loading: data === null && error === null, refresh };
}
