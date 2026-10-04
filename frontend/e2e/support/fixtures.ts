import { test as base, expect, type BrowserContext, type Page } from "@playwright/test";
import { openSales, type Sale } from "./backend";

type Fixtures = {
  /** An open sale with seats left. The test is skipped, saying why, when there is none. */
  sale: Sale;
  /** Two different open sales, for the concurrent-sales specs. */
  twoSales: [Sale, Sale];
  /**
   * Another buyer: a new browser context, so a new `fsid` cookie and a new identity (ADR-010). Two
   * pages in one context are two tabs of ONE buyer; conflating them tests neither (FE_SPEC §8).
   */
  newBuyer: () => Promise<Page>;
};

export const test = base.extend<Fixtures>({
  sale: async ({}, use, testInfo) => {
    const [sale] = await openSales();
    testInfo.skip(!sale, "No open sale with seats left. Run docker/scripts/dev-up.sh.");
    await use(sale);
  },
  twoSales: async ({}, use, testInfo) => {
    const sales = await openSales();
    testInfo.skip(sales.length < 2, "Needs two open sales with seats left.");
    await use([sales[0], sales[1]]);
  },
  newBuyer: async ({ browser }, use) => {
    const contexts: BrowserContext[] = [];
    await use(async () => {
      const context = await browser.newContext();
      contexts.push(context);
      return context.newPage();
    });
    await Promise.all(contexts.map((context) => context.close()));
  }
});

export { expect };

export const STUB = {
  ok: "pm_card_visa",
  declined: "pm_card_declined",
  outage: "pm_card_error",
  threeDs: "pm_card_authenticationRequired"
} as const;

/** From the event page to the buyer's turn. Promotion is a real 1 s worker: wait on the UI, never sleep. */
export async function joinAndGetTurn(page: Page, sale: Sale): Promise<void> {
  await page.goto(`/events/${sale.eventId}`);
  await page.getByTestId("join").click();
  await expect(page.getByRole("heading", { name: /choose your seats/i })).toBeVisible({ timeout: 30_000 });
}

export async function reserve(page: Page, sale: Sale, quantity = 1): Promise<void> {
  await page.getByTestId(`tier-${sale.tier.tierId}`).click();
  for (let more = 1; more < quantity; more++) await page.getByLabel("One more ticket").click();
  await page.getByTestId("reserve").click();
  await expect(page.getByTestId("hold-timer")).toBeVisible({ timeout: 20_000 });
}

export async function pay(page: Page, outcome: string = STUB.ok, email = "buyer@example.com"): Promise<void> {
  const field = page.getByTestId("email");
  if ((await field.inputValue()) !== email) await field.fill(email);
  await page.getByTestId(`stub-${outcome}`).click();
  await page.getByTestId("pay").click();
}

/** The whole way to a receipt. */
export async function buy(page: Page, sale: Sale, quantity = 1): Promise<string> {
  await joinAndGetTurn(page, sale);
  await reserve(page, sale, quantity);
  await pay(page);
  await expect(page).toHaveURL(/\/orders\/TK-/, { timeout: 20_000 });
  return (await page.getByTestId("order-number").textContent())?.trim() ?? "";
}
