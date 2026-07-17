import { defineConfig, devices } from '@playwright/test';

// 이 파일은 Node에서 실행되지만 웹 tsconfig는 DOM 타입만 포함한다. 브라우저 전역 timer 타입을
// Node Timeout으로 오염시키지 않도록 필요한 env shape만 로컬 선언한다.
declare const process: { env: Record<string, string | undefined> };

const webPort = process.env.PLAYWRIGHT_WEB_PORT ?? '5174';
const webBaseUrl = `http://127.0.0.1:${webPort}`;

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  // 테스트는 서로 독립(각자 page.goto)이라 파일 내부까지 병렬화한다 — CI 벽시계의 지배 요인 해소.
  fullyParallel: true,
  // CI 러너는 4 vCPU. 기본값(50%=2)이 병렬화 효과를 반토막 내므로 명시.
  workers: process.env.CI ? 4 : undefined,
  // test.only가 커밋되면 CI가 축소 스위트로 조용히 통과하는 것을 차단.
  forbidOnly: !!process.env.CI,
  // retries=0 환경에서 on-first-retry는 트레이스가 영원히 안 남는다 — 실패 시 보존으로 교정.
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: webBaseUrl,
    viewport: { width: 1440, height: 1024 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${webPort} --strictPort`,
    env: {
      VITE_DEV_PROXY_TARGET: process.env.PLAYWRIGHT_API_PROXY_TARGET ?? 'http://127.0.0.1:65535'
    },
    url: webBaseUrl,
    reuseExistingServer: false,
    timeout: 120_000
  },
  projects: [
    {
      name: 'desktop-chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 1024 }
      }
    }
  ]
});
