import { test as base, type Page } from '@playwright/test';
import type { BrowserContext } from '@playwright/test';

/**
 * Buyer fixture: represents one buyer (one session/cookie jar).
 * Each test context gets a fresh browser context and page.
 */
export type BuyerFixture = {
  page: Page;
  userId: string; // unique id per test
};

export type SaleFixture = {
  eventId: number;
  tierId: number;
  capacity: number;
};

/**
 * Extend the test with buyer and sale fixtures.
 */
export const test = base.extend<{ buyer: BuyerFixture; sale: SaleFixture }>({
  buyer: async ({ page }, use) => {
    const userId = Math.random().toString(36).substring(7);
    const buyer: BuyerFixture = {
      page,
      userId,
    };
    await use(buyer);
  },

  sale: async ({}, use) => {
    // Seed a sale via SQL (this requires the backend to be running).
    // For now, we'll use the first available sale from the API.
    const response = await fetch('http://localhost:8080/api/v1/events', {
      credentials: 'include',
    });
    const events = await response.json();

    if (!events || events.length === 0) {
      throw new Error('No events available in backend. Run seed script first.');
    }

    const eventId = events[0].eventId;
    const detailResponse = await fetch(
      `http://localhost:8080/api/v1/events/${eventId}`,
      { credentials: 'include' }
    );
    const details = await detailResponse.json();
    const tierId = details.tiers[0].tierId;
    const capacity = details.tiers[0].capacity;

    await use({
      eventId,
      tierId,
      capacity,
    });
  },
});

export { expect } from '@playwright/test';
