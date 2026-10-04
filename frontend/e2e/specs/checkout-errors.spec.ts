import { STUB, expect, joinAndGetTurn, pay, reserve, test } from "../support/fixtures";

/*
 * FE_SPEC §3, "Checkout error handling — one rule per code": the Pay button and the seats, for every
 * row the stub gateway can produce. The rows that need a hold's clock moved — too little time left, an
 * expiry while away — are unit-tested in checkoutErrorState.test.ts and notices.test.ts, and the server
 * side of them in HoldExpiryTimerIT and CheckoutServiceTest.
 */
test.describe("Checkout, when it does not simply succeed", () => {
  test.beforeEach(async ({ page, sale }) => {
    await joinAndGetTurn(page, sale);
    await reserve(page, sale);
  });

  test("a decline keeps the seats and Pay, says how many attempts are left, and runs out at three", async ({ page }) => {
    await pay(page, STUB.declined);
    await expect(page.getByTestId("checkout-error")).toContainText("Your card was declined");
    await expect(page.getByTestId("checkout-error")).toContainText("2 attempts left");
    await expect(page.getByTestId("pay")).toBeEnabled();
    await expect(page.getByTestId("hold-timer")).toBeVisible();

    await page.getByTestId("pay").click();
    await expect(page.getByTestId("checkout-error")).toContainText("1 attempt left");

    await page.getByTestId("pay").click();
    await expect(page.getByTestId("checkout-error")).toContainText("no payment attempts left");
    await expect(page.getByTestId("pay")).toBeDisabled();
    await expect(page.getByTestId("release")).toBeVisible();
  });

  test("a provider outage keeps the seats, uses no attempt, and lets Pay back after the pause", async ({ page }) => {
    await pay(page, STUB.outage);
    await expect(page.getByTestId("checkout-error")).toContainText("no attempt was used");
    await expect(page.getByTestId("pay")).toBeDisabled();
    await expect(page.getByTestId("pay")).toContainText("Try again in");

    await expect(page.getByTestId("pay")).toBeEnabled({ timeout: 10_000 });
    await pay(page, STUB.ok);
    await expect(page).toHaveURL(/\/orders\/TK-/, { timeout: 20_000 });
  });

  test("3-D Secure completes on the same request, with no attempt used", async ({ page }) => {
    await pay(page, STUB.threeDs);
    await expect(page).toHaveURL(/\/orders\/TK-/, { timeout: 20_000 });
    await expect(page.getByTestId("order-number")).toBeVisible();
  });

  test("releasing asks first, then says the seats went back on sale", async ({ page }) => {
    await page.getByTestId("release").click();
    await page.getByRole("button", { name: "Release seats" }).click();

    await expect(page.getByTestId("notice-RELEASED")).toBeVisible();
    await expect(page.getByRole("heading", { name: /choose your seats/i })).toBeVisible();
  });
});
