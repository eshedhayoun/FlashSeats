import { test as base, type Page } from '@playwright/test';

export type BuyerFixture = {
  page: Page;
  userId: string;
};

export type SaleFixture = {
  eventId: number;
  tierId: number;
  tierName: string;
  capacity: number;
};

type ApiEvent = {
  eventId: number;
  title: string;
  windowStatus: string;
};

type ApiEventDetails = {
  eventId: number;
  tiers: Array<{
    tierId: number;
    tierName: string;
    availability: string;
    maxPerOrder: number;
  }>;
};

async function loadSales(minCount: number): Promise<SaleFixture[]> {
  const response = await fetch('http://localhost:8081/api/v1/events');
  if (!response.ok) {
    throw new Error(`GET /events failed with ${response.status}`);
  }

  const events = (await response.json()) as ApiEvent[];
  if (!Array.isArray(events)) {
    throw new Error('GET /events did not return an array');
  }

  const sales: SaleFixture[] = [];

  for (const event of events) {
    if (event.windowStatus !== 'OPEN') continue;

    const detailResponse = await fetch(
      `http://localhost:8081/api/v1/events/${event.eventId}`
    );
    if (!detailResponse.ok) continue;

    const details = (await detailResponse.json()) as ApiEventDetails;
    const tier = details.tiers.find(
      (candidate) =>
        candidate.availability !== 'SOLD_OUT' && candidate.maxPerOrder >= 1
    );

    if (!tier) continue;

    // The current public event DTO does not expose raw remaining capacity,
    // so the fixture keeps the field for compatibility and uses 0 as unknown.
    sales.push({
      eventId: details.eventId,
      tierId: tier.tierId,
      tierName: tier.tierName,
      capacity: 0,
    });

    if (sales.length >= minCount) break;
  }

  if (sales.length < minCount) {
    throw new Error(
      `Need at least ${minCount} open sales with an available tier; found ${sales.length}.`
    );
  }

  return sales;
}

export type FlashSeatsFixtures = {
  buyer: BuyerFixture;
  sale: SaleFixture;
  sales: [SaleFixture, SaleFixture];
};

export const test = base.extend<FlashSeatsFixtures>({
  buyer: async ({ page }, use) => {
    const userId = Math.random().toString(36).slice(2);
    await use({ page, userId });
  },

  sale: async ({}, use) => {
    const [sale] = await loadSales(1);
    await use(sale);
  },

  sales: async ({}, use) => {
    const sales = await loadSales(2);
    await use([sales[0], sales[1]]);
  },
});

export { expect } from '@playwright/test';
