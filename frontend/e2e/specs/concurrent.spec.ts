import { STUB, expect, joinAndGetTurn, pay, reserve, test } from "../support/fixtures";

/** FE_SPEC §0 rule 5: there is no "current event". Two sales, one buyer, nothing shared but the cookie. */
test.describe("Two sales at once", () => {
  test("holding seats in one sale and choosing in another, in two tabs, corrupts neither", async ({ page, twoSales }) => {
    const [first, second] = twoSales;

    await joinAndGetTurn(page, first);
    await reserve(page, first);

    const tab = await page.context().newPage();
    await joinAndGetTurn(tab, second);

    const keys = await page.evaluate(() => Object.keys(sessionStorage).concat(Object.keys(localStorage)));
    expect(keys).toContain(`fs.${first.eventId}.holdToken`);
    expect(keys.some((key) => key.startsWith(`fs.${second.eventId}.`))).toBe(true);
    expect(keys.filter((key) => key.startsWith("fs.") && !/^fs\.(\d+\.|recentOrders|clockOffsetMs|theme)/.test(key))).toEqual([]);

    await pay(page, STUB.ok);
    await expect(page).toHaveURL(/\/orders\/TK-/, { timeout: 20_000 });

    await tab.reload();
    await expect(tab.getByRole("heading", { name: /choose your seats/i })).toBeVisible();
  });

  test("moving between two sales in one tab keeps each one's state", async ({ page, twoSales }) => {
    const [first, second] = twoSales;

    await joinAndGetTurn(page, first);
    await reserve(page, first);

    await joinAndGetTurn(page, second);

    await page.goto(`/events/${first.eventId}`);
    await expect(page.getByTestId("hold-timer")).toBeVisible();
    await page.goto(`/events/${second.eventId}`);
    await expect(page.getByRole("heading", { name: /choose your seats/i })).toBeVisible();
  });
});
