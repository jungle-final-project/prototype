import { expect, test } from '@playwright/test';

const adminRoutes = [
  '/admin',
  '/admin/agent-sessions/demo-session',
  '/admin/tool-invocations/tool-power-001',
  '/admin/rag-evidence/rag-psu-001',
  '/admin/parts',
  '/admin/price-jobs',
  '/admin/load-tests',
  '/admin/as-tickets',
  '/admin/as-tickets/AS-1031'
];

test('shows permission screen without calling auth/me when token is missing', async ({ page }) => {
  let authMeCalls = 0;
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('관리자 화면을 보려면 먼저 로그인해야 합니다.')).toBeVisible();
  await page.waitForTimeout(100);
  expect(authMeCalls).toBe(0);
});

for (const route of adminRoutes) {
  test(`guards ${route} when token is missing`, async ({ page }) => {
    await page.goto(route);

    await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
    await expect(page.getByText('관리자 화면을 보려면 먼저 로그인해야 합니다.')).toBeVisible();
    await expect(page.getByRole('link', { name: '로그인으로 이동' })).toBeVisible();
    await expect(page.getByRole('link', { name: '홈으로 이동' })).toBeVisible();
  });
}

test('does not expose protected admin page content without admin permission', async ({ page }) => {
  await page.goto('/admin/parts');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.locator('main')).not.toContainText('부품 DB');
  await expect(page.locator('main')).not.toContainText('가격 Job 상태');
});

test('shows permission screen when auth/me returns USER role', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'user-1004', email: 'user@example.com', role: 'USER' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('현재 로그인한 계정에는 관리자 권한이 없습니다.')).toBeVisible();
  expect(authMeCalls).toBeGreaterThan(0);
});

test('shows login-needed message when auth/me returns 401', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'invalid-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'UNAUTHORIZED', message: '로그인이 필요합니다.' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('관리자 화면을 보려면 먼저 로그인해야 합니다.')).toBeVisible();
  expect(authMeCalls).toBeGreaterThan(0);
});

test('shows permission message when auth/me returns 403', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 403,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'FORBIDDEN', message: '관리자 권한이 필요합니다.' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('현재 로그인한 계정에는 관리자 권한이 없습니다.')).toBeVisible();
  expect(authMeCalls).toBeGreaterThan(0);
});

test('renders admin page when auth/me returns ADMIN role', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/agent-sessions/demo-session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'demo-session',
        status: 'SUCCEEDED',
        summary: 'Agent trace completed.',
        purpose: 'BUILD_RECOMMEND',
        stateTimeline: [
          { from: null, to: 'QUEUED', actor: 'USER', at: '2026-06-29T10:00:00Z', reason: 'created' },
          { from: 'QUEUED', to: 'RUNNING', actor: 'SYSTEM', at: '2026-06-29T10:00:01Z', reason: 'started' }
        ],
        toolInvocations: [
          {
            id: 'tool-001',
            agentSessionId: 'demo-session',
            toolName: 'compatibility',
            status: 'PASS',
            confidence: 'HIGH',
            summary: 'Compatibility check passed.',
            latencyMs: 40
          }
        ],
        evidenceIds: ['rag-001']
      })
    });
  });
  await page.route('**/api/admin/rag-evidence/rag-001', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'rag-001',
        agentSessionId: 'demo-session',
        sourceId: 'internal-rule-demo',
        summary: 'Demo RAG evidence.',
        score: 0.91,
        metadata: { sourceType: 'INTERNAL_RULE' }
      })
    });
  });

  await page.goto('/admin/agent-sessions/demo-session');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('body')).toContainText('Agent / RAG / Tool 근거 상세');
  await expect(page.getByRole('main')).toContainText('Agent 실행 Trace');
  await expect(page.getByRole('main')).toContainText('Compatibility check passed.');
  expect(authMeCalls).toBeGreaterThan(0);
});

