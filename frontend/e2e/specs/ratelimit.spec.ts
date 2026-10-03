import { expect, test } from "../support/fixtures";

const limited = {
  status: 429,
  contentType: "application/problem+json",
  headers: { "Retry-After": "1" },
  body: JSON.stringify({
    type: "about:blank",
    title: "Too Many Requests",
    status: 429,
    detail: "We're handling a lot of traffic right now. Please try again in a moment.",
    code: "RATE_LIMITED",
    retryable: true,
    retryAfterSeconds: 1
  })
};

/*
 * The one place this suite answers for the server: a real 429 needs hundreds of requests from one
 * address, which is a load test, not a browser test. What is under test is the client's backoff.
 */
test.describe("Being rate limited", () => {
  test("a join is retried after Retry-After, without the buyer pressing anything again", async ({ page, sale }) => {
    let refusals = 0;
    await page.route("**/api/v1/queue/join", async (route) => {
      if (refusals < 2) {
        refusals += 1;
        await route.fulfill(limited);
      } else {
        await route.fallback();
      }
    });

    await page.goto(`/events/${sale.eventId}`);
    await page.getByTestId("join").click();
    await expect(page.getByRole("heading", { name: /choose your seats/i })).toBeVisible({ timeout: 30_000 });
    expect(refusals).toBe(2);
  });

  test("after a handful of tries it stops, and says so without blaming the buyer", async ({ page, sale }) => {
    await page.route("**/api/v1/queue/join", (route) => route.fulfill(limited));

    await page.goto(`/events/${sale.eventId}`);
    await page.getByTestId("join").click();
    await expect(page.getByText("We're handling a lot of traffic").first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("join")).toBeEnabled();
    await expect(page.getByText(/bot|suspicious/i)).toHaveCount(0);
  });
});
