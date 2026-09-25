#!/usr/bin/env node

/**
 * Clean up Playwright test artifacts before running tests.
 * Kills any lingering Node/Chrome processes and removes locked directories.
 * This prevents "EPERM: operation not permitted" errors on Windows.
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const testResultsDir = path.join(__dirname, '..', 'test-results');
const playwrightReportDir = path.join(__dirname, '..', 'playwright-report');

console.log('🧹 Cleaning up Playwright artifacts...');

// Kill Node and Chrome processes
try {
  if (process.platform === 'win32') {
    execSync('taskkill /F /IM node.exe /T', { stdio: 'ignore' });
    execSync('taskkill /F /IM chrome.exe /T', { stdio: 'ignore' });
    execSync('taskkill /F /IM msedge.exe /T', { stdio: 'ignore' });
  } else {
    execSync('pkill -9 node', { stdio: 'ignore' });
    execSync('pkill -9 chrome', { stdio: 'ignore' });
  }
} catch (e) {
  // Ignore errors if processes don't exist
}

// Wait a bit for processes to fully terminate
console.log('⏳ Waiting for processes to terminate...');
setTimeout(() => {
  // Remove test directories
  [testResultsDir, playwrightReportDir].forEach(dir => {
    if (fs.existsSync(dir)) {
      try {
        fs.rmSync(dir, { recursive: true, force: true });
        console.log(`✓ Removed ${path.basename(dir)}`);
      } catch (e) {
        console.warn(`⚠ Could not remove ${path.basename(dir)}: ${e.message}`);
      }
    }
  });

  console.log('✓ Cleanup complete\n');
}, 2000);