test('renders eight admin shell navigation entries for ADMIN role', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 1,
        openTickets: 3,
        priceJobsRunning: 0,
        degraded: false,
        generatedAt: '2026-06-29T10:50:00Z'
      })
    });
  });
  await page.route('**/api/admin/audit-logs/recent', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [] })
    });
  });

  await page.goto('/admin');

  const navigation = page.getByRole('navigation', { name: '관리자 메뉴' });
  await expect(navigation.getByRole('link')).toHaveCount(8);
  await expect(navigation.getByRole('link', { name: '대시보드' })).toHaveAttribute('href', '/admin');
  await expect(navigation.getByRole('link', { name: 'Agent 세션' })).toHaveAttribute('href', '/admin/agent-sessions/00000000-0000-4000-8000-000000003001');
  await expect(navigation.getByRole('link', { name: 'Tool 이력' })).toHaveAttribute('href', '/admin/tool-invocations/00000000-0000-4000-8000-000000005002');
  await expect(navigation.getByRole('link', { name: 'RAG 근거' })).toHaveAttribute('href', '/admin/rag-evidence/00000000-0000-4000-8000-000000004001');
  await expect(navigation.getByRole('link', { name: '부품/가격' })).toHaveAttribute('href', '/admin/parts');
  await expect(navigation.getByRole('link', { name: 'AS 티켓' })).toHaveAttribute('href', '/admin/as-tickets');
  await expect(navigation.getByRole('link', { name: '가격 Job' })).toHaveAttribute('href', '/admin/price-jobs');
  await expect(navigation.getByRole('link', { name: '부하 테스트' })).toHaveAttribute('href', '/admin/load-tests');

  await expect(page.getByRole('searchbox', { name: '관리자 검색' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '내보내기' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '작업 실행' })).toBeDisabled();
});

test('renders price job and load test admin menu pages for ADMIN role', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });

  await page.goto('/admin/price-jobs');
  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('body')).toContainText('가격 Job 관리자');
  await expect(page.locator('main')).toContainText('가격 수집 작업');
  await expect(page.locator('main')).toContainText('네이버 쇼핑 API');
  await expect(page.locator('main')).toContainText('다나와 제한 크롤링');

  await page.goto('/admin/load-tests');
  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('body')).toContainText('부하 테스트');
  await expect(page.locator('main')).toContainText('k6 Smoke');
  await expect(page.locator('main')).toContainText('300명');
});

test('renders admin dashboard with ADMIN role and dashboard API response', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  let authMeAuthorization: string | undefined;
  await page.route('**/api/auth/me', async (route) => {
    authMeAuthorization = route.request().headers().authorization;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  let dashboardCalls = 0;
  let dashboardAuthorization: string | undefined;
  await page.route('**/api/admin/dashboard', async (route) => {
    dashboardCalls += 1;
    dashboardAuthorization = route.request().headers().authorization;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 1,
        openTickets: 3,
        priceJobsRunning: 0,
        degraded: false,
        generatedAt: '2026-06-29T10:50:00Z'
      })
    });
  });
  let auditLogCalls = 0;
  let auditLogAuthorization: string | undefined;
  await page.route('**/api/admin/audit-logs/recent', async (route) => {
    auditLogCalls += 1;
    auditLogAuthorization = route.request().headers().authorization;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            action: 'AS_TICKET_UPDATED',
            targetType: 'as_tickets',
            targetId: '4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a',
            metadata: { beforeStatus: 'OPEN', afterStatus: 'IN_PROGRESS' },
            createdAt: '2026-06-29T10:45:00Z'
          }
        ]
      })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('main')).toContainText('진행 중 Agent');
  await expect(page.locator('main')).toContainText('미해결 AS');
  await expect(page.locator('main')).toContainText('실행 중 Price Job');
  await expect(page.locator('main')).toContainText('운영 상태');
  await expect(page.locator('main')).toContainText('1건');
  await expect(page.locator('main')).toContainText('3건');
  await expect(page.locator('main')).toContainText('0건');
  await expect(page.locator('main')).toContainText('정상');
  await expect(page.locator('main')).toContainText('2026-06-29T10:50:00Z');
  await expect(page.locator('main')).toContainText('최근 Agent 세션 요약');
  await expect(page.locator('main')).toContainText('운영 작업');
  await expect(page.locator('main')).toContainText('관리자 할 일');
  await expect(page.locator('main')).toContainText('최근 관리자 작업');
  await expect(page.locator('main')).toContainText('AS_TICKET_UPDATED');
  await expect(page.locator('main')).toContainText('as_tickets');
  await expect(page.locator('main')).toContainText('4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a');
  await expect(page.locator('main')).toContainText('2026-06-29T10:45:00Z');
  await expect(page.locator('main')).toContainText('가격 Job');
  await expect(page.locator('main')).toContainText('Mailpit');
  await expect(page.locator('main')).toContainText('Mock Worker');
  await expect(page.locator('main')).toContainText('k6 Smoke');
  await expect(page.locator('main')).toContainText('부품/가격');
  await expect(page.locator('main')).toContainText('Agent/RAG');
  await expect(page.locator('main')).toContainText('AS 티켓');
  await expect(page.locator('main')).not.toContainText('undefined');
  expect(authMeAuthorization).toBe('Bearer jwt-admin-token');
  expect(dashboardCalls).toBe(1);
  expect(dashboardAuthorization).toBe('Bearer jwt-admin-token');
  expect(auditLogCalls).toBe(1);
  expect(auditLogAuthorization).toBe('Bearer jwt-admin-token');
});

