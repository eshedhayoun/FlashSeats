import { pause, resume } from "../support/backend";
import { buy, expect, joinAndGetTurn, reserve, test } from "../support/fixtures";

/** FE_SPEC §3, the recovery matrix: every reload point lands where the server says the buyer is. */
test.describe("Reloading at every step", () => {
  test("in line: the same place, still connected", async ({ newBuyer, sale }) => {
    await pause(sale.eventId); // nobody is let in, so the line holds still while it is measured
    const first = await newBuyer();
    const second = await newBuyer();
    try {
      for (const page of [first, second]) {
        await page.goto(`/events/${sale.eventId}`);
        await page.getByTestId("join").click();
        await expect(page.getByTestId("queue-position")).toBeVisible();
      }
      await expect(second.getByTestId("queue-position")).toHaveText("#2");

      await second.reload();
      await expect(second.getByTestId("queue-position")).toHaveText("#2");
      await expect(second.getByTestId("connection")).toHaveAttribute("data-state", "open");
      await expect(second.getByTestId("paused")).toBeVisible();
    } finally {
      await resume(sale.eventId);
    }
  });

  test("choosing seats: the turn's clock resumes rather than restarts", async ({ page, sale }) => {
    await joinAndGetTurn(page, sale);
    await page.waitForTimeout(2_000);
    await page.reload();
    await expect(page.getByRole("heading", { name: /choose your seats/i })).toBeVisible();
    const remaining = await page.getByRole("timer").first().textContent();
    expect(remaining).not.toBe("10:00");
  });

  test("at checkout: the hold, its clock and the typed email all survive", async ({ page, sale }) => {
    await joinAndGetTurn(page, sale);
    await reserve(page, sale);
    await page.getByTestId("email").fill("kept@example.com");

    await page.reload();
    await expect(page.getByTestId("hold-timer")).toBeVisible();
    await expect(page.getByTestId("email")).toHaveValue("kept@example.com");
  });

  test("after buying: the receipt, never the landing page", async ({ page, sale }) => {
    const orderNumber = await buy(page, sale);
    await page.reload();
    await expect(page.getByTestId("order-number")).toHaveText(orderNumber);

    await page.goto(`/events/${sale.eventId}`);
    await expect(page).toHaveURL(new RegExp(`/orders/${orderNumber}`));
  });

  test("a second tab of the same sale converges on the same view", async ({ page, sale }) => {
    await joinAndGetTurn(page, sale);
    const other = await page.context().newPage();
    await other.goto(`/events/${sale.eventId}`);
    await expect(other.getByRole("heading", { name: /choose your seats/i })).toBeVisible();

    await reserve(page, sale);
    await other.reload();
    await expect(other.getByTestId("hold-timer")).toBeVisible();
  });
});
