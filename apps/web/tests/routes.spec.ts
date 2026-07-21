import { expect, test } from '@playwright/test';

const routes = [
  '/',
  '/requirements/new',
  '/builds/latest',
  '/builds/00000000-0000-4000-8000-000000002001',
  '/self-quote',
  '/checkout',
  '/checkout/offers/00000000-0000-4000-8000-000000020001',
  '/checkout/payment/00000000-0000-4000-8000-000000020001',
  '/checkout/complete/00000000-0000-4000-8000-000000020001',
  '/parts',
  '/parts/00000000-0000-4000-8000-000000013001',
  '/builds/00000000-0000-4000-8000-000000002001/change-part',
  '/my/quotes',
  '/my/assembly-requests',
  '/my/assembly-requests/00000000-0000-4000-8000-000000020001',
  '/technician/apply',
  '/technician',
  '/technician/jobs',
  '/technician/requests/00000000-0000-4000-8000-000000020001',
  '/support/new',
  '/support/ai-chat',
  '/support/00000000-0000-4000-8000-000000006001',
  '/login',
  '/signup',
  '/admin',
  '/admin/agent-sessions',
  '/admin/agent-sessions/00000000-0000-4000-8000-000000003001',
  '/admin/tool-invocations',
  '/admin/tool-invocations/00000000-0000-4000-8000-000000005002',
  '/admin/rag-evidence',
  '/admin/rag-evidence/00000000-0000-4000-8000-000000004001',
  '/admin/parts',
  '/admin/assembly',
  '/admin/price-jobs',
  '/admin/build-graph-layouts',
  '/admin/load-tests',
  '/admin/support-chat-sessions',
  '/admin/as-tickets',
  '/admin/as-tickets/00000000-0000-4000-8000-000000006001'
];

for (const route of routes) {
  test(`renders ${route}`, async ({ page }) => {
    await page.goto(route);
    await expect(page.getByRole('link', { name: '다짜줘 홈' })).toBeVisible();
    await expect(page.getByRole('main')).toBeVisible();
  });
}

test('keeps populated change-part layout inside a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript(() => localStorage.setItem('buildgraph.token', 'jwt-user-token'));
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'user-001', email: 'user@example.com', role: 'USER' }) });
  });
  await page.route('**/api/builds/build-mobile', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'build-mobile', name: '모바일 비교 견적', totalPrice: 2000000, items: [] }) });
  });
  await page.route('**/api/parts?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [{ id: 'part-gpu-mobile', category: 'GPU', name: 'GeForce RTX 5080 Mobile Layout Fixture', manufacturer: 'NVIDIA', price: 1900000, attributes: {} }],
        page: 0,
        size: 12,
        total: 1
      })
    });
  });

  await page.goto('/builds/build-mobile/change-part');
  await expect(page.getByText('GeForce RTX 5080 Mobile Layout Fixture')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
});
