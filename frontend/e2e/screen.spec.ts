import { AxeBuilder } from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { createCheck, createServer, deleteCheck, deleteServer, openApi, testDataName } from './support/api.ts'

/** WCAG 2.2 AA까지의 규칙만 검사합니다. best-practice 규칙은 요구사항(0002)의 범위가 아닙니다. */
const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

/** 지금 열린 화면을 검사합니다. label은 실패 메시지에서 어느 화면인지 알려 줍니다. 기준선은 두지 않습니다. */
async function expectNoScreenProblems(page: Page, label: string) {
  const { violations } = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
  const found = violations.map((violation) => ({
    rule: violation.id,
    impact: violation.impact,
    help: violation.help,
    targets: violation.nodes.map((node) => node.target.join(' ')),
  }))
  // 접근성 위반이 있어도 넘침까지 검사해 한 번에 모두 보이도록 soft로 확인합니다.
  expect.soft(found, `${label}의 접근성 위반`).toEqual([])

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(overflow.scrollWidth, `${label}의 가로 넘침`).toBeLessThanOrEqual(overflow.clientWidth)
}

async function open(page: Page, path: string) {
  await page.goto(path)
  await page.waitForLoadState('networkidle')
}

test.describe('접근성 위반과 가로 넘침', () => {
  // 데이터 없이 열 수 있는 화면입니다. 사람이 만든 데이터가 없을 때만 빈 상태가 검사됩니다.
  for (const path of ['/', '/monitoring', '/infrastructure', '/alerts', '/notifications', '/settings', '/e2e-not-found']) {
    test(`${path} 화면에는 없다`, async ({ page }) => {
      await open(page, path)
      await expectNoScreenProblems(page, path)
    })
  }

  // 입력 칸 대부분이 대화상자에 있어 화면만으로는 라벨 누락을 놓칩니다.
  for (const [path, button] of [
    ['/monitoring', '감시 항목 추가'],
    ['/infrastructure', '서버 추가'],
    ['/notifications', '그룹 추가'],
  ] as const) {
    test(`${path}의 ${button} 대화상자에는 없다`, async ({ page }) => {
      await open(page, path)
      await page.getByRole('button', { name: button, exact: true }).first().click()
      await expect(page.getByRole('dialog')).toBeVisible()
      await expectNoScreenProblems(page, `${path}의 ${button} 대화상자`)
    })
  }

  // 오류 문구와 다시 시도 버튼도 대비와 이름을 지켜야 합니다. API를 실패시켜 오류 상태를 엽니다.
  for (const path of ['/', '/monitoring', '/infrastructure', '/alerts', '/notifications', '/settings']) {
    test(`${path} 화면의 오류 상태에는 없다`, async ({ page }) => {
      await page.route('**/api/**', (route) =>
        route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"status":500}' }),
      )
      await open(page, path)
      await expect(page.getByText(/못했습니다/).first()).toBeVisible({ timeout: 10_000 })
      await expectNoScreenProblems(page, `${path}의 오류 상태`)
    })
  }

  test.describe('상세 화면', () => {
    let checkId: number | undefined
    let serverId: number | undefined

    test.beforeAll(async () => {
      const api = await openApi()
      try {
        checkId = (await createCheck(api, { name: testDataName('screen'), type: 'ICMP', target: '127.0.0.1', port: null }))
          .id
        serverId = (await createServer(api, testDataName('screen'))).id
      } finally {
        await api.dispose()
      }
    })

    // 준비가 중간에 실패해도 원래 오류가 가려지지 않게, 만든 것만 지웁니다.
    test.afterAll(async () => {
      const api = await openApi()
      try {
        if (checkId !== undefined) await deleteCheck(api, checkId)
        if (serverId !== undefined) await deleteServer(api, serverId)
      } finally {
        await api.dispose()
      }
    })

    test('Check 상세 화면에는 없다', async ({ page }) => {
      const path = `/monitoring/checks/${checkId}`
      await open(page, path)
      await expectNoScreenProblems(page, path)
    })

    test('서버 상세 화면에는 없다', async ({ page }) => {
      const path = `/infrastructure/servers/${serverId}`
      await open(page, path)
      await expectNoScreenProblems(page, path)
    })
  })
})
