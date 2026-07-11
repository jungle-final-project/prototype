import { expect, test } from '@playwright/test';

const profile = {
  id: 'tech-external-1',
  displayName: '최민석 기사',
  initials: '최',
  status: 'ACTIVE',
  providerType: 'EXTERNAL',
  verificationStatus: 'APPROVED',
  businessName: '민석 PC 조립',
  contactPhone: '010-0000-1004',
  serviceRegions: ['서울'],
  serviceTypes: ['FULL_SERVICE'],
  specialties: ['저소음 조립'],
  rating: 4.7,
  completedJobs: 41,
  avgResponseMinutes: 15,
  assemblyFee: 70000,
  deliveryFee: 12000,
  leadTimeDays: 2,
  partsPriceAdjustment: 0,
  sortPriority: 100,
  standardAsAccepted: true,
  seeded: true
};

const requestSummary = {
  id: 'assembly-open-1', requestNo: 'ASM-20990720-OPEN0001', status: 'OFFERED',
  serviceType: 'FULL_SERVICE', region: '서울', preferredDate: '2099-07-20',
  deliveryMethod: 'DELIVERY', estimatedPartsPrice: 1400000, itemCount: 2
};

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('buildgraph.token', 'jwt-user-token'));
  await page.route('**/api/auth/me', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'user-tech', email: 'technician@example.com', name: 'Demo Technician', role: 'USER' }) }));
});

test('submits a lightweight external technician application', async ({ page }) => {
  let submitted = false;
  await page.route('**/api/technician/profile', (route) => route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'NOT_FOUND', message: '기사 프로필 없음' }) }));
  await page.route('**/api/technician/applications', async (route) => {
    submitted = true;
    const body = route.request().postDataJSON();
    expect(body.standardAsAccepted).toBe(true);
    expect(body.serviceRegions).toContain('서울');
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...profile, status: 'INACTIVE', verificationStatus: 'PENDING' }) });
  });

  await page.goto('/technician/apply');
  await page.getByLabel('기사 활동명').fill('외부 테스트 기사');
  await page.getByLabel('연락처').fill('010-1111-2222');
  await page.getByRole('checkbox', { name: /표준 AS 정책/ }).check();
  await expect(page.getByRole('button', { name: '기사 신청 제출' })).toBeEnabled();
  await page.getByRole('button', { name: '기사 신청 제출' }).click();

  await expect(page.getByText('기사 신청이 접수되었습니다')).toBeVisible();
  expect(submitted).toBe(true);
});

test('shows matched anonymous requests and submits an external offer without contact data', async ({ page }) => {
  let ownOffer: Record<string, unknown> | null = null;
  await page.route('**/api/technician/profile', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(profile) }));
  await page.route('**/api/technician/assembly-requests?scope=OPEN**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [requestSummary], page: 0, size: 20, total: 1 }) }));
  await page.route('**/api/technician/assembly-requests/assembly-open-1**', async (route) => {
    if (route.request().method() === 'POST') {
      ownOffer = { id: 'external-offer-1', status: 'AVAILABLE', confirmedPartsPrice: 1405000, assemblyFee: 70000, deliveryFee: 12000, finalPrice: 1487000, leadTimeDays: 2, stockStatus: '재고 확인 완료' };
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...requestSummary, items: [{ partId: 'part-gpu', category: 'GPU', name: 'RTX 5070', manufacturer: 'NVIDIA', quantity: 1, unitPrice: 980000, lineTotal: 980000 }], ownOffer, contact: null, note: null }) });
  });

  await page.goto('/technician');
  await expect(page.getByText('ASM-20990720-OPEN0001')).toBeVisible();
  await page.getByText('ASM-20990720-OPEN0001').click();
  await expect(page.getByText('RTX 5070')).toBeVisible();
  await expect(page.getByText('010-1234-5678')).toHaveCount(0);
  await page.getByLabel('재고 확인 문구').fill('재고 확인 완료');
  await page.getByRole('button', { name: '제안 제출' }).click();
  await expect(page.getByText('AVAILABLE')).toBeVisible();
});

test('reveals contact only for the selected and paid external technician', async ({ page }) => {
  await page.route('**/api/technician/profile', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(profile) }));
  await page.route('**/api/technician/assembly-requests/assembly-selected-1', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
    ...requestSummary,
    id: 'assembly-selected-1', status: 'MATCHED', paymentStatus: 'PAID',
    items: [{ partId: 'part-gpu', category: 'GPU', name: 'RTX 5070', manufacturer: 'NVIDIA', quantity: 1, unitPrice: 980000, lineTotal: 980000 }],
    ownOffer: { id: 'external-offer-1', status: 'SELECTED', confirmedPartsPrice: 1405000, assemblyFee: 70000, deliveryFee: 12000, finalPrice: 1487000, leadTimeDays: 2, stockStatus: '재고 확인 완료' },
    contact: { name: '데모 사용자', phone: '010-1234-5678', postalCode: '06236', addressLine1: '서울시 강남구 테헤란로 1', addressLine2: '101호' },
    note: '선정리 요청'
  }) }));

  await page.goto('/technician/requests/assembly-selected-1');
  await expect(page.getByText('010-1234-5678')).toBeVisible();
  await expect(page.getByText(/서울시 강남구 테헤란로 1/)).toBeVisible();
  await expect(page.getByRole('button', { name: '제안 수정' })).toHaveCount(0);
});

test('keeps selected jobs visible after an approved technician is suspended', async ({ page }) => {
  await page.route('**/api/technician/profile', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ ...profile, status: 'SUSPENDED' })
  }));
  await page.route('**/api/technician/assembly-requests?scope=SELECTED**', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ items: [{ ...requestSummary, id: 'assembly-selected-2', status: 'MATCHED', ownOfferStatus: 'SELECTED', paymentStatus: 'PAID' }], page: 0, size: 20, total: 1 })
  }));

  await page.goto('/technician/jobs');

  await expect(page.getByText('ASM-20990720-OPEN0001')).toBeVisible();
  await expect(page.getByRole('heading', { name: '선택된 작업' })).toBeVisible();
});
