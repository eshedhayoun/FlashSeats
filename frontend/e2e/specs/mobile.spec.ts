import { buy, expect, test } from "../support/fixtures";

test("the whole journey fits a phone, with nothing scrolling sideways", async ({ page, sale }) => {
  await page.goto(`/events/${sale.eventId}`);
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(page.viewportSize()!.width);

  await buy(page, sale);
  const receiptWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(receiptWidth).toBeLessThanOrEqual(page.viewportSize()!.width);
});
