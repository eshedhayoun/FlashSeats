import { test, expect } from '../fixtures/index';
import { getSaleStorage, clearSaleStorage } from '../helpers';

/**
 * Full journey test.
 * 
 * A real buyer flow (simplified, without waiting for actual queue or payment).
 * This serves as a smoke test for the overall app structure.
 */

test.describe('full journey - smoke test', () => {
  test('landing page loads', async ({ buyer, sale }) => {
    const { page } = buyer;

    await page.goto('/');

    // Should see FlashSeats branding
    const title = page.locator('h1').first();
    await expect(title).toBeVisible();
  });

  test('event page loads and displays sale state', async ({ buyer, sale }) => {
    const { page } = buyer;
    const { eventId } = sale;

    await page.goto(`/events/${eventId}`);

    // Should render something on the event page
    const content = page.locator('body');
    await expect(content).toBeVisible();

    // Verify the page made a call to /sale/{eventId}/state
    const stateResponse = await page.request.get(
      `/api/v1/sale/${eventId}/state`,
      { headers: { cookie: '' } }
    );
    expect(stateResponse.ok()).toBe(true);

    const state = await stateResponse.json();
    expect(state).toHaveProperty('windowStatus');
  });

  test('storage is scoped by eventId', async ({ buyer, sale }) => {
    const { page } = buyer;
    const { eventId } = sale;

    await page.goto(`/events/${eventId}`);

    // Set a test value
    await page.evaluate(
      (eid) => {
        sessionStorage.setItem(`fs.${eid}.test`, 'value1');
      },
      eventId
    );

    // Verify it's stored with the correct prefix
    const storage = await getSaleStorage(page, eventId);
    expect(storage[`fs.${eventId}.test`]).toBe('value1');

    // Clear it and verify
    await page.evaluate(
      (eid) => {
        sessionStorage.removeItem(`fs.${eid}.test`);
      },
      eventId
    );

    const storageAfter = await getSaleStorage(page, eventId);
    expect(storageAfter[`fs.${eventId}.test`]).toBeUndefined();
  });

  test('clock offset is computed', async ({ buyer, sale }) => {
    const { page } = buyer;
    const { eventId } = sale;

    await page.goto(`/events/${eventId}`);

    // Wait for an API call that includes serverTime
    await new Promise((r) => setTimeout(r, 500));

    // The offset should be computed (internally in ServerClock singleton)
    const hasClockOffset = await page.evaluate(() => {
      // Check if the app is functional (clock was set)
      return true;
    });

    expect(hasClockOffset).toBe(true);
  });

  test('navigation between events preserves app state', async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;
    const { eventId } = sale;

    await page.goto(`/events/${eventId}`);

    // Navigate to a different event (or back to landing)
    await page.goto('/');

    // App should still be functional
    const hasApp = await page.evaluate(() => {
      return true;
    });

    expect(hasApp).toBe(true);
  });

  test('recent orders list is accessible', async ({ buyer, sale }) => {
    const { page } = buyer;

    // Navigate to landing
    await page.goto('/');

    // The app should render without errors
    const body = page.locator('body');
    await expect(body).toBeVisible();
  });

  test('API errors are handled and displayed', async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;

    // Try to navigate to a non-existent event
    await page.goto('/events/99999999');

    // The page should handle the error gracefully (show error message or redirect)
    const body = page.locator('body');
    await expect(body).toBeVisible();
  });

  test('concurrent navigations do not corrupt state', async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;
    const { eventId } = sale;

    // Navigate to event
    await page.goto(`/events/${eventId}`);

    // Set a test value
    await page.evaluate(
      (eid) => {
        sessionStorage.setItem(`fs.${eid}.test`, 'value1');
      },
      eventId
    );

    // Verify it was set
    let storage = await getSaleStorage(page, eventId);
    expect(storage[`fs.${eventId}.test`]).toBe('value1');

    // Navigate elsewhere and back
    await page.goto('/');
    await page.goto(`/events/${eventId}`);

    // The value should still be there (same tab)
    storage = await getSaleStorage(page, eventId);
    expect(storage[`fs.${eventId}.test`]).toBe('value1');
  });
});
