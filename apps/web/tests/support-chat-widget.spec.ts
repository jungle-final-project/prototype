import { expect, test, type Page } from '@playwright/test';

test('global support chat guides logged-in users without a ticket to support intake', async ({ page }) => {
  await mockLoggedInUser(page);
  await mockEmptyChat(page);

  await page.goto('/');

  await expect(page.getByRole('button', { name: '상담방 열기' })).toBeVisible();
  await page.getByRole('button', { name: '상담방 열기' }).click();
  await expect(page.getByText('AS 티켓이 필요합니다.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'AS 접수로 이동' })).toHaveAttribute('href', '/support/new');
});

test('global support chat can send a message when a ticket chat session exists', async ({ page }) => {
  let postPayload: unknown = null;
  await mockLoggedInUser(page);
  await mockActiveChat(page, () => postPayload, (payload) => {
    postPayload = payload;
  });

  await page.goto('/support/00000000-0000-4000-8000-000000006001');
  await page.getByRole('button', { name: '상담방 열기' }).click();

  await expect(page.getByText('상담방이 생성되었습니다. 문의 내용을 남기면 담당자가 확인합니다.')).toBeVisible();
  await page.getByPlaceholder('메시지를 입력하세요').fill('지금 상담 가능할까요?');
  await page.getByRole('button', { name: '전송' }).click();

  await expect.poll(() => postPayload).toEqual({ content: '지금 상담 가능할까요?' });
  await expect(page.getByText('지금 상담 가능할까요?')).toBeVisible();
});

test('global support chat stays hidden on support intake', async ({ page }) => {
  await mockLoggedInUser(page);
  await mockEmptyChat(page);

  await page.goto('/support/new');

  await expect(page.getByRole('button', { name: '상담방 열기' })).toHaveCount(0);
});

test('global support chat stays hidden for admin users on the shopping home', async ({ page }) => {
  let supportChatCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: '00000000-0000-4000-8000-000000000001',
      email: 'admin@example.com',
      name: 'BuildGraph Admin',
      role: 'ADMIN'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000000001',
        email: 'admin@example.com',
        name: 'BuildGraph Admin',
        role: 'ADMIN'
      })
    });
  });
  await page.route('**/api/support/chat-sessions/current**', async (route) => {
    supportChatCalls += 1;
    await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
  });
  await page.route('**/api/quote-drafts/current', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: null, status: 'EMPTY', name: '빈 견적', items: [], totalPrice: 0, itemCount: 0 })
    });
  });

  await page.goto('/');

  await expect(page.getByRole('button', { name: '상담방 열기' })).toHaveCount(0);
  expect(supportChatCalls).toBe(0);
});

async function mockLoggedInUser(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: '00000000-0000-4000-8000-000000001004',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001004',
        email: 'user@example.com',
        name: 'Demo User',
        role: 'USER'
      })
    });
  });
  await page.route('**/api/quote-drafts/current', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: null, status: 'EMPTY', name: '빈 견적', items: [], totalPrice: 0, itemCount: 0 })
    });
  });
}

async function mockEmptyChat(page: Page) {
  await page.route('**/api/support/chat-sessions/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        contact: null,
        messages: [],
        supportNewPath: '/support/new',
        pollingIntervalMs: 5000
      })
    });
  });
}

async function mockActiveChat(
  page: Page,
  postPayload: () => unknown,
  setPostPayload: (payload: unknown) => void
) {
  const initial = {
    contact: {
      id: '00000000-0000-4000-8000-000000009001',
      asTicketId: '00000000-0000-4000-8000-000000006001',
      status: 'ACTIVE',
      ticketStatus: 'OPEN',
      title: 'AS 상담방',
      symptom: 'GPU 온도 상승',
      userUnreadCount: 0,
      adminUnreadCount: 0,
      canSendMessage: true
    },
    messages: [
      {
        id: '00000000-0000-4000-8000-000000009101',
        role: 'SYSTEM',
        content: '상담방이 생성되었습니다. 문의 내용을 남기면 담당자가 확인합니다.',
        createdAt: '2026-07-06T10:00:00Z'
      }
    ],
    pollingIntervalMs: 5000
  };
  await page.route('**/api/as-tickets/00000000-0000-4000-8000-000000006001', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000006001',
        status: 'OPEN',
        symptom: 'GPU 온도 상승',
        supportChatRoomId: '00000000-0000-4000-8000-000000009001',
        causeCandidates: [],
        upgradeCandidates: []
      })
    });
  });
  await page.route('**/api/support/chat-sessions/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(initial) });
  });
  await page.route('**/api/support/chat-sessions/00000000-0000-4000-8000-000000009001', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(initial) });
      return;
    }
    await route.fallback();
  });
  await page.route('**/api/support/chat-sessions/00000000-0000-4000-8000-000000009001/messages', async (route) => {
    if (route.request().method() === 'POST') {
      const payload = route.request().postDataJSON();
      setPostPayload(payload);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ...initial,
          messages: [
            ...initial.messages,
            {
              id: '00000000-0000-4000-8000-000000009102',
              role: 'USER',
              content: String((postPayload() as { content?: string } | null)?.content ?? ''),
              createdAt: '2026-07-06T10:01:00Z'
            }
          ]
        })
      });
      return;
    }
    await route.fallback();
  });
}
