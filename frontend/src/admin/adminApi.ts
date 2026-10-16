import { api } from "../api/client";
import type { WindowStatus } from "../api/types";

/** The operator's login. Held in memory only: a reload signs out (ADR-080). */
export type Credentials = { username: string; password: string };

export type EventStatus = "DRAFT" | "PUBLISHED" | "PAUSED" | "CANCELLED";

export type AdminTier = {
  tierId: number;
  tierName: string;
  priceCents: number;
  currency: string;
  totalCapacity: number;
  maxPerOrder: number;
  /** `null` when the tier has no counter: unknown, never zero (ADR-040). */
  remaining: number | null;
};

export type AdminEvent = {
  eventId: number;
  title: string;
  description: string | null;
  venueName: string;
  eventStartTime: string;
  saleStartTime: string;
  saleEndTime: string;
  status: EventStatus;
  windowStatus: WindowStatus;
  /** The sale will sell: every counter present and vouched for by the running Redis. */
  prewarmed: boolean;
  deletable: boolean;
  tiers: AdminTier[];
};

export type CreateEventRequest = {
  title: string;
  description: string | null;
  venueName: string;
  eventStartTime: string;
  saleStartTime: string;
  saleEndTime: string;
  tiers: { tierName: string; priceCents: number; totalCapacity: number; maxPerOrder: number }[];
};

/** UTF-8, then base64: `btoa` alone throws on any character outside Latin-1. */
function basic({ username, password }: Credentials): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  return `Basic ${btoa(String.fromCharCode(...bytes))}`;
}

/**
 * Every operator call. `X-Requested-With` makes a refused login answer with a challenge browsers do
 * not prompt for, so the browser's own dialog never covers the console's form (ADR-080).
 */
function adminCall<T>(credentials: Credentials, path: string, init: RequestInit = {}): Promise<T> {
  return api<T>(`/admin${path}`, {
    ...init,
    headers: { Authorization: basic(credentials), "X-Requested-With": "XMLHttpRequest" }
  });
}

export const listEvents = (credentials: Credentials) => adminCall<AdminEvent[]>(credentials, "/events");

export const createEvent = (credentials: Credentials, request: CreateEventRequest) =>
  adminCall<AdminEvent>(credentials, "/events", { method: "POST", body: JSON.stringify(request) });

export type EventAction = "prewarm" | "pause" | "resume" | "rebuild" | "delete";

/** Runs one action on one sale. The server re-checks everything the console decided to show. */
export function runAction(credentials: Credentials, eventId: number, action: EventAction): Promise<unknown> {
  if (action === "delete") {
    return adminCall(credentials, `/events/${eventId}`, { method: "DELETE" });
  }
  const path = action === "rebuild" ? "rebuild-stock" : action;
  return adminCall(credentials, `/events/${eventId}/${path}`, { method: "POST" });
}

/**
 * The actions the server would accept for this sale right now — never one it would refuse. The
 * server stays the authority; this only keeps buttons that cannot work off the screen.
 */
export function actionsFor(event: AdminEvent): EventAction[] {
  const live = event.windowStatus !== "CLOSED";
  const actions: EventAction[] = [];
  if (event.windowStatus === "UPCOMING" && !event.prewarmed) actions.push("prewarm");
  if (event.status === "PUBLISHED" && live) actions.push("pause");
  if (event.status === "PAUSED" && live) actions.push("resume");
  if (event.windowStatus === "OPEN" || event.windowStatus === "PAUSED") actions.push("rebuild");
  if (event.deletable) actions.push("delete");
  return actions;
}

/** Why a sale will not sell, and what fixes it; `null` when it will. */
export function readiness(event: AdminEvent): { severity: "warning" | "error"; message: string } | null {
  if (event.prewarmed) return null;
  if (event.windowStatus === "UPCOMING") {
    return { severity: "warning", message: "Not pre-warmed: it will not sell when it opens. Pre-warm it now." };
  }
  if (event.windowStatus === "OPEN" || event.windowStatus === "PAUSED") {
    return { severity: "error", message: "Stock is not trusted: holds are refused until you rebuild it." };
  }
  return null;
}
