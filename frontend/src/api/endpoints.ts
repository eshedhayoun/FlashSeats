import { api, downloadPdf } from "./client";
import type {
  AdmitRequest,
  AdmitResponse,
  CheckoutRequest,
  CreateHoldRequest,
  EventDetails,
  EventListItem,
  HoldResponse,
  JoinQueueRequest,
  OrderReceipt,
  QueueStatusResponse,
  SaleState
} from "./types";

/*
 * Retries are per call, chosen by what a repeat means. Reads repeat freely. A join is ZADD NX, a hold
 * after SERVICE_BUSY was never created (ADR-067), and checkout is find-or-create on the hold
 * (ADR-002): each is safe to send again. Admit is not retried here — its pass is single-use, and the
 * sale state, not a blind repeat, decides what happens next.
 */
const READ_RETRIES = 2;

export function getEvents() {
  return api<EventListItem[]>("/events", { retries: READ_RETRIES });
}

export function getEvent(eventId: number) {
  return api<EventDetails>(`/events/${eventId}`, { retries: READ_RETRIES });
}

export function getSaleState(eventId: number) {
  return api<SaleState>(`/sale/${eventId}/state`, { retries: READ_RETRIES });
}

export function joinQueue(request: JoinQueueRequest) {
  return api<QueueStatusResponse>("/queue/join", {
    method: "POST",
    body: JSON.stringify(request),
    retries: 2
  });
}

export function leaveQueue(eventId: number) {
  return api<void>("/queue/leave", {
    method: "POST",
    body: JSON.stringify({ eventId }),
    retries: 2
  });
}

export function getQueueStatus(eventId: number) {
  return api<QueueStatusResponse>(`/queue/status?eventId=${eventId}`);
}

export function admitQueue(request: AdmitRequest, passToken: string) {
  return api<AdmitResponse>("/queue/admit", {
    method: "POST",
    headers: { "X-Queue-Pass-Token": passToken },
    body: JSON.stringify(request)
  });
}

export function createHold(request: CreateHoldRequest, admissionToken: string) {
  return api<HoldResponse>("/holds", {
    method: "POST",
    headers: { "X-Admission-Token": admissionToken },
    body: JSON.stringify(request),
    retries: 2
  });
}

export function releaseHold(holdToken: string) {
  return api<void>(`/holds/${encodeURIComponent(holdToken)}`, {
    method: "DELETE",
    retries: 2
  });
}

export function checkout(request: CheckoutRequest) {
  return api<OrderReceipt>("/orders/checkout", {
    method: "POST",
    body: JSON.stringify(request),
    retries: 3
  });
}

export function getOrder(orderNumber: string, receiptToken?: string) {
  const query = receiptToken ? `?receiptToken=${encodeURIComponent(receiptToken)}` : "";
  return api<OrderReceipt>(`/orders/${encodeURIComponent(orderNumber)}${query}`, {
    retries: READ_RETRIES
  });
}

export function downloadTicket(orderNumber: string, receiptToken?: string) {
  const query = receiptToken ? `?receiptToken=${encodeURIComponent(receiptToken)}` : "";
  return downloadPdf(`/orders/${encodeURIComponent(orderNumber)}/ticket.pdf${query}`);
}
