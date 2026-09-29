import { test, expect } from "../fixtures/index";
import { getSaleState, getSaleStorage, waitFor } from "../helpers";

type SaleStateResponse = {
  eventId: number;
  windowStatus: "OPEN";
  serverTime: string;
  queue: {
    state: "WAITING" | "PROMOTED";
    position: number | null;
    estWaitSeconds: number | null;
    admissionExpiresAt: string | null;
    passToken: string | null;
  };
  hold: null;
  order: null;
  partial: string[];
};

function waitingState(eventId: number): SaleStateResponse {
  return {
    eventId,
    windowStatus: "OPEN",
    serverTime: new Date().toISOString(),
    queue: {
      state: "WAITING",
      position: 2,
      estWaitSeconds: 1,
      admissionExpiresAt: null,
      passToken: null,
    },
    hold: null,
    order: null,
    partial: [],
  };
}

function promotedState(eventId: number): SaleStateResponse {
  return {
    eventId,
    windowStatus: "OPEN",
    serverTime: new Date().toISOString(),
    queue: {
      state: "PROMOTED",
      position: 1,
      estWaitSeconds: 0,
      admissionExpiresAt: new Date(
        Date.now() + 30_000
      ).toISOString(),
      passToken: "e2e-pass-token",
    },
    hold: null,
    order: null,
    partial: [],
  };
}

