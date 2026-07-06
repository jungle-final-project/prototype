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
  await mockOpenSupportWebSocket(page);
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
  await expect.poll(() => page.evaluate(() => (window as unknown as { __supportChatSocketSends?: string[] }).__supportChatSocketSends ?? [])).toEqual([]);
  await expect(page.getByText('지금 상담 가능할까요?')).toBeVisible();
});

test('global support chat keeps input and shows an error when REST send fails', async ({ page }) => {
  await mockLoggedInUser(page);
  await mockActiveChatWithFailedPost(page);

  await page.goto('/support/00000000-0000-4000-8000-000000006001');
  await page.getByRole('button', { name: '상담방 열기' }).click();
  await page.getByPlaceholder('메시지를 입력하세요').fill('닫힌 티켓에 보내는 메시지');
  await page.getByRole('button', { name: '전송' }).click();

  await expect(page.getByPlaceholder('메시지를 입력하세요')).toHaveValue('닫힌 티켓에 보내는 메시지');
  await expect(page.getByText('종료된 AS 티켓 상담방에는 메시지를 보낼 수 없습니다.')).toBeVisible();
});

test('global support chat limits message input to 2000 characters', async ({ page }) => {
  await mockLoggedInUser(page);
  await mockActiveChat(page, () => null, () => {});

  await page.goto('/support/00000000-0000-4000-8000-000000006001');
  await page.getByRole('button', { name: '상담방 열기' }).click();

  await expect(page.getByPlaceholder('메시지를 입력하세요')).toHaveAttribute('maxLength', '2000');
});

test('global support chat separates API failure from missing ticket empty state', async ({ page }) => {
  await mockLoggedInUser(page);
  await page.route('**/api/support/chat-sessions/current**', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '상담방을 불러오지 못했습니다.' })
    });
  });

  await page.goto('/');
  await page.getByRole('button', { name: '상담방 열기' }).click();

  await expect(page.getByText('상담방 조회 실패')).toBeVisible();
  await expect(page.getByText('AS 티켓이 필요합니다.')).toHaveCount(0);
});

test('global support chat scrolls to the newest message when opened', async ({ page }) => {
  await mockLoggedInUser(page);
  await mockLongActiveChat(page);

  await page.goto('/support/00000000-0000-4000-8000-000000006001');
  await page.getByRole('button', { name: '상담방 열기' }).click();

  const scroller = page.getByTestId('support-chat-messages');
  await expect.poll(async () => scroller.evaluate((element) => {
    return element.scrollHeight - element.clientHeight - element.scrollTop < 8;
  })).toBe(true);
});

test('global support chat closes and clears chat cache on auth change', async ({ page }) => {
  await mockLoggedInUser(page);
  await mockActiveChat(page, () => null, () => {});

  await page.goto('/support/00000000-0000-4000-8000-000000006001');
  await page.getByRole('button', { name: '상담방 열기' }).click();
  await expect(page.getByText('PC Agent 상담방')).toBeVisible();

  await page.evaluate(async () => {
    const apiModulePath = '/src/lib/api.ts';
    const { clearToken } = await import(/* @vite-ignore */ apiModulePath);
    clearToken();
  });

  await expect(page.getByText('PC Agent 상담방')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '상담방 열기' })).toHaveCount(0);
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

async function mockOpenSupportWebSocket(page: Page) {
  await page.addInitScript(() => {
    const sends: string[] = [];
    class FakeWebSocket extends EventTarget {
      static CONNECTING = 0;
      static OPEN = 1;
      static CLOSING = 2;
      static CLOSED = 3;
      readyState = FakeWebSocket.OPEN;
      url: string;

      constructor(url: string) {
        super();
        this.url = url;
        setTimeout(() => this.dispatchEvent(new Event('open')), 0);
      }

      send(payload: string) {
        sends.push(payload);
      }

      close() {
        this.readyState = FakeWebSocket.CLOSED;
        this.dispatchEvent(new Event('close'));
      }
    }
    (window as unknown as { WebSocket: typeof WebSocket }).WebSocket = FakeWebSocket as unknown as typeof WebSocket;
    (window as unknown as { __supportChatSocketSends?: string[] }).__supportChatSocketSends = sends;
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

async function mockLongActiveChat(page: Page) {
  const messages = Array.from({ length: 36 }, (_, index) => ({
    id: `00000000-0000-4000-8000-00000001${String(index).padStart(4, '0')}`,
    role: index === 0 ? 'SYSTEM' : index % 2 === 0 ? 'USER' : 'ADMIN',
    content: index === 35 ? '가장 최신 메시지입니다.' : `상담 메시지 ${index + 1}`,
    senderName: index % 2 === 0 ? 'Demo User' : '상담원',
    createdAt: `2026-07-06T10:${String(index).padStart(2, '0')}:00Z`
  }));
  const detail = {
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
    messages,
    pollingIntervalMs: 5000
  };

  await page.route('**/api/support/chat-sessions/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail) });
  });
  await page.route('**/api/support/chat-sessions/00000000-0000-4000-8000-000000009001', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail) });
  });
}

async function mockActiveChatWithFailedPost(page: Page) {
  await mockActiveChat(page, () => null, () => {});
  await page.route('**/api/support/chat-sessions/00000000-0000-4000-8000-000000009001/messages', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'CONFLICT_STATE',
          message: '종료된 AS 티켓 상담방에는 메시지를 보낼 수 없습니다.'
        })
      });
      return;
    }
    await route.fallback();
  });
}
