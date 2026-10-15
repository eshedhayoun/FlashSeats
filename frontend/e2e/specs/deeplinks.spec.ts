import { expect, test } from "../support/fixtures";

test.describe("Links that lead nowhere", () => {
  test("an unknown page", async ({ page }) => {
    await page.goto("/no-such-page");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("We couldn't find that page");
  });

  test("a malformed event id is never requested", async ({ page }) => {
    const requests: string[] = [];
    page.on("request", (request) => requests.push(request.url()));
    await page.goto("/events/NaN");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("We couldn't find that event");
    expect(requests.filter((url) => url.includes("/api/v1/events/NaN") || url.includes("/sale/NaN"))).toEqual([]);
  });

  test("an event that does not exist", async ({ page }) => {
    await page.goto("/events/987654321");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("We couldn't find that event");
  });

  test("an order that does not exist, or is not this buyer's", async ({ page }) => {
    await page.goto("/orders/TK-NOPE");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("We couldn't find that order");
  });
});
