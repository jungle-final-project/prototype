import { expect, test, type Page } from '@playwright/test';

type BuildItem = {
  category: string;
  partId: string;
  name: string;
  manufacturer: string;
  price: number;
};

const savedBuilds = [
  {
    id: 'build-qhd-balanced',
    name: 'QHD 균형 저장 견적',
    recommendedFor: 'QHD 게임/작업',
    summary: '게임과 개발 작업을 함께 고려한 저장 견적입니다.',
    totalPrice: 2_180_000,
    confidence: 'HIGH',
    createdAt: '2026-07-03T10:20:00Z',
    warnings: [],
    evidenceIds: [],
    items: [
      item('CPU', 'part-cpu-9700x', 'AMD Ryzen 7 9700X', 'AMD', 430_000),
      item('GPU', 'part-gpu-5070', 'GeForce RTX 5070', 'NVIDIA', 960_000),
      item('MOTHERBOARD', 'part-board-b650', 'B650 WiFi 메인보드', 'ASUS', 260_000)
    ]
  },
  {
    id: 'build-workstation',
    name: '작업용 저장 견적',
    recommendedFor: '영상 편집',
    summary: 'GPU와 메모리를 높인 작업용 견적입니다.',
    totalPrice: 3_140_000,
    confidence: 'MEDIUM',
    createdAt: '2026-07-02T08:10:00Z',
    warnings: [{ code: 'PRICE', message: '예산 상단에 가까운 조합입니다.' }],
    evidenceIds: [],
    items: [
      item('GPU', 'part-gpu-5080', 'GeForce RTX 5080', 'MSI', 1_540_000),
      item('RAM', 'part-ram-64', 'DDR5 64GB Kit', 'Samsung', 320_000)
    ]
  }
];

const priceAlerts = [
  {
    partId: 'part-gpu-5070',
    partName: 'GeForce RTX 5070',
    targetPrice: 900_000,
    currentPrice: 960_000,
    status: 'ACTIVE',
    createdAt: '2026-07-03T11:00:00Z'
  },
  {
    partId: 'part-ssd-990pro',
    partName: 'Samsung 990 PRO 1TB',
    targetPrice: 190_000,
    currentPrice: 180_000,
    status: 'TRIGGERED',
    createdAt: '2026-07-01T09:00:00Z'
  }
];

function item(category: string, partId: string, name: string, manufacturer: string, price: number): BuildItem {
  return {
    category,
    partId,
    name,
    manufacturer,
    price
  };
}

async function openMyQuotesAsUser(page: Page) {
  const priceAlertRequests: unknown[] = [];
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-1004',
      email: 'user@example.com',
      name: '테스트 사용자',
      role: 'USER'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-1004',
        email: 'user@example.com',
        name: '테스트 사용자',
        role: 'USER'
      })
    });
  });
  await page.route('**/api/builds/history', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: savedBuilds, page: 0, size: 20, total: savedBuilds.length })
    });
  });
  await page.route('**/api/price-alerts', async (route) => {
    if (route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() ?? '{}');
      priceAlertRequests.push(body);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: body.partId,
          partName: '새 목표가 부품',
          targetPrice: body.targetPrice,
          currentPrice: 960_000,
          status: 'ACTIVE',
          createdAt: '2026-07-04T00:00:00Z'
        })
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: priceAlerts, page: 0, size: 20, total: priceAlerts.length })
    });
  });

  await page.goto('/my/quotes');
  return { priceAlertRequests };
}

test('shows saved quotes, actionable price alert setup, and alert progress', async ({ page }) => {
  const { priceAlertRequests } = await openMyQuotesAsUser(page);

  await expect(page.getByRole('heading', { name: '내 견적함 / 목표가 알림' })).toBeVisible();
  await expect(page.getByTestId('my-quotes-build-count')).toContainText('2개');
  await expect(page.getByTestId('my-quotes-alert-count')).toContainText('2개');
  await expect(page.getByTestId('my-quotes-achieved-count')).toContainText('1개');

  const firstBuild = page.getByTestId('saved-build-card-build-qhd-balanced');
  await expect(firstBuild).toContainText('QHD 균형 저장 견적');
  await expect(firstBuild.getByRole('link', { name: '견적 상세' })).toHaveAttribute('href', '/builds/build-qhd-balanced');
  await expect(firstBuild.getByRole('link', { name: '부품 변경' })).toHaveAttribute('href', '/builds/build-qhd-balanced/change-part');
  await firstBuild.getByRole('button', { name: '목표가 등록' }).click();

  await expect(page.getByLabel('저장 견적 부품')).toHaveValue('part-cpu-9700x');
  await page.getByLabel('목표가').fill('880000');
  await page.getByRole('button', { name: '알림 등록' }).click();

  await expect.poll(() => priceAlertRequests.length).toBe(1);
  expect(priceAlertRequests[0]).toEqual({ partId: 'part-cpu-9700x', targetPrice: 880_000 });
  await expect(page.getByText('알림 등록 완료')).toBeVisible();

  const activeAlert = page.getByTestId('price-alert-row-part-gpu-5070');
  await expect(activeAlert).toContainText('GeForce RTX 5070');
  await expect(activeAlert).toContainText('목표까지 60,000원');

  const triggeredAlert = page.getByTestId('price-alert-row-part-ssd-990pro');
  await expect(triggeredAlert).toContainText('Samsung 990 PRO 1TB');
  await expect(triggeredAlert).toContainText('목표 달성');
});

test('allows manual part id entry when the saved quote part is not listed', async ({ page }) => {
  const { priceAlertRequests } = await openMyQuotesAsUser(page);

  await page.getByRole('button', { name: '직접 입력' }).click();
  await page.getByLabel('부품 ID 직접 입력').fill('manual-part-001');
  await page.getByLabel('목표가').fill('777000');
  await page.getByRole('button', { name: '알림 등록' }).click();

  await expect.poll(() => priceAlertRequests.length).toBe(1);
  expect(priceAlertRequests[0]).toEqual({ partId: 'manual-part-001', targetPrice: 777_000 });
});
