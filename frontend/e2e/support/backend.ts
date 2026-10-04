/*
 * The suite talks to the backend the way a buyer and an operator do: the public API, plus the
 * operator's existing pause and resume. It never touches a database or Redis, and the backend has no
 * endpoints that exist only for it. States no API can create — a sale ending mid-test, a hold expiring,
 * a counter lost — are proven by the backend's integration tests and the client's unit tests instead.
 *
 * Needs a backend with at least one OPEN sale: `docker/scripts/dev-up.sh && ./mvnw spring-boot:run`.
 * E2E_ADMIN is the operator login ("admin:admin" on the dev profile).
 */

export const API = process.env.E2E_API ?? "http://localhost:8080";
const ADMIN = process.env.E2E_ADMIN ?? "admin:admin";

export type SaleTier = { tierId: number; priceCents: number; currency: string };
export type Sale = { eventId: number; title: string; tier: SaleTier };

type EventDetails = {
  eventId: number;
  title: string;
  windowStatus: string;
  tiers: Array<{ tierId: number; priceCents: number; currency: string; availability: string }>;
};

/** Open sales with a tier that still has seats, most recently listed first. */
export async function openSales(): Promise<Sale[]> {
  const list = (await (await fetch(`${API}/api/v1/events`)).json()) as Array<{ eventId: number; windowStatus: string }>;
  const sales: Sale[] = [];
  for (const { eventId } of list.filter((event) => event.windowStatus === "OPEN")) {
    const details = (await (await fetch(`${API}/api/v1/events/${eventId}`)).json()) as EventDetails;
    const tier = details.tiers.find((candidate) => candidate.availability === "PLENTY" || candidate.availability === "LIMITED");
    if (tier) sales.push({ eventId, title: details.title, tier: { tierId: tier.tierId, priceCents: tier.priceCents, currency: tier.currency } });
  }
  return sales;
}

async function admin(path: string): Promise<void> {
  const response = await fetch(`${API}/api/v1/admin${path}`, {
    method: "POST",
    headers: { Authorization: `Basic ${Buffer.from(ADMIN).toString("base64")}` }
  });
  if (!response.ok) throw new Error(`POST /admin${path} answered ${response.status}: ${await response.text()}`);
}

export const pause = (eventId: number) => admin(`/events/${eventId}/pause`);
export const resume = (eventId: number) => admin(`/events/${eventId}/resume`);

/** What the client renders for an amount, in the browser's (en-US) locale. */
export function money(cents: number, currency: string): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(cents / 100);
}
