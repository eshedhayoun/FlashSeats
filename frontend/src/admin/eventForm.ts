import type { CreateEventRequest } from "./adminApi";

/** One tier as typed: strings, because that is what inputs hold. */
export type TierForm = { tierName: string; price: string; totalCapacity: string; maxPerOrder: string };

/** The create form as typed. Times are `datetime-local` values, in the operator's time zone. */
export type EventForm = {
  title: string;
  description: string;
  venueName: string;
  saleStartTime: string;
  saleEndTime: string;
  eventStartTime: string;
  tiers: TierForm[];
};

/** The server's limits (ADR-080), mirrored for fast feedback. The server stays the authority. */
export const MAX_TIERS = 10;
export const MAX_PER_ORDER = 6;
const MIN_LEAD_MS = 2 * 60_000;
const HORIZON_MS = 5 * 365 * 24 * 3_600_000;
const MIN_PRICE_CENTS = 50;
const MAX_PRICE_CENTS = 10_000_000;

const pad = (value: number) => String(value).padStart(2, "0");

/** A `datetime-local` value for this instant, in the browser's time zone. */
export function toLocalInput(date: Date): string {
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

export const emptyTier = (): TierForm => ({ tierName: "", price: "", totalCapacity: "", maxPerOrder: "4" });

/** A sale that passes every check as it stands, so a demo is one click: opens in 10 minutes. */
export function defaultForm(now: Date): EventForm {
  const saleStart = new Date(now.getTime() + 10 * 60_000);
  saleStart.setSeconds(0, 0);
  return {
    title: "",
    description: "",
    venueName: "",
    saleStartTime: toLocalInput(saleStart),
    saleEndTime: toLocalInput(new Date(saleStart.getTime() + 4 * 3_600_000)),
    eventStartTime: toLocalInput(new Date(saleStart.getTime() + 7 * 24 * 3_600_000)),
    tiers: [{ tierName: "General Admission", price: "50.00", totalCapacity: "100", maxPerOrder: "4" }]
  };
}

const instant = (local: string): Date | null => {
  const date = new Date(local);
  return local && !Number.isNaN(date.getTime()) ? date : null;
};

const wholeNumber = (value: string): number | null =>
  /^\d+$/.test(value.trim()) ? Number(value.trim()) : null;

/** Dollars as typed to cents, without float drift: "45.10" is 4510, not 4509.999. */
export function toCents(price: string): number | null {
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(price.trim());
  if (!match) return null;
  return Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
}

/** The request, or the first thing wrong with the form, in the operator's terms. */
export function toCreateRequest(form: EventForm, now: Date): { request: CreateEventRequest } | { error: string } {
  if (!form.title.trim()) return { error: "Give the sale a title." };
  if (!form.venueName.trim()) return { error: "Give the sale a venue." };

  const saleStart = instant(form.saleStartTime);
  const saleEnd = instant(form.saleEndTime);
  const eventStart = instant(form.eventStartTime);
  if (!saleStart || !saleEnd || !eventStart) return { error: "Fill in all three dates." };
  if (saleStart.getTime() < now.getTime() + MIN_LEAD_MS) {
    return { error: "The sale must start at least 2 minutes from now." };
  }
  if (saleEnd <= saleStart) return { error: "The sale must end after it starts." };
  if (eventStart <= saleStart) return { error: "The event must start after its sale does." };
  if (Math.max(saleEnd.getTime(), eventStart.getTime()) > now.getTime() + HORIZON_MS) {
    return { error: "Dates must be within five years from now." };
  }

  if (form.tiers.length === 0) return { error: "Add at least one tier." };
  if (form.tiers.length > MAX_TIERS) return { error: `At most ${MAX_TIERS} tiers.` };
  const tiers: CreateEventRequest["tiers"] = [];
  for (const [index, tier] of form.tiers.entries()) {
    const label = tier.tierName.trim() || `Tier ${index + 1}`;
    if (!tier.tierName.trim()) return { error: `Name tier ${index + 1}.` };
    const priceCents = toCents(tier.price);
    if (priceCents === null || priceCents < MIN_PRICE_CENTS || priceCents > MAX_PRICE_CENTS) {
      return { error: `${label}: the price must be between $0.50 and $100,000, in dollars and cents.` };
    }
    const totalCapacity = wholeNumber(tier.totalCapacity);
    if (totalCapacity === null || totalCapacity < 1 || totalCapacity > 100_000) {
      return { error: `${label}: capacity must be between 1 and 100,000.` };
    }
    const maxPerOrder = wholeNumber(tier.maxPerOrder);
    if (maxPerOrder === null || maxPerOrder < 1 || maxPerOrder > MAX_PER_ORDER) {
      return { error: `${label}: tickets per order must be between 1 and ${MAX_PER_ORDER}.` };
    }
    tiers.push({ tierName: tier.tierName.trim(), priceCents, totalCapacity, maxPerOrder });
  }

  return {
    request: {
      title: form.title.trim(),
      description: form.description.trim() || null,
      venueName: form.venueName.trim(),
      saleStartTime: saleStart.toISOString(),
      saleEndTime: saleEnd.toISOString(),
      eventStartTime: eventStart.toISOString(),
      tiers
    }
  };
}
