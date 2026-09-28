import { expect, type Page } from "@playwright/test";

const APP_ORIGIN = "http://localhost:5173";

async function ensureAppOrigin(page: Page): Promise<void> {
  const currentUrl = page.url();

  if (currentUrl.startsWith(APP_ORIGIN)) {
    return;
  }

  await page.goto("/");
}

export async function skewClockBy(
  page: Page,
  offsetMs: number
): Promise<void> {
  await page.addInitScript((offset) => {
    const originalNow = Date.now.bind(Date);
    Date.now = () => originalNow() + offset;
  }, offsetMs);
}

export async function getStorageValue(
  page: Page,
  key: string
): Promise<string | null> {
  await ensureAppOrigin(page);

  return page.evaluate((storageKey) => {
    return sessionStorage.getItem(storageKey);
  }, key);
}

export async function getSaleStorage(
  page: Page,
  eventId: number
): Promise<Record<string, string>> {
  await ensureAppOrigin(page);

  return page.evaluate((eid) => {
    const result: Record<string, string> = {};

    for (let i = 0; i < sessionStorage.length; i += 1) {
      const key = sessionStorage.key(i);

      if (key?.startsWith(`fs.${eid}.`)) {
        result[key] = sessionStorage.getItem(key) ?? "";
      }
    }

    return result;
  }, eventId);
}

export async function clearSaleStorage(
  page: Page,
  eventId: number
): Promise<void> {
  await ensureAppOrigin(page);

  await page.evaluate((eid) => {
    const keys: string[] = [];

    for (let i = 0; i < sessionStorage.length; i += 1) {
      const key = sessionStorage.key(i);

      if (key?.startsWith(`fs.${eid}.`)) {
        keys.push(key);
      }
    }

    for (const key of keys) {
      sessionStorage.removeItem(key);
    }
  }, eventId);
}

export async function putAdmissionToken(
  page: Page,
  eventId: number,
  token: string
): Promise<void> {
  await ensureAppOrigin(page);

  await page.evaluate(
    ({ eid, value }) => {
      localStorage.setItem(`fs.${eid}.admissionToken`, value);
    },
    { eid: eventId, value: token }
  );
}

export async function apiJson<T>(
  page: Page,
  path: string,
  init?: RequestInit
): Promise<T> {
  await ensureAppOrigin(page);

  return page.evaluate(
    async ({ requestPath, requestInit }) => {
      const response = await fetch(requestPath, requestInit);

      const text = await response.text();

      let payload: unknown = null;

      try {
        payload = text ? JSON.parse(text) : null;
      } catch {
        payload = text;
      }

      if (!response.ok) {
        throw new Error(
          `API ${response.status} ${response.statusText}: ${
            typeof payload === "string"
              ? payload
              : JSON.stringify(payload)
          }`
        );
      }

      return payload as T;
    },
    {
      requestPath: path,
      requestInit: {
        method: init?.method,
        headers: init?.headers,
        body: init?.body
      }
    }
  );
}

export type QueueStatus = {
  phase: string;
  position: number | null;
  aheadOfYou: number | null;
  estWaitSeconds: number | null;
  passToken: string | null;
  admissionExpiresAt: string | null;
  serverTime: string;
};

export async function getSaleState(
  page: Page,
  eventId: number
) {
  return apiJson<{
    eventId: number;
    windowStatus: string;
    serverTime: string;
    queue: QueueStatus | null;
    hold: unknown;
    order: unknown;
    partial: string[];
  }>(
    page,
    `/api/v1/sale/${eventId}/state`
  );
}

export async function joinSale(
  page: Page,
  eventId: number
): Promise<QueueStatus> {
  return apiJson<QueueStatus>(
    page,
    "/api/v1/queue/join",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ eventId })
    }
  );
}

export async function waitForPromotion(
  page: Page,
  eventId: number,
  timeoutMs = 20000
): Promise<QueueStatus> {
  const deadline = Date.now() + timeoutMs;
  let lastPhase = "UNKNOWN";

  while (Date.now() < deadline) {
    const state = await getSaleState(page, eventId);

    if (state.queue) {
      lastPhase = state.queue.phase;

      if (state.queue.passToken) {
        return state.queue;
      }
    }

    await page.waitForTimeout(200);
  }

  throw new Error(
    `Sale ${eventId} was not promoted within ${timeoutMs}ms; last phase=${lastPhase}`
  );
}

export async function admitSale(
  page: Page,
  eventId: number
): Promise<string> {
  const status = await waitForPromotion(page, eventId);

  if (!status.passToken) {
    throw new Error(
      `Sale ${eventId} reached ${status.phase} without a pass token`
    );
  }

  const response = await apiJson<{
    admissionToken: string;
  }>(
    page,
    "/api/v1/queue/admit",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Queue-Pass-Token": status.passToken
      },
      body: JSON.stringify({ eventId })
    }
  );

  await putAdmissionToken(
    page,
    eventId,
    response.admissionToken
  );

  return response.admissionToken;
}

export async function openSelectionPage(
  page: Page,
  eventId: number
): Promise<void> {
  await page.goto(`/events/${eventId}`);

  await expect(
    page.getByRole("heading", {
      name: "Choose your seats"
    })
  ).toBeVisible();
}

export async function reserveOneSeat(
  page: Page,
  tierName: string
): Promise<void> {
  const tierButton = page
    .getByRole("button", {
      name: new RegExp(escapeRegex(tierName), "i")
    })
    .first();

  await expect(tierButton).toBeVisible();
  await expect(tierButton).toBeEnabled();

  await tierButton.click();

  await page
    .getByRole("button", {
      name: "Reserve seats"
    })
    .click();

  await expect(
    page.getByRole("heading", {
      name: /complete your purchase/i
    })
  ).toBeVisible();
}

/**
 * Release the current hold for one sale.
 *
 * The hold token is stored in:
 * fs.{eventId}.holdToken
 */
export async function releaseHold(
  page: Page,
  eventId: number
): Promise<void> {
  await ensureAppOrigin(page);

  const holdToken = await page.evaluate((eid) => {
    return sessionStorage.getItem(`fs.${eid}.holdToken`);
  }, eventId);

  if (!holdToken) {
    return;
  }

  await apiJson<void>(
    page,
    `/api/v1/holds/${encodeURIComponent(holdToken)}`,
    {
      method: "DELETE"
    }
  );

  await page.evaluate((eid) => {
    sessionStorage.removeItem(`fs.${eid}.holdToken`);
  }, eventId);
}

export async function waitFor(
  condition: () => Promise<boolean>,
  timeoutMs = 10000,
  intervalMs = 100
): Promise<void> {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
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

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}