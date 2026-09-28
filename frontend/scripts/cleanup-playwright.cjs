#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");

const playwrightRoot = path.join(
  os.tmpdir(),
  "flashseats-playwright"
);

const testResultsDir = path.join(
  playwrightRoot,
  "test-results"
);

const playwrightReportDir = path.join(
  playwrightRoot,
  "playwright-report"
);

console.log("🧹 Cleaning up Playwright artifacts...");

function removeDir(dir) {
  if (!fs.existsSync(dir)) {
    console.log(`✓ ${dir} does not exist`);
    return;
  }

  try {
    fs.rmSync(dir, {
      recursive: true,
      force: true,
      maxRetries: 20,
      retryDelay: 500
    });

    console.log(`✓ Removed ${dir}`);
  } catch (error) {
    console.warn(
      `⚠ Could not completely remove ${dir}: ${error.message}`
    );
  }
}

removeDir(testResultsDir);
removeDir(playwrightReportDir);

console.log("✓ Cleanup complete\n");