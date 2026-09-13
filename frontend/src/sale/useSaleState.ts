import { useCallback, useEffect, useState } from "react";
import { ApiError } from "../api/errors";
import type { SaleState } from "../api/types";
import { bootstrapSale, type BootstrappedSale } from "./saleBootstrap";

type SaleStateStatus = "idle" | "loading" | "ready" | "error";

export type UseSaleStateResult = {
  status: SaleStateStatus;
  data: BootstrappedSale | null;
  error: ApiError | null;
  refresh: () => Promise<void>;
};

export function useSaleState(
  eventId: number,
  options: { buyMore?: boolean } = {}
): UseSaleStateResult {
  const [status, setStatus] = useState<SaleStateStatus>("idle");
  const [data, setData] = useState<BootstrappedSale | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const refresh = useCallback(async () => {
    setStatus("loading");
    setError(null);

    try {
      const next = await bootstrapSale(eventId, options);
      setData(next);
      setStatus("ready");
    } catch (cause) {
      const nextError =
        cause instanceof ApiError
          ? cause
          : new ApiError({
              type: "about:blank",
              title: "Sale state unavailable",
              status: 0,
              detail: "The sale state could not be loaded.",
              code: "CLIENT_ERROR"
            });
      setError(nextError);
      setStatus("error");
    }
  }, [eventId, options.buyMore]);

  useEffect(() => {
    void refresh();
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

  return { status, data, error, refresh };
}
