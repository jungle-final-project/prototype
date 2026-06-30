import { expect, test } from '@playwright/test';

test('shows login API error message and does not save tokens', async ({ page }) => {
  await page.route('**/api/auth/login', async (route) => {
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      email: 'user@example.com',
      password: 'wrong-password'
    });
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'UNAUTHORIZED',
        message: '이메일 또는 비밀번호가 올바르지 않습니다.'
      })
    });
  });

  await page.goto('/login');
  await page.getByLabel('이메일').fill('user@example.com');
  await page.getByLabel('비밀번호').fill('wrong-password');
  await page.getByRole('button', { name: '로그인' }).click();

  await expect(page.getByText('이메일 또는 비밀번호가 올바르지 않습니다.')).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBeNull();
});

test('updates header from login response before auth me finishes', async ({ page }) => {
  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'jwt-fast-user',
        refreshToken: 'refresh-fast-user',
        user: {
          id: '00000000-0000-4000-8000-000000001088',
          email: 'fast@example.com',
          name: 'Fast User',
          role: 'USER'
        }
      })
    });
  });
  await page.route('**/api/auth/me', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 5_000));
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001088',
        email: 'fast@example.com',
        name: 'Fast User',
        role: 'USER'
      })
    });
  });

  await page.goto('/login?redirect=%2Fparts%2Fpart-gpu-detail-test');
  await page.getByLabel('이메일').fill('fast@example.com');
  await page.getByLabel('비밀번호').fill('passw0rd!');
  await page.getByRole('button', { name: '로그인' }).click();

  await expect(page).toHaveURL('/');
  await expect(page.getByText('로그인됨 · fast@example.com · USER')).toBeVisible({ timeout: 2_000 });
  await expect(page.getByText('Fast User')).toBeVisible({ timeout: 2_000 });
  await expect(page.getByRole('navigation').getByRole('link', { name: '관리자' })).toHaveCount(0);
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('buildgraph.authUser') ?? '{}'))).toMatchObject({
    email: 'fast@example.com',
    name: 'Fast User',
    role: 'USER'
  });
});

test('shows admin navigation only for ADMIN role', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'admin-001',
      email: 'admin@example.com',
      name: 'Admin User',
      role: 'ADMIN'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'admin-001',
        email: 'admin@example.com',
        name: 'Admin User',
        role: 'ADMIN'
      })
    });
  });

  await page.goto('/login');

  await expect(page.getByText('로그인됨 · admin@example.com · ADMIN')).toBeVisible();
  await expect(page.getByRole('navigation').getByRole('link', { name: '관리자' })).toHaveAttribute('href', '/admin');
});

test('refreshes expired access token and retries current user request', async ({ page }) => {
  let expiredMeCalls = 0;
  let refreshedMeCalls = 0;
  let refreshCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'expired-access-token');
    localStorage.setItem('buildgraph.refreshToken', 'valid-refresh-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    const authorization = route.request().headers().authorization;
    if (authorization === 'Bearer expired-access-token') {
      expiredMeCalls += 1;
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'UNAUTHORIZED',
          message: 'Access token is expired.'
        })
      });
      return;
    }

    expect(authorization).toBe('Bearer refreshed-access-token');
    refreshedMeCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001077',
        email: 'refreshed@example.com',
        name: 'Refresh User',
        role: 'USER'
      })
    });
  });
  await page.route('**/api/auth/refresh', async (route) => {
    refreshCalls += 1;
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      refreshToken: 'valid-refresh-token'
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'refreshed-access-token',
        refreshToken: 'rotated-refresh-token'
      })
    });
  });

  await page.goto('/login');

  await expect(page.getByText('refreshed@example.com')).toBeVisible();
  await expect(page.getByText('Refresh User')).toBeVisible();
  expect(expiredMeCalls).toBeGreaterThanOrEqual(1);
  expect(refreshedMeCalls).toBeGreaterThanOrEqual(1);
  expect(refreshCalls).toBe(1);
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBe('refreshed-access-token');
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBe('rotated-refresh-token');
});

test('calls logout API and clears stored auth tokens', async ({ page }) => {
  let logoutCalled = false;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'logout-access-token');
    localStorage.setItem('buildgraph.refreshToken', 'logout-refresh-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: '00000000-0000-4000-8000-000000001066',
      email: 'logout@example.com',
      name: 'Logout User',
      role: 'USER'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001066',
        email: 'logout@example.com',
        name: 'Logout User',
        role: 'USER'
      })
    });
  });
  await page.route('**/api/auth/logout', async (route) => {
    logoutCalled = true;
    expect(route.request().headers().authorization).toBe('Bearer logout-access-token');
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      refreshToken: 'logout-refresh-token'
    });
    await route.fulfill({ status: 204 });
  });

  await page.goto('/login');
  await expect(page.getByText('Logout User')).toBeVisible();
  await page.locator('header button').last().click();

  expect(logoutCalled).toBe(true);
  await page.waitForFunction(() =>
    localStorage.getItem('buildgraph.token') === null &&
    localStorage.getItem('buildgraph.refreshToken') === null &&
    localStorage.getItem('buildgraph.authUser') === null
  );
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.authUser'))).toBeNull();
  await expect(page).toHaveURL('/login');
});

test('submits signup form with the OpenAPI user payload', async ({ page }) => {
  await page.route('**/api/users', async (route) => {
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      name: '홍길동',
      email: 'new-user@example.com',
      password: 'passw0rd!',
      termsAccepted: true,
      marketingAccepted: false
    });
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001099',
        email: 'new-user@example.com',
        name: '홍길동',
        role: 'USER'
      })
    });
  });

  await page.goto('/signup');
  await page.getByLabel('이름').fill('홍길동');
  await page.getByLabel('이메일').fill('new-user@example.com');
  await page.getByLabel('비밀번호', { exact: true }).fill('passw0rd!');
  await page.getByLabel('비밀번호 확인').fill('passw0rd!');
  await page.getByLabel('서비스 이용약관 및 로그 업로드 정책 확인').check();
  await page.getByRole('button', { name: '회원가입' }).click();

  await expect(page).toHaveURL('/login');
});

test('shows signup API error message', async ({ page }) => {
  await page.route('**/api/users', async (route) => {
    await route.fulfill({
      status: 409,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'DUPLICATE_RESOURCE',
        message: '이미 가입된 이메일입니다.'
      })
    });
  });

  await page.goto('/signup');
  await page.getByLabel('이름').fill('홍길동');
  await page.getByLabel('이메일').fill('user@example.com');
  await page.getByLabel('비밀번호', { exact: true }).fill('passw0rd!');
  await page.getByLabel('비밀번호 확인').fill('passw0rd!');
  await page.getByLabel('서비스 이용약관 및 로그 업로드 정책 확인').check();
  await page.getByRole('button', { name: '회원가입' }).click();

  await expect(page.getByText('이미 가입된 이메일입니다.')).toBeVisible();
  await expect(page).toHaveURL('/signup');
});
