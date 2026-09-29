import type { Page } from "@playwright/test";

async function ensureAppOrigin(page: Page): Promise<void> {
  const currentUrl = page.url();

  // New Playwright pages start at about:blank, where relative fetch()
  // URLs such as /api/v1/... cannot be resolved.
  if (!currentUrl.startsWith("http://localhost:5173")) {
    await page.goto("/");
  }
}

/**
 * Inject a browser clock skew before the application loads.
 */
export async function skewClockBy(
  page: Page,
  offsetMs: number
): Promise<void> {
  await page.addInitScript((offset) => {
    const originalNow = Date.now;

    Date.now = () => originalNow() + offset;
  }, offsetMs);
}

/**
 * Get a sessionStorage value.
 */
export async function getStorageValue(
  page: Page,
  key: string
): Promise<string | null> {
  await ensureAppOrigin(page);

  return page.evaluate((storageKey) => {
    return sessionStorage.getItem(storageKey);
  }, key);
}

/**
 * Get all sale-scoped sessionStorage values for one event.
 */
export async function getSaleStorage(
  page: Page,
  eventId: number
): Promise<Record<string, string>> {
  await ensureAppOrigin(page);

  return page.evaluate((eid) => {
    const result: Record<string, string> = {};

    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i);

      if (key?.startsWith(`fs.${eid}.`)) {
        result[key] = sessionStorage.getItem(key) ?? "";
      }
    }

    return result;
  }, eventId);
}

/**
 * Clear sale-scoped sessionStorage for one event.
 */
export async function clearSaleStorage(
  page: Page,
  eventId: number
): Promise<void> {
  await ensureAppOrigin(page);

  await page.evaluate((eid) => {
    const keysToRemove: string[] = [];

    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i);

      if (key?.startsWith(`fs.${eid}.`)) {
        keysToRemove.push(key);
      }
    }

    for (const key of keysToRemove) {
      sessionStorage.removeItem(key);
    }
  }, eventId);
}

/**
 * Generic API helper using the browser's current origin.
 *
 * This is important because the browser session/cookies used by the app
 * are tied to the frontend origin.
 */
export async function apiJson<T>(
  page: Page,
  path: string,
  init: RequestInit = {}
): Promise<T> {
  await ensureAppOrigin(page);

  return page.evaluate(
    async ({ path, init }) => {
      const response = await fetch(path, {
        ...init,
        headers: {
          "Content-Type": "application/json",
          ...(init.headers ?? {})
        }
      });

      const text = await response.text();

      let body: unknown = null;

      if (text) {
        try {
          body = JSON.parse(text);
        } catch {
          body = text;
        }
      }

      if (!response.ok) {
        throw new Error(
          `API ${response.status}: ${
            typeof body === "string"
              ? body
              : JSON.stringify(body)
          }`
        );
      }

      return body as T;
    },
    {
      path,
      init: {
        method: init.method,
        headers: init.headers,
        body: init.body
      }
    }
  );
}

/**
 * Read the current sale state.
 */
export type SaleStateResponse = {
  eventId: number;
  windowStatus: string;
  serverTime: string;
  queue: {
    state: string;
    position: number | null;
    estWaitSeconds: number | null;
    admissionExpiresAt: string | null;
    passToken: string | null;
  } | null;
  hold: {
    holdToken: string;
    tierId: number;
    quantity: number;
    expiresAt: string;
    ttlRemainingSeconds: number;
  } | null;
  order: {
    orderNumber: string;
    status: string;
  } | null;
  partial: string[];
};

export async function getSaleState(
  page: Page,
  eventId: number
): Promise<SaleStateResponse> {
  return apiJson<SaleStateResponse>(
    page,
    `/api/v1/sale/${eventId}/state`
  );
}

/**
 * Join a queue for a specific sale.
 */
export type JoinQueueResponse = {
  phase: string;
  position: number | null;
  aheadOfYou: number | null;
  estWaitSeconds: number | null;
  passToken: string | null;
  admissionExpiresAt: string | null;
  serverTime: string;
};

export async function joinSale(
  page: Page,
  eventId: number
): Promise<JoinQueueResponse> {
  return apiJson<JoinQueueResponse>(
    page,
    "/api/v1/queue/join",
    {
      method: "POST",
      body: JSON.stringify({ eventId })
    }
  );
}

/**
 * Wait until a sale exposes a queue pass.
 */
export async function waitForPromotion(
  page: Page,
  eventId: number,
  timeoutMs = 15000
): Promise<string> {
  let passToken: string | null = null;

  await waitFor(
    async () => {
      const state = await getSaleState(page, eventId);

      passToken = state.queue?.passToken ?? null;

      return Boolean(passToken);
    },
    timeoutMs
  );

  if (!passToken) {
    throw new Error(`Sale ${eventId} was not promoted in time.`);
  }

  return passToken;
}

/**
 * Exchange a queue pass for an admission token.
 */
export type AdmitResponse = {
  admissionToken: string;
  expiresAt: string;
  serverTime: string;
};

export async function admitSale(
  page: Page,
  eventId: number,
  passToken?: string
): Promise<AdmitResponse> {
  if (!passToken) {
    passToken = await waitForPromotion(page, eventId);
  }

  const response = await apiJson<AdmitResponse>(
    page,
    "/api/v1/queue/admit",
    {
      method: "POST",
      headers: {
        "X-Queue-Pass-Token": passToken
      },
      body: JSON.stringify({ eventId })
    }
  );

  await page.evaluate(
    ({ eventId, admissionToken }) => {
      localStorage.setItem(
        `fs.${eventId}.admissionToken`,
        admissionToken
      );
    },
    {
      eventId,
      admissionToken: response.admissionToken
    }
  );

  return response;
}

/**
 * Explicitly store an admission token in the correct namespace.
 */
export async function putAdmissionToken(
  page: Page,
  eventId: number,
  token: string
): Promise<void> {
  await ensureAppOrigin(page);

  await page.evaluate(
    ({ eventId, token }) => {
      localStorage.setItem(
        `fs.${eventId}.admissionToken`,
        token
      );
    },
    { eventId, token }
  );
}

/**
 * Open the seat-selection route for a sale.
 */
export async function openSelectionPage(
  page: Page,
  eventId: number
): Promise<void> {
  await page.goto(`/events/${eventId}`);

  await page.getByRole("heading", {
    name: "Choose your seats"
  }).waitFor();
}

/**
 * Reserve one ticket from the first selectable tier.
 */
export async function reserveOneSeat(
  page: Page,
  tierName: string
): Promise<void> {
  const tierButton = page.getByRole("button", {
    name: new RegExp(tierName, "i")
  });

  await tierButton.click();

  await page.getByRole("button", {
    name: "Reserve seats"
  }).click();

  await page.getByRole("heading", {
    name: "Complete your purchase"
  }).waitFor();
}

/**
 * Generic async polling helper.
 */
export async function waitFor(
  condition: () => Promise<boolean>,
  timeoutMs = 10_000,
  intervalMs = 100
): Promise<void> {
  const start = Date.now();

  while (Date.now() - start < timeoutMs) {
    if (await condition()) {
      return;
    }

    await new Promise<void>((resolve) =>
      setTimeout(resolve, intervalMs)
    );
  }

  throw new Error(
    `Condition not met after ${timeoutMs}ms`
  );
}