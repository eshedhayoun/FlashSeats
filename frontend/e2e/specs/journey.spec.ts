import { money } from "../support/backend";
import { STUB, buy, expect, joinAndGetTurn, pay, reserve, test } from "../support/fixtures";

test.describe("The journey", () => {
  test("a buyer goes from the event page to a downloaded ticket", async ({ page, sale }) => {
    await page.goto(`/events/${sale.eventId}`);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(sale.title);

    await page.getByTestId("join").click();
    await expect(page.getByRole("heading", { name: /choose your seats/i })).toBeVisible({ timeout: 30_000 });

    await page.getByTestId(`tier-${sale.tier.tierId}`).click();
    await page.getByLabel("One more ticket").click();
    const total = money(sale.tier.priceCents * 2, sale.tier.currency);
    await expect(page.getByTestId("selection-total")).toHaveText(total);
    await page.getByTestId("reserve").click();

    await expect(page.getByTestId("hold-timer")).toBeVisible();
    await expect(page.getByTestId("checkout-total")).toHaveText(total);
    await pay(page, STUB.ok, "buyer@example.com");

    await expect(page).toHaveURL(/\/orders\/TK-[^?]+$/); // no receipt token in a URL the app chose (FE_SPEC §3.1)
    await expect(page.getByRole("heading", { level: 1 })).toContainText(sale.title);
    await expect(page.getByTestId("order-email")).toHaveText("buyer@example.com");

    const download = page.waitForEvent("download");
    await page.getByTestId("download-ticket").click();
    expect((await download).suggestedFilename()).toMatch(/^TK-.+\.pdf$/);
  });

  test("the ticket is listed under My tickets, across a new page load", async ({ page, sale }) => {
    const orderNumber = await buy(page, sale);

    await page.goto("/#tickets");
    const tickets = page.getByRole("list", { name: "Your tickets" });
    await expect(tickets).toContainText(sale.title);
    await expect(tickets).toContainText(orderNumber);

    await tickets.getByRole("link", { name: "View tickets" }).first().click();
    await expect(page.getByTestId("order-number")).toHaveText(orderNumber);
  });

  test("an invalid email is caught before anything is sent", async ({ page, sale }) => {
    await joinAndGetTurn(page, sale);
    await reserve(page, sale);

    await page.getByTestId("email").fill("buyer@@example");
    await page.getByTestId("pay").click();

    await expect(page.getByText("Enter an email address like name@example.com.")).toBeVisible();
    await expect(page.getByTestId("hold-timer")).toBeVisible();
  });
});
