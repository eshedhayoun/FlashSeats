import type { Page } from '@playwright/test';

/**
 * Helper to inject server time offset into the page for clock testing.
 * This allows us to test timer behavior when the browser clock is wrong.
 */
export async function skewClockBy(
  page: Page,
  offsetMs: number
): Promise<void> {
  await page.evaluate(
    (offset) => {
      const originalNow = Date.now;
      (globalThis as any).Date.now = () => originalNow() + offset;
    },
    offsetMs
  );
}

/**
 * Get a storage value from session/local storage by key.
 */
export async function getStorageValue(
  page: Page,
  key: string
): Promise<string | null> {
  return page.evaluate((k) => sessionStorage.getItem(k), key);
}

/**
 * Get all sale-scoped storage keys for an eventId.
 */
export async function getSaleStorage(
  page: Page,
  eventId: number
): Promise<Record<string, string>> {
  return page.evaluate((eid) => {
    const result: Record<string, string> = {};
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i);
      if (key && key.startsWith(`fs.${eid}.`)) {
        result[key] = sessionStorage.getItem(key) || '';
      }
    }
    return result;
  }, eventId);
}

/**
 * Clear all session storage for a specific event (useful for simulating
 * the page behavior when storage was lost).
 */
export async function clearSaleStorage(page: Page, eventId: number): Promise<void> {
  await page.evaluate((eid) => {
    const keysToRemove: string[] = [];
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i);
      if (key && key.startsWith(`fs.${eid}.`)) {
        keysToRemove.push(key);
      }
    }
    keysToRemove.forEach((k) => sessionStorage.removeItem(k));
  }, eventId);
}

/**
 * Get the current server clock offset from the page.
 */
export async function getClockOffset(page: Page): Promise<number> {
  return page.evaluate(() => {
    // Assume the page has imported serverClock
    // This is a bit hacky but allows us to inspect the internal state
    return (globalThis as any).__clockOffsetMs || 0;
  });
}

/**
 * Expose the clock offset globally for testing.
 */
export async function exposeClockOffset(page: Page): Promise<void> {
  await page.addInitScript(() => {
    // This runs before the page scripts load
    // We'll capture it after the app initializes
  });

  // After navigation, expose the clock
  await page.addInitScript(
    () => {
      Object.defineProperty(globalThis, '__debugClock', {
        get: () => {
          // Try to access the serverClock singleton
          // This is implementation-specific and may need adjustment
          return (globalThis as any).__serverClock;
        },
      });
    }
  );
}

/**
 * Poll a condition until it's true, with a maximum wait time.
 */
export async function waitFor(
  condition: () => Promise<boolean>,
  timeoutMs: number = 10000
): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (await condition()) {
      return;
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`Condition not met after ${timeoutMs}ms`);
}
