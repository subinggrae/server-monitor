import { defineConfig, devices } from '@playwright/test'

const port = process.env.E2E_WEB_PORT ?? '18081'

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  // 흐름이 같은 Database를 쓰므로 하나씩 실행합니다.
  workers: 1,
  fullyParallel: false,
  // 불안정한 테스트를 재시도로 숨기지 않습니다.
  retries: 0,
  forbidOnly: true,
  reporter: [['list']],
  outputDir: './test-results',
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
