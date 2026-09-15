import { expect, test } from '@playwright/test';

/**
 * Admin-panel journeys against a local backend (fresh bootTestRun database
 * with the seeded `admin` account). Admin credentials come from the
 * environment with a local-dev default; no secrets are committed.
 */
const ADMIN_USER = process.env['E2E_ADMIN_USER'] ?? 'admin';
const ADMIN_PASSWORD = process.env['E2E_ADMIN_PASSWORD'] ?? 'admin123';
const BASE_API = 'http://localhost:8080';

async function uiLogin(
  page: import('@playwright/test').Page,
  identifier: string,
  password: string,
) {
  await page.goto('/login');
  await page.getByLabel('Username or email').fill(identifier);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test('admin login → users list → create USER → detail → delete → logout', async ({ page }) => {
  const username = `e2e${Date.now().toString(36)}`;

  await uiLogin(page, ADMIN_USER, ADMIN_PASSWORD);
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();

  await page.getByRole('link', { name: 'Users', exact: true }).click();
  await expect(page).toHaveURL(/\/users$/);
  await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();
  await expect(page.getByRole('cell', { name: ADMIN_USER, exact: true })).toBeVisible();

  await page.getByRole('link', { name: 'Create user' }).click();
  await expect(page).toHaveURL(/\/users\/new$/);
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Initial password').fill('e2e-secret-1');
  await page.getByLabel('Email (optional)').fill(`${username}@example.com`);
  await page.getByRole('button', { name: 'Create user' }).click();
  await expect(page).toHaveURL(/\/users\/[0-9a-f-]+$/);
  await expect(page.getByRole('heading', { name: username })).toBeVisible();
  await expect(page.getByText(`${username}@example.com`)).toBeVisible();

  await page.getByRole('link', { name: 'Back to users' }).click();
  await expect(page.getByRole('cell', { name: username, exact: true })).toBeVisible();

  const row = page.getByRole('row', { name: new RegExp(username) });
  await row.getByRole('button', { name: /Delete/ }).click();
  await page.getByRole('button', { name: 'Delete permanently' }).click();
  await expect(page.getByText(`Deleted user “${username}”.`)).toBeVisible();
  await expect(page.getByRole('cell', { name: username, exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/login$/);
});

test('non-ADMIN credentials are rejected at login without a session', async ({ page, request }) => {
  const username = `e2euser${Date.now().toString(36)}`;

  // Provision a USER through the API (admin setup step, asserted via UI below).
  const login = await request.post(`${BASE_API}/api/auth/login`, {
    data: { identifier: ADMIN_USER, password: ADMIN_PASSWORD, clientPlatform: 'WEB' },
  });
  expect(login.ok()).toBeTruthy();
  const { accessToken } = await login.json();
  const created = await request.post(`${BASE_API}/api/users`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { username, password: 'e2e-secret-1' },
  });
  expect(created.ok()).toBeTruthy();

  await uiLogin(page, username, 'e2e-secret-1');
  // Valid credentials, wrong audience: stay on login with a clear error.
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('alert')).toContainText(/not authorized/i);
  // No Web Admin session was established: protected routes bounce to login.
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);

  // Revoke the provisioning session so repeated runs do not accumulate
  // toward the server's five-active-session limit for the admin account.
  await request.post(`${BASE_API}/api/auth/logout`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
});

test('invalid credentials show an error and stay on login', async ({ page }) => {
  await uiLogin(page, ADMIN_USER, 'definitely-wrong-password');
  await expect(page).toHaveURL(/\/login$/);
  // The exact text is the server's 401 message (e.g. wrong-password vs
  // unknown-user); the contract is: visible error, no navigation.
  await expect(page.getByRole('alert')).toContainText(/incorrect|invalid/i);
});
