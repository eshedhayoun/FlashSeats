#!/usr/bin/env node

/**
 * Clean up Playwright test artifacts before running tests.
 * Removes locked test directories that cause EPERM errors on Windows.
 */

const fs = require('fs');
const path = require('path');

const testResultsDir = path.join(__dirname, '..', 'test-results');
const playwrightReportDir = path.join(__dirname, '..', 'playwright-report');

console.log('🧹 Cleaning up Playwright artifacts...');

// Remove test directories with retries
function removeDir(dir, retries = 3) {
  if (!fs.existsSync(dir)) {
    return;
  }

  for (let i = 0; i < retries; i++) {
    try {
      fs.rmSync(dir, { recursive: true, force: true });
      console.log(`✓ Removed ${path.basename(dir)}`);
      return;
    } catch (e) {
      if (i < retries - 1) {
        // Wait a bit before retrying
        const wait = Date.now() + 300;
        while (Date.now() < wait) {}
      } else {
        console.warn(`⚠ Could not remove ${path.basename(dir)}: ${e.message}`);
      }
    }
  }
}

removeDir(testResultsDir);
removeDir(playwrightReportDir);

console.log('✓ Cleanup complete\n');
