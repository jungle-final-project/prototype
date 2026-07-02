import { expect, test } from '@playwright/test';

const screenshotDir = '../../artifacts/qa/agent-as';

const beforeDecisionTicket = {
  id: 'qa-ticket-before',
  status: 'OPEN',
  analysisStatus: 'RULE_READY',
  reviewStatus: 'REQUIRED',
  supportDecision: 'NEEDS_MORE_INFO',
  riskLevel: 'MEDIUM',
  symptom: 'GPU temperature spike during gaming',
  logUploadId: 'log-upload-before',
  assignedAdminId: null,
  causeCandidates: [
    { label: 'GPU thermal throttling', confidence: 'HIGH', evidenceIds: ['gpu-temperature-95c'] }
  ],
  upgradeCandidates: [],
  adminNote: null,
  remoteSupportLink: null,
  remoteSupportStatus: null,
  visitSupportRequired: false,
  createdAt: '2026-07-02T06:30:00Z'
};

const afterDecisionTicket = {
  ...beforeDecisionTicket,
  id: 'qa-ticket-after',
  reviewStatus: 'APPROVED',
  supportDecision: 'REMOTE_POSSIBLE',
  assignedAdminId: 'admin-public-id',
  adminNote: 'Remote support link sent.',
  remoteSupportLink: 'https://support.example.test/session/qa-ticket-after',
  remoteSupportStatus: 'LINK_SENT'
};

test('captures Agent AS demo UI evidence with decision fields', async ({ page }) => {
  const consoleErrors: string[] = [];
  const apiPaths = new Set<string>();

  page.on('console', (message) => {
    if (message.type() === 'error') {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/auth/me', async (route) => {
    apiPaths.add(new URL(route.request().url()).pathname);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'admin-001',
        email: 'admin@example.com',
        role: 'ADMIN'
      })
    });
  });
  await page.route('**/api/as-tickets/qa-ticket-before', async (route) => {
    apiPaths.add(new URL(route.request().url()).pathname);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(beforeDecisionTicket) });
  });
  await page.route('**/api/as-tickets/qa-ticket-after', async (route) => {
    apiPaths.add(new URL(route.request().url()).pathname);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(afterDecisionTicket) });
  });
  await page.route('**/api/admin/as-tickets', async (route) => {
    apiPaths.add(new URL(route.request().url()).pathname);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [afterDecisionTicket], page: 0, size: 20, total: 1 })
    });
  });
  await page.route('**/api/admin/as-tickets/qa-ticket-after', async (route) => {
    apiPaths.add(new URL(route.request().url()).pathname);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(afterDecisionTicket) });
  });

  await page.goto('/support/new');
  await expect(page.getByRole('main')).toContainText('AS 접수');
  await expect(page.getByRole('main')).toContainText('최근 30분 로그 파일');
  await page.screenshot({ path: `${screenshotDir}/01-support-new.png`, fullPage: true });

  await page.goto('/support/qa-ticket-before');
  await expect(page.getByRole('main')).toContainText('RULE_READY');
  await expect(page.getByRole('main')).toContainText('NEEDS_MORE_INFO');
  await expect(page.getByRole('main')).toContainText('GPU thermal throttling');
  await expect(page.getByRole('main')).not.toContainText('undefined');
  await page.screenshot({ path: `${screenshotDir}/02-support-ticket-before-decision.png`, fullPage: true });

  await page.evaluate(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.goto('/admin/as-tickets/qa-ticket-after');
  await expect(page.getByRole('main')).toContainText('RULE_READY');
  await expect(page.getByRole('main')).toContainText('APPROVED');
  await expect(page.getByRole('main')).toContainText('REMOTE_POSSIBLE');
  await expect(page.getByRole('main')).toContainText('Remote support link sent.');
  await expect(page.getByRole('main')).not.toContainText('undefined');
  await page.screenshot({ path: `${screenshotDir}/03-admin-ticket-decision-fields.png`, fullPage: true });

  await page.evaluate(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
  await page.goto('/support/qa-ticket-after');
  await expect(page.getByRole('main')).toContainText('REMOTE_POSSIBLE');
  await expect(page.getByRole('main')).toContainText('https://support.example.test/session/qa-ticket-after');
  await page.screenshot({ path: `${screenshotDir}/04-support-ticket-after-decision.png`, fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/support/qa-ticket-after');
  await expect(page.getByRole('main')).toContainText('REMOTE_POSSIBLE');
  await expect(page.getByRole('main')).toContainText('Remote support link sent.');
  await page.screenshot({ path: `${screenshotDir}/05-mobile-ticket.png`, fullPage: true });

  expect(apiPaths).toEqual(new Set([
    '/api/as-tickets/qa-ticket-before',
    '/api/auth/me',
    '/api/admin/as-tickets/qa-ticket-after',
    '/api/as-tickets/qa-ticket-after'
  ]));
  expect(consoleErrors).toEqual([]);
});
