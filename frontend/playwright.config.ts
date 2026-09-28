import { defineConfig, devices } from "@playwright/test";
import os from "node:os";
import path from "node:path";

const playwrightOutputDir = path.join(
  os.tmpdir(),
  "flashseats-playwright",
  "test-results"
);

const playwrightReportDir = path.join(
  os.tmpdir(),
  "flashseats-playwright",
  "playwright-report"
);

export default defineConfig({
  testDir: "./e2e",

  fullyParallel: false,

  forbidOnly: !!process.env.CI,

  retries: process.env.CI ? 2 : 0,

  workers: 1,

  reporter: [
    ["line"],
    [
      "html",
      {
        outputFolder: playwrightReportDir,
        open: "never"
      }
    ]
  ],

  use: {
    baseURL: "http://localhost:5173",
    httpCredentials: undefined,
    trace: "on-first-retry"
  },

  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"]
      }
    }
  ],

  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: !process.env.CI,
    timeout: 120 * 1000
  },

  outputDir: playwrightOutputDir,

  timeout: 30 * 1000,

  expect: {
    timeout: 5 * 1000
  }
});