import type { OrderReceipt } from "../api/types";

export type RecentOrder = {
  orderNumber: string;
  receiptToken: string;
  eventTitle: string;
};

const RECENT_ORDERS_KEY = "fs.recentOrders";

export function saleStorageKey(eventId: number, name: string) {
  return `fs.${eventId}.${name}`;
}

export function getSaleValue(eventId: number, name: string): string | null {
  return sessionStorage.getItem(saleStorageKey(eventId, name));
}

export function setSaleValue(eventId: number, name: string, value: string): void {
  sessionStorage.setItem(saleStorageKey(eventId, name), value);
}

export function removeSaleValue(eventId: number, name: string): void {
  sessionStorage.removeItem(saleStorageKey(eventId, name));
}

export function getAdmissionToken(eventId: number): string | null {
  return (
    localStorage.getItem(saleStorageKey(eventId, "admissionToken")) ??
    getSaleValue(eventId, "admissionToken")
  );
}

export function setAdmissionToken(eventId: number, token: string): void {
  localStorage.setItem(saleStorageKey(eventId, "admissionToken"), token);
  removeSaleValue(eventId, "admissionToken");
}

export function removeAdmissionToken(eventId: number): void {
  localStorage.removeItem(saleStorageKey(eventId, "admissionToken"));
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

export function getIdempotencyKey(eventId: number, holdToken: string): string {
  const name = `idem.${holdToken}`;
  const existing = getSaleValue(eventId, name);
  if (existing) return existing;

  const created = crypto.randomUUID();
  setSaleValue(eventId, name, created);
  return created;
}

export function clearHoldStorage(eventId: number, holdToken: string): void {
  removeSaleValue(eventId, "holdToken");
  //removeSaleValue(eventId, `idem.${holdToken}`);
}

export function getRecentOrders(): RecentOrder[] {
  const raw = localStorage.getItem(RECENT_ORDERS_KEY);
  if (!raw) return [];

  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];

    return parsed.filter(isRecentOrder);
  } catch {
    return [];
  }
}

export function rememberOrder(
  receipt: Pick<OrderReceipt, "orderNumber" | "receiptToken">,
  eventTitle: string
): void {
  const order: RecentOrder = {
    orderNumber: receipt.orderNumber,
    receiptToken: receipt.receiptToken,
    eventTitle
  };

  const orders = getRecentOrders().filter(
    (existing) => existing.orderNumber !== order.orderNumber
  );
  localStorage.setItem(
    RECENT_ORDERS_KEY,
    JSON.stringify([order, ...orders])
  );
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
