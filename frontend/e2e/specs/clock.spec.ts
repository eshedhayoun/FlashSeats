import { expect, joinAndGetTurn, reserve, test } from "../support/fixtures";

/** FE_SPEC §0 rule 1: every countdown runs on the server's clock, whatever the device says. */
test("a device clock four minutes fast still sees the whole reservation", async ({ page, sale }) => {
  const realNow = Date.now();
  await page.clock.install({ time: new Date(realNow + 4 * 60_000) });

  await joinAndGetTurn(page, sale);
  await reserve(page, sale);

  const shown = (await page.getByTestId("hold-timer").getByRole("timer").textContent()) ?? "";
  const [minutes, seconds] = shown.split(":").map(Number);
  const remaining = minutes * 60 + seconds;
  // The hold lasts five minutes on the server. On the device's clock it would read about one.
  expect(remaining).toBeGreaterThan(4 * 60);
});
