import { pause, resume } from "../support/backend";
import { expect, test } from "../support/fixtures";

test.describe("The live waiting room", () => {
  test("a pause is a banner, the place is kept, and the turn arrives on the stream when it resumes", async ({ page, sale }) => {
    await pause(sale.eventId);
    try {
      await page.goto(`/events/${sale.eventId}`);
      await expect(page.getByText("Sales are paused for a moment")).toBeVisible();
      await page.getByTestId("join").click();

      await expect(page.getByTestId("queue-position")).toHaveText("You're next");
      await expect(page.getByTestId("connection")).toHaveAttribute("data-state", "open");
      await expect(page.getByTestId("paused")).toBeVisible();
      await expect(page.getByTestId("queue-estimate")).toHaveCount(0); // no estimate while nothing moves
    } finally {
      await resume(sale.eventId);
    }
    await expect(page.getByRole("heading", { name: /choose your seats/i })).toBeVisible({ timeout: 30_000 });
  });

  test("with the stream unreachable, polling takes over and the turn is not lost", async ({ page, sale }) => {
    await page.route("**/api/v1/queue/stream**", (route) => route.abort());
    await pause(sale.eventId);
    try {
      await page.goto(`/events/${sale.eventId}`);
      await page.getByTestId("join").click();
      await expect(page.getByTestId("queue-position")).toBeVisible();
      await expect(page.getByTestId("connection")).toContainText("your place is saved", { timeout: 30_000 });
      await expect(page.getByTestId("connection")).toHaveAttribute("data-state", "polling", { timeout: 60_000 });
    } finally {
      await resume(sale.eventId);
    }
    await expect(page.getByRole("heading", { name: /choose your seats/i })).toBeVisible({ timeout: 30_000 });
  });

  test("leaving the line asks first, then says rejoining starts at the back", async ({ page, sale }) => {
    await pause(sale.eventId);
    try {
      await page.goto(`/events/${sale.eventId}`);
      await page.getByTestId("join").click();
      await expect(page.getByTestId("queue-position")).toBeVisible();

      await page.getByTestId("leave-queue").click();
      await page.getByRole("button", { name: "Leave the line" }).click();

      await expect(page.getByTestId("notice-LEFT_QUEUE")).toContainText("start at the back");
      await expect(page.getByTestId("join")).toBeEnabled();
    } finally {
      await resume(sale.eventId);
    }
  });
});
