export type Availability = "PLENTY" | "LIMITED" | "SOLD_OUT" | "UNKNOWN";
export type WindowStatus = "UPCOMING" | "OPEN" | "CLOSED";
export type QueueState =
  | "NOT_JOINED"
  | "WAITING"
  | "PROMOTED"
  | "ADMITTED"
  | "EXHAUSTED"
  | "CLOSED";
export type OrderStatus = "PENDING" | "CONFIRMED" | "FAILED" | "REFUNDED";

export type Problem = {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  traceId?: string;
  retryable?: boolean;
  attemptsRemaining?: number;
  expiresAt?: string;
  retryAfterSeconds?: number;
};

export type EventTier = {
  tierId: number;
  tierName: string;
  priceCents: number;
  currency: string;
  maxPerOrder: number;
  availability: Availability;
};

export type EventListItem = {
  eventId: number;
  title: string;
  venueName: string;
  eventStartTime: string;
  saleStartTime: string;
  windowStatus: WindowStatus;
};

export type EventDetails = {
  eventId: number;
  title: string;
  description: string;
  venueName: string;
  eventStartTime: string;
  saleStartTime: string;
  saleEndTime: string;
  windowStatus: WindowStatus;
  serverTime: string;
  tiers: EventTier[];
};

export type SaleQueueState = {
  state: QueueState;
  position: number | null;
  estWaitSeconds: number | null;
  admissionExpiresAt: string | null;
  passToken: string | null;
};

export type ActiveHold = {
  holdToken: string;
  tierId: number;
  quantity: number;
  expiresAt: string;
  ttlRemainingSeconds: number;
};

export type SaleOrder = {
  orderNumber: string;
  status: OrderStatus;
};

export type SaleState = {
  eventId: number;
  windowStatus: WindowStatus;
  serverTime: string;
  queue: SaleQueueState | null;
  hold: ActiveHold | null;
  order: SaleOrder | null;
  partial: string[];
};

export type JoinQueueRequest = {
  eventId: number;
  recaptchaToken?: string;
};

export type QueueStatusResponse = {
  phase: QueueState;
  position: number | null;
  aheadOfYou: number | null;
  estWaitSeconds: number | null;
  passToken: string | null;
  admissionExpiresAt: string | null;
  serverTime: string;
};

export type AdmitRequest = {
  eventId: number;
};

export type AdmitResponse = {
  admissionToken: string;
  expiresAt: string;
  serverTime: string;
};

export type CreateHoldRequest = {
  eventId: number;
  tierId: number;
  quantity: number;
};

export type HoldResponse = {
  holdToken: string;
  eventId: number;
  tierId: number;
  quantity: number;
  expiresAt: string;
  ttlRemainingSeconds: number;
  serverTime: string;
};

export type CheckoutRequest = {
  holdToken: string;
  userEmail: string;
  paymentMethodId: string;
  idempotencyKey?: string;
};

export type OrderItem = {
  eventId: number;
  tierId: number;
  tierName: string;
  quantity: number;
  unitPriceCents: number;
};

export type OrderReceipt = {
  orderNumber: string;
  status: OrderStatus;
  userEmail: string;
  totalAmountCents: number;
  currency: string;
  receiptToken: string;
  createdAt: string;
  items: OrderItem[];
};

export type PositionUpdateEvent = {
  position: number;
  aheadOfYou: number;
  estWaitSeconds: number;
};

export type QueuePromotedEvent = {
  passToken: string;
  expiresInSeconds: number;
};

export type TierAvailabilityEvent = {
  tiers: Array<{
    tierId: number;
    level: Availability;
  }>;
};

export type SaleExhaustedEvent = {
  soldOutAt: string;
};

export type SaleClosedEvent = {
  saleEndTime: string;
};
