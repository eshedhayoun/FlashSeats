import { api, downloadPdf } from "./client";
import type {
  AdmitRequest,
  AdmitResponse,
  CheckoutRequest,
  EventDetails,
  EventListItem,
  CreateHoldRequest,
  HoldResponse,
  JoinQueueRequest,
  OrderReceipt,
  QueueStatusResponse,
  SaleState
} from "./types";

export function getEvents() {
  return api<EventListItem[]>("/events");
}

export function getEvent(eventId: number) {
  return api<EventDetails>(`/events/${eventId}`);
}

export function getSaleState(eventId: number) {
  return api<SaleState>(`/sale/${eventId}/state`);
}

export function joinQueue(request: JoinQueueRequest) {
  return api<QueueStatusResponse>("/queue/join", {
    method: "POST",
    body: JSON.stringify(request)
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
    body: JSON.stringify(request)
  });
}

export function getHold(holdToken: string) {
  return api<HoldResponse>(`/holds/${encodeURIComponent(holdToken)}`);
}

export function releaseHold(holdToken: string) {
  return api<void>(`/holds/${encodeURIComponent(holdToken)}`, {
    method: "DELETE"
  });
}

export function checkout(request: CheckoutRequest) {
  return api<OrderReceipt>("/orders/checkout", {
    method: "POST",
    body: JSON.stringify(request)
  });
}

export function getOrder(orderNumber: string, receiptToken?: string) {
  const query = receiptToken
    ? `?receiptToken=${encodeURIComponent(receiptToken)}`
    : "";

  return api<OrderReceipt>(
    `/orders/${encodeURIComponent(orderNumber)}${query}`
  );
}

export function downloadTicket(orderNumber: string, receiptToken?: string) {
  const query = receiptToken
    ? `?receiptToken=${encodeURIComponent(receiptToken)}`
    : "";

  return downloadPdf(
    `/orders/${encodeURIComponent(orderNumber)}/ticket.pdf${query}`
  );
}
