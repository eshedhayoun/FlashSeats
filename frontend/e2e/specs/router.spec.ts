import { buy, expect, test } from "../support/fixtures";

/*
 * FE_SPEC §1: the router is an ordered list. The rows reachable through the public API are here; the
 * rest of the order — a hold outranking a closed window, sold out above closed — is pinned by
 * routeFor.test.ts, and the server's half by QueueLifecycleIT and SalePauseIT.
 */
test.describe("Which view the server's state chooses", () => {
  test("a confirmed order sits below the queue states: buying more shows the sale, not the old receipt", async ({ page, sale }) => {
    const orderNumber = await buy(page, sale);

    await page.goto(`/events/${sale.eventId}?buyMore=1`);
    await expect(page.getByTestId("join")).toBeVisible();

    await page.goto(`/events/${sale.eventId}`);
    await expect(page).toHaveURL(new RegExp(`/orders/${orderNumber}`));
  });

  test("a path below the event is still the server's view, not a page of its own", async ({ page, sale }) => {
    await page.goto(`/events/${sale.eventId}/checkout`);
    await expect(page.getByTestId("join")).toBeVisible();
  });
});
