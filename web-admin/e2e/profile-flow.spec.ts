import { expect, test } from '@playwright/test';

const ADMIN_USER = process.env['E2E_ADMIN_USER'] ?? 'admin';
const ADMIN_PASSWORD = process.env['E2E_ADMIN_PASSWORD'] ?? 'admin123';
const BASE_API = 'http://localhost:8080';

const pendingAdminTokens: string[] = [];

function trackAdminToken(token: string | undefined | null) {
  if (token) pendingAdminTokens.push(token);
}

test.afterEach(async ({ page, request }) => {
  const tokens = pendingAdminTokens.splice(0, pendingAdminTokens.length);
  for (const token of tokens) {
    try {
      await request.post(`${BASE_API}/api/auth/logout`, {
        headers: { Authorization: `Bearer ${token}` },
      });
    } catch {
      // best-effort
    }
  }
  try {
    const logoutBtn = page.getByRole('button', { name: 'Log out' });
    if (await logoutBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
      await logoutBtn.click({ timeout: 2000 }).catch(() => {});
      await page.waitForURL(/\/login$/, { timeout: 2000 }).catch(() => {});
    }
  } catch {
    // ignore
  }
});

async function uiLogin(page: import('@playwright/test').Page, identifier: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Username or email').fill(identifier);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test('admin can view and edit own profile, email, and password', async ({ page, request }) => {
  const suffix = Date.now().toString(36);
  const newEmail = `admin-e2e-${suffix}@example.com`;
  const newPassword = `e2e-new-${suffix}`;
  let passwordChanged = false;
  let emailChanged = false;

  try {
    await uiLogin(page, ADMIN_USER, ADMIN_PASSWORD);
    await expect(page).toHaveURL(/\/$/);

    // Navigation to Profile
    await page.getByRole('link', { name: 'Profile', exact: true }).click();
    await expect(page).toHaveURL(/\/profile$/);
    await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible();
    await expect(page.getByText(ADMIN_USER, { exact: true }).first()).toBeVisible();
    // Username and role are read-only text, not inputs
    await expect(page.locator('input#profile-displayName')).toBeVisible();

    // Edit profile: displayName and bio
    const displayName = `E2E Admin ${suffix}`;
    await page.getByLabel('Display name').fill(displayName);
    await page.getByLabel('Bio').fill(`bio-${suffix}`);
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByText('Profile updated.')).toBeVisible();
    // Verify the form still shows the saved value without a reload (reload would clear in-memory tokens)
    await expect(page.getByLabel('Display name')).toHaveValue(displayName);

    // Clear a field (avatarUrl) → should persist as cleared (empty)
    await page.getByLabel('Avatar URL').fill('');
    // Input is already dirty after fill; save
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByText('Profile updated.')).toBeVisible();

    // Change email (self-only PATCH)
    await page.locator('#profile-email').fill(newEmail);
    await page.getByRole('button', { name: 'Update email' }).click();
    await expect(page.getByText('Email updated.')).toBeVisible();
    emailChanged = true;
    // Account section should show new email
    await expect(page.getByText(newEmail)).toBeVisible();

    // Change password (self-only PATCH) — require current password
    await page.getByLabel('Current password').fill(ADMIN_PASSWORD);
    await page.getByLabel('New password').fill(newPassword);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByText('Password updated.')).toBeVisible();
    passwordChanged = true;

    // Logout and login with new password
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await uiLogin(page, ADMIN_USER, newPassword);
    await expect(page).toHaveURL(/\/$/);
  } finally {
    // Best-effort restore even when the test fails after the mutation.
    // Use the new password if it was changed, otherwise the original.
    const currentPassword = passwordChanged ? newPassword : ADMIN_PASSWORD;
    try {
      const loginRes = await request.post(`${BASE_API}/api/auth/login`, {
        data: { identifier: ADMIN_USER, password: currentPassword, clientPlatform: 'WEB' },
      });
      if (loginRes.ok()) {
        const { accessToken } = await loginRes.json();
        trackAdminToken(accessToken);
        const meLookup = await request.get(`${BASE_API}/api/users/lookup?username=${ADMIN_USER}`, {
          headers: { Authorization: `Bearer ${accessToken}` },
        });
        if (meLookup.ok()) {
          const me = await meLookup.json();
          if (passwordChanged) {
            try {
              await request.patch(`${BASE_API}/api/users/${me.userId}/password`, {
                headers: { Authorization: `Bearer ${accessToken}` },
                data: { currentPassword: newPassword, newPassword: ADMIN_PASSWORD },
              });
            } catch {
              // ignore
            }
          }
          if (emailChanged) {
            try {
              await request.patch(`${BASE_API}/api/users/${me.userId}/email`, {
                headers: { Authorization: `Bearer ${accessToken}` },
                data: { email: 'admin@example.com' },
              });
            } catch {
              // ignore
            }
          }
        }
        // Explicit logout for this restoration login; afterEach will also handle it.
        try {
          await request.post(`${BASE_API}/api/auth/logout`, {
            headers: { Authorization: `Bearer ${accessToken}` },
          });
          const idx = pendingAdminTokens.indexOf(accessToken);
          if (idx !== -1) pendingAdminTokens.splice(idx, 1);
        } catch {
          // afterEach will retry
        }
      }
    } catch {
      // ignore
    }
    // Ensure the UI is logged out so afterEach does not leave a session.
    try {
      const logoutBtn = page.getByRole('button', { name: 'Log out' });
      if (await logoutBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
        await logoutBtn.click({ timeout: 2000 }).catch(() => {});
        await page.waitForURL(/\/login$/, { timeout: 2000 }).catch(() => {});
      }
    } catch {
      // ignore
    }
  }

  // Finally ensure old credentials still work for next runs (outside finally so failure is visible)
  await page.goto('/login');
  await page.getByLabel('Username or email').fill(ADMIN_USER);
  await page.getByLabel('Password').fill(ADMIN_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/$/);
});

test('profile page is protected and requires admin session', async ({ page }) => {
  await page.goto('/profile');
  await expect(page).toHaveURL(/\/login$/);
});

test('profile page does not expose editable userId', async ({ page }) => {
  await uiLogin(page, ADMIN_USER, ADMIN_PASSWORD);
  await expect(page).toHaveURL(/\/$/);
  await page.getByRole('link', { name: 'Profile', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible();
  // No input with name userId should exist
  await expect(page.locator('input[name="userId"]')).toHaveCount(0);
  await expect(page.locator('[data-testid="userId-input"]')).toHaveCount(0);
});
