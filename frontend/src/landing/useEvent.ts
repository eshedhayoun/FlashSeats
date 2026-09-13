import { useEffect, useState } from "react";
import { ApiError } from "../api/errors";
import { getEvent } from "../api/endpoints";
import type { EventDetails } from "../api/types";

type EventStatus = "loading" | "ready" | "error";

export function useEvent(eventId: number) {
  const [status, setStatus] = useState<EventStatus>("loading");
  const [event, setEvent] = useState<EventDetails | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let active = true;
    setStatus("loading");
    setError(null);

    void getEvent(eventId)
      .then((nextEvent) => {
        if (!active) return;
        setEvent(nextEvent);
        setStatus("ready");
      })
      .catch((cause: unknown) => {
        if (!active) return;
        setError(
          cause instanceof ApiError
            ? cause
            : new ApiError({
                type: "about:blank",
                title: "Event unavailable",
                status: 0,
                detail: "The event could not be loaded.",
                code: "CLIENT_ERROR"
              })
        );
        setStatus("error");
      });

    return () => {
      active = false;
    };
  }, [eventId]);

  return { status, event, error };
}