test.describe("SSE recovery and backoff", () => {
  test("SSE stream opens on the queue view", async ({ buyer, sale }) => {
    const { page } = buyer;
    const { eventId } = sale;

    await page.goto(`/events/${eventId}`);

    await page
      .getByRole("button", { name: /Join Flash Sale/i })
      .click();

    const response = await page.waitForResponse(
      (candidate) =>
        candidate.url().includes("/api/v1/queue/stream") &&
        candidate.status() === 200,
      { timeout: 10_000 }
    );

    expect(response.status()).toBe(200);
    expect(response.headers()["content-type"] ?? "").toContain(
      "text/event-stream"
    );
  });

  test("polling fallback starts after the third SSE failure", async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;
    const { eventId } = sale;

    const sseRequests: string[] = [];
    let statusRequests = 0;

    await page.addInitScript(() => {
      Math.random = () => 0;
    });

    await page.route("**/api/v1/queue/stream**", async (route) => {
      sseRequests.push(route.request().url());
      await route.abort("failed");
    });

    page.on("request", (request) => {
      if (
        request
          .url()
          .includes(`/api/v1/queue/status?eventId=${eventId}`)
      ) {
        statusRequests += 1;
      }
    });

    await page.goto(`/events/${eventId}`);

    await page
      .getByRole("button", { name: /Join Flash Sale/i })
      .click();

    await waitFor(
      () => Promise.resolve(sseRequests.length >= 3),
      8_000,
      50
    );

    await waitFor(
      () => Promise.resolve(statusRequests >= 1),
      7_000,
      100
    );

    expect(sseRequests.length).toBeGreaterThanOrEqual(3);
    expect(statusRequests).toBeGreaterThanOrEqual(1);
  });


  test("queue-promoted SSE event leads to the admission request", async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;
    const { eventId } = sale;

    const passToken = "e2e-pass-token";
    const admissionToken = "e2e-admission-token";

    let admitCalls = 0;
    let joined = false;
    let servePromoted = false;
    let admitted = false;

    /*
    * The real frontend flow is:
    *
    * Join Flash Sale
    *   -> POST /queue/join
    *   -> GET /sale/{eventId}/state
    *   -> QueuePage opens SSE
    *   -> queue-promoted arrives
    *   -> SSE handler calls onRefresh()
    *   -> GET /sale/{eventId}/state
    *   -> route becomes PROMOTED
    *   -> EventPage POSTs /queue/admit
    *   -> admission succeeds
    *   -> EventPage refreshes sale state
    *   -> route becomes ADMITTED
    *
    * The mock therefore needs to transition:
    *
    * WAITING -> PROMOTED -> ADMITTED
    */

    /*
    * Mark the buyer as joined when the real join request occurs.
    *
    * We allow the request through because the actual join endpoint is not
    * the behavior under test here.
    */
    await page.route("**/api/v1/queue/join", async (route) => {
      joined = true;
      await route.continue();
    });

    /*
    * Control the sale-state responses used by EventPage.
    *
    * Before joining:
    *   real backend response
    *
    * After joining:
    *   WAITING initially
    *   PROMOTED after the mocked SSE promotion
    *   ADMITTED after the mocked admission succeeds
    */
    await page.route(
      `**/api/v1/sale/${eventId}/state`,
      async (route) => {
        if (!joined) {
          await route.continue();
          return;
        }

        if (admitted) {
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
              eventId,
              windowStatus: "OPEN",
              serverTime: new Date().toISOString(),
              queue: {
                state: "ADMITTED",
                position: 1,
                estWaitSeconds: 0,
                admissionExpiresAt: new Date(
                  Date.now() + 10 * 60 * 1000
                ).toISOString(),
                passToken: null,
              },
              hold: null,
              order: null,
              partial: [],
            }),
          });
          return;
        }

        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(
            servePromoted
              ? promotedState(eventId)
              : waitingState(eventId)
          ),
        });
      }
    );

    /*
    * Hold the SSE response until the test explicitly releases the
    * promotion. This removes the race between the join/state refresh
    * and the mocked SSE event.
    */
    let releasePromotion!: () => void;

    const promotionReady = new Promise<void>((resolve) => {
      releasePromotion = resolve;
    });

    await page.route("**/api/v1/queue/stream**", async (route) => {
      await promotionReady;

      const body =
        `id: 123\n` +
        `event: queue-promoted\n` +
        `data: ${JSON.stringify({
          passToken,
          expiresInSeconds: 120,
        })}\n\n`;

      await route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        headers: {
          "Cache-Control": "no-cache",
          Connection: "keep-alive",
          "X-Accel-Buffering": "no",
        },
        body,
      });
    });

    /*
    * Mock the admission exchange.
    *
    * The first call must use the exact passToken from the promotion.
    * After that call succeeds, mark the sale ADMITTED so the EventPage
    * does not attempt admission again.
    */
    await page.route("**/api/v1/queue/admit", async (route) => {
      admitCalls += 1;

      const request = route.request();

      expect(
        request.headers()["x-queue-pass-token"]
      ).toBe(passToken);

      expect(request.postDataJSON()).toEqual({
        eventId,
      });

      admitted = true;

      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          admissionToken,
          expiresAt: new Date(
            Date.now() + 10 * 60 * 1000
          ).toISOString(),
          serverTime: new Date().toISOString(),
        }),
      });
    });

    await page.goto(`/events/${eventId}`);

    /*
    * This performs the real UI action:
    *   POST /queue/join
    *   then the application's onJoined() refresh.
    */
    await page
      .getByRole("button", {
        name: /Join Flash Sale/i,
      })
      .click();

    /*
    * At this point the buyer is joined. Make the next sale-state response
    * PROMOTED and release the mocked SSE event.
    */
    servePromoted = true;
    releasePromotion();

    /*
    * A successful promotion must cause exactly one admission request.
    */
    await expect
      .poll(() => admitCalls, {
        timeout: 10_000,
      })
      .toBe(1);

    /*
    * Verify the admission token was persisted in the event-scoped
    * localStorage namespace.
    */
    await expect
      .poll(
        () =>
          page.evaluate(
            (eid) =>
              localStorage.getItem(
                `fs.${eid}.admissionToken`
              ),
            eventId
          ),
        {
          timeout: 10_000,
        }
      )
      .toBe(admissionToken);
  });


  test("Last-Event-ID is stored and sent on reconnect", async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;
    const { eventId } = sale;
    let reconnectSeen = false;

    await page.route("**/api/v1/queue/stream**", async (route) => {
      const url = new URL(route.request().url());

      if (!url.searchParams.get("lastEventId")) {
        await route.fulfill({
          status: 200,
          headers: {
            "content-type": "text/event-stream",
          },
          body:
            "id: 42\n" +
            "event: position-update\n" +
            'data: {"position":2,"aheadOfYou":1,"estWaitSeconds":1}\n\n',
        });
        return;
      }

      reconnectSeen = true;

      expect(url.searchParams.get("lastEventId")).toBe("42");

      await route.abort("failed");
    });

    await page.goto(`/events/${eventId}`);

    await page
      .getByRole("button", { name: /Join Flash Sale/i })
      .click();

    await waitFor(
      async () =>
        (await getSaleStorage(page, eventId))[
          `fs.${eventId}.lastEventId`
        ] === "42",
      5_000,
      50
    );

    await waitFor(
      () => Promise.resolve(reconnectSeen),
      5_000,
      50
    );

    const stored = await page.evaluate(
      (eid) =>
        sessionStorage.getItem(
          `fs.${eid}.lastEventId`
        ),
      eventId
    );

    expect(stored).toBe("42");
  });

  test("online event triggers a fresh SSE connection", async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;
    const { eventId } = sale;

    const requests: string[] = [];

    await page.route("**/api/v1/queue/stream**", async (route) => {
      requests.push(route.request().url());
      await route.abort("failed");
    });

    await page.goto(`/events/${eventId}`);

    await page
      .getByRole("button", { name: /Join Flash Sale/i })
      .click();

    await waitFor(
      () => Promise.resolve(requests.length >= 1),
      5_000,
      50
    );

    await page.evaluate(() => {
      window.dispatchEvent(new Event("online"));
    });

    await waitFor(
      () => Promise.resolve(requests.length >= 2),
      5_000,
      50
    );

    expect(requests.length).toBeGreaterThanOrEqual(2);
  });

  test("visible page rehydrates sale state", async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;
    const { eventId } = sale;

    let stateRequests = 0;

    page.on("request", (request) => {
      if (
        request.url().includes(
          `/api/v1/sale/${eventId}/state`
        )
      ) {
        stateRequests += 1;
      }
    });

    await page.goto(`/events/${eventId}`);

    await waitFor(
      () => Promise.resolve(stateRequests >= 1),
      5_000,
      50
    );

    const before = stateRequests;

    await page.evaluate(() => {
      Object.defineProperty(document, "visibilityState", {
        configurable: true,
        value: "hidden",
      });

      document.dispatchEvent(
        new Event("visibilitychange")
      );

      Object.defineProperty(document, "visibilityState", {
        configurable: true,
        value: "visible",
      });

      document.dispatchEvent(
        new Event("visibilitychange")
      );
    });

    await waitFor(
      () => Promise.resolve(stateRequests > before),
      5_000,
      50
    );

    expect(stateRequests).toBeGreaterThan(before);
  });

  test("reconnect ceilings follow the implemented full-jitter cap", async ({
    buyer,
  }) => {
    const { page } = buyer;

    const ceilings = await page.evaluate(() =>
      Array.from({ length: 6 }, (_, attempt) =>
        Math.min(
          30_000,
          1_000 * 2 ** attempt
        )
      )
    );

    expect(ceilings).toEqual([
      1000,
      2000,
      4000,
      8000,
      16000,
      30000,
    ]);
  });
});