test('shows degraded alert on admin dashboard when dashboard API reports degraded', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 4,
        openTickets: 7,
        priceJobsRunning: 2,
        degraded: true,
        generatedAt: '2026-06-29T11:05:00Z'
      })
    });
  });
  await page.route('**/api/admin/audit-logs/recent', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [] })
    });
  });

  await page.goto('/admin');

  await expect(page.locator('main')).toContainText('운영 상태 주의');
  await expect(page.locator('main')).toContainText('일부 운영 지표가 주의 상태입니다.');
  await expect(page.locator('main')).toContainText('4건');
  await expect(page.locator('main')).toContainText('7건');
  await expect(page.locator('main')).toContainText('2건');
  await expect(page.locator('main')).toContainText('주의');
  await expect(page.locator('main')).not.toContainText('undefined');
});

test('keeps admin dashboard usable when audit logs API fails', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 1,
        openTickets: 3,
        priceJobsRunning: 0,
        degraded: false,
        generatedAt: '2026-06-29T10:50:00Z'
      })
    });
  });
  await page.route('**/api/admin/audit-logs/recent', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '감사 로그 조회 실패' })
    });
  });

  await page.goto('/admin');

  await expect(page.locator('main')).toContainText('진행 중 Agent');
  await expect(page.locator('main')).toContainText('1건');
  await expect(page.locator('main')).toContainText('감사 로그 조회 실패');
  await expect(page.locator('main')).toContainText('최근 관리자 작업을 불러오지 못했습니다.');
  await expect(page.locator('main')).not.toContainText('undefined');
});

test('shows admin dashboard loading state while dashboard API is pending', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });

  let releaseDashboard: (() => void) | undefined;
  const dashboardReady = new Promise<void>((resolve) => {
    releaseDashboard = resolve;
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await dashboardReady;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 1,
        openTickets: 3,
        priceJobsRunning: 0,
        degraded: false,
        generatedAt: '2026-06-29T10:50:00Z'
      })
    });
  });

  await page.goto('/admin');

  await expect(page.getByText('대시보드 로딩 중')).toBeVisible();
  await expect(page.getByText('운영 지표를 불러오고 있습니다.')).toBeVisible();

  releaseDashboard?.();
  await expect(page.locator('main')).toContainText('진행 중 Agent');
  await expect(page.locator('main')).toContainText('1건');
});

test('shows admin dashboard error state when dashboard API fails', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '대시보드 조회 실패' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByText('대시보드 조회 실패')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('관리자 대시보드 API 응답을 불러오지 못했습니다.')).toBeVisible();
});
