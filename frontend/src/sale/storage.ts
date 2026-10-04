import type { OrderReceipt } from "../api/types";

export type RecentOrder = {
  orderNumber: string;
  receiptToken: string;
  eventTitle: string;
};

const RECENT_ORDERS_KEY = "fs.recentOrders";
const RECENT_ORDERS_LIMIT = 20;

/** Every per-sale key is `fs.{eventId}.{name}`: there is no "current event" (FE_SPEC §0 rule 5). */
export function saleStorageKey(eventId: number, name: string) {
  return `fs.${eventId}.${name}`;
}

/*
 * Storage can throw — private browsing, a full quota, a sandboxed frame. Every value here is a hint
 * that speeds the next paint; the server is the truth (FE_SPEC §0 rule 3), so a failed read is simply
 * "no hint".
 */
function read(storage: Storage, key: string): string | null {
  try {
    return storage.getItem(key);
  } catch {
    return null;
  }
}

function write(storage: Storage, key: string, value: string): void {
  try {
    storage.setItem(key, value);
  } catch {
    // See above: losing a hint costs a slower first paint, nothing more.
  }
}

function remove(storage: Storage, key: string): void {
  try {
    storage.removeItem(key);
  } catch {
    // Nothing to do.
  }
}

export function getSaleValue(eventId: number, name: string): string | null {
  return read(sessionStorage, saleStorageKey(eventId, name));
}

export function setSaleValue(eventId: number, name: string, value: string): void {
  write(sessionStorage, saleStorageKey(eventId, name), value);
}

export function removeSaleValue(eventId: number, name: string): void {
  remove(sessionStorage, saleStorageKey(eventId, name));
}

/*
 * The admission token lives in localStorage, unlike every other per-sale key. An admission belongs to
 * the session — the fsid cookie, which every tab shares — and the server only accepts a hold request
 * that carries it. Kept per tab, a second tab of the same buyer in the same sale would be admitted on
 * the server and refused at /holds with ADMISSION_REQUIRED, looping between the two. It is still
 * namespaced by event, and cleared the moment rehydration says the admission is over.
 */
export function getAdmissionToken(eventId: number): string | null {
  return read(localStorage, saleStorageKey(eventId, "admissionToken")) ?? getSaleValue(eventId, "admissionToken");
}

export function setAdmissionToken(eventId: number, token: string): void {
  write(localStorage, saleStorageKey(eventId, "admissionToken"), token);
  removeSaleValue(eventId, "admissionToken");
}

export function removeAdmissionToken(eventId: number): void {
  remove(localStorage, saleStorageKey(eventId, "admissionToken"));
  removeSaleValue(eventId, "admissionToken");
}

export function getHoldToken(eventId: number): string | null {
  return getSaleValue(eventId, "holdToken");
}

export function setHoldToken(eventId: number, token: string): void {
  setSaleValue(eventId, "holdToken", token);
}

export function getLastEventId(eventId: number): string | null {
  return getSaleValue(eventId, "lastEventId");
}

export function setLastEventId(eventId: number, lastEventId: string): void {
  setSaleValue(eventId, "lastEventId", lastEventId);
}

/** The position first seen in line, which the progress bar measures from. */
export function getQueueStart(eventId: number): number | null {
  const stored = Number(getSaleValue(eventId, "queueStart"));
  return Number.isInteger(stored) && stored > 0 ? stored : null;
}

export function setQueueStart(eventId: number, position: number): void {
  setSaleValue(eventId, "queueStart", String(position));
}

export function removeQueueStart(eventId: number): void {
  removeSaleValue(eventId, "queueStart");
}

/**
 * ONE idempotency key per hold, reused by every retry of it (FE_SPEC V4). A fresh key per attempt
 * would open a second payment for one authentication (ADR-054).
 */
export function getIdempotencyKey(eventId: number, holdToken: string): string {
  const name = `idem.${holdToken}`;
  const existing = getSaleValue(eventId, name);
  if (existing) return existing;

  const created = crypto.randomUUID();
  setSaleValue(eventId, name, created);
  return created;
}

/** The email typed for this hold, so a reload does not empty the form. Per tab, gone with the hold. */
export function getCheckoutEmail(eventId: number, holdToken: string): string {
  return getSaleValue(eventId, `email.${holdToken}`) ?? "";
}

export function setCheckoutEmail(eventId: number, holdToken: string, email: string): void {
  setSaleValue(eventId, `email.${holdToken}`, email);
}

/**
 * Set before a payment is sent and cleared when any answer comes back. Still set after a reload means
 * the page went away mid-charge, which is when the buyer must be told a payment may be finishing —
 * never that nothing happened (FE_SPEC §3, the "V4, mid-charge" row).
 */
export function markPaymentInFlight(eventId: number, holdToken: string): void {
  setSaleValue(eventId, `paying.${holdToken}`, String(Date.now()));
}

export function clearPaymentInFlight(eventId: number, holdToken: string): void {
  removeSaleValue(eventId, `paying.${holdToken}`);
}

export function wasPaymentInFlight(eventId: number, holdToken: string): boolean {
  return getSaleValue(eventId, `paying.${holdToken}`) !== null;
}

/** Everything this tab kept for a hold that is over: settled, released or gone (FE_SPEC V5). */
export function clearHoldStorage(eventId: number, holdToken: string): void {
  if (getHoldToken(eventId) === holdToken) {
    removeSaleValue(eventId, "holdToken");
  }
  removeSaleValue(eventId, `idem.${holdToken}`);
  removeSaleValue(eventId, `email.${holdToken}`);
  removeSaleValue(eventId, `paying.${holdToken}`);
}

export function getRecentOrders(): RecentOrder[] {
  const raw = read(localStorage, RECENT_ORDERS_KEY);
  if (!raw) return [];

  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isRecentOrder);
  } catch {
    return [];
  }
}

/**
 * The one list meant to outlive a tab: how a buyer finds every ticket across sales. The receipt token
 * in it is a bearer capability (FE_SPEC §3.1), so it is rendered only inside a link the buyer chooses
 * to open, never as text.
 */
export function rememberOrder(
  receipt: Pick<OrderReceipt, "orderNumber" | "receiptToken">,
  eventTitle: string
): void {
  const order: RecentOrder = {
    orderNumber: receipt.orderNumber,
    receiptToken: receipt.receiptToken,
    eventTitle
  };

  const orders = getRecentOrders().filter((existing) => existing.orderNumber !== order.orderNumber);
  write(localStorage, RECENT_ORDERS_KEY, JSON.stringify([order, ...orders].slice(0, RECENT_ORDERS_LIMIT)));
}

function isRecentOrder(value: unknown): value is RecentOrder {
  if (typeof value !== "object" || value === null) return false;

  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.orderNumber === "string" &&
    typeof candidate.receiptToken === "string" &&
    typeof candidate.eventTitle === "string"
  );
}
