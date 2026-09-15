import { defineConfig, devices } from '@playwright/test';

/**
 * Web-admin end-to-end tests.
 *
 * Prerequisites (separate terminals):
 *   1. Backend:  cd server && ./gradlew bootTestRun   (http://localhost:8080)
 *   2. Frontend: cd web-admin && npm start             (http://localhost:4200)
 *
 * The dev server is reused when already running, otherwise Playwright
 * starts it automatically.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:4200',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm start',
    url: 'http://localhost:4200/login',
    reuseExistingServer: !process.env['CI'],
    timeout: 180_000,
  },
});
