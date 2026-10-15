import { defineConfig, devices } from '@playwright/test'

import { baseURL } from './e2e/support/env.ts'

/**
 * E2E, 화면 검사, 성능 검사는 전체 실행 환경(docker compose)이 떠 있어야 합니다.
 * DB와 Prometheus까지 필요해서 webServer로 띄우지 않습니다.
 */

const desktop = devices['Desktop Chrome']

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  outputDir: './test-results',
  // 테스트 데이터와 Prometheus 수집을 함께 쓰므로 하나씩 실행합니다.
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL,
    timezoneId: 'Asia/Seoul',
    locale: 'ko-KR',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'e2e',
      testMatch: /(check-lifecycle|dashboard-status)\.spec\.ts/,
      use: { ...desktop, viewport: { width: 1280, height: 800 } },
    },
    {
      name: 'screen-1280',
      testMatch: /screen\.spec\.ts/,
      use: { ...desktop, viewport: { width: 1280, height: 800 } },
    },
    {
      name: 'screen-1920',
      testMatch: /screen\.spec\.ts/,
      use: { ...desktop, viewport: { width: 1920, height: 1080 } },
    },
    {
      name: 'perf',
      testMatch: /perf\.spec\.ts/,
      use: { ...desktop },
    },
  ],
})
