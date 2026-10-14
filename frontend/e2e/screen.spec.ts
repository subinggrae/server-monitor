import { AxeBuilder } from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { createCheck, createServer, deleteCheck, deleteServer, openApi, testDataName } from './support/api.ts'

/** WCAG 2.2 AA까지의 규칙만 검사합니다. best-practice 규칙은 요구사항(0002)의 범위가 아닙니다. */
const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

/**
 * 기준선: 화면 검사를 들일 때 이미 있던 위반입니다. 새로 생긴 위반에서만 실패하도록 뺍니다.
 * `--subtle-foreground` 글자는 배경과 대비가 3.18:1로 4.5:1에 못 미칩니다(color-contrast).
 * 사람이 2026-10-14에 기준선으로 두고 대비는 별도 작업에서 고치기로 정했습니다. 고치면 이 기준선을 지웁니다.
 */
const SUBTLE_FOREGROUND_CLASS = /\btext-subtle-foreground\b/

type Violations = Awaited<ReturnType<AxeBuilder['analyze']>>['violations']

function withoutKnownViolations(violations: Violations) {
  return violations
    .map((violation) =>
      violation.id === 'color-contrast'
        ? { ...violation, nodes: violation.nodes.filter((node) => !SUBTLE_FOREGROUND_CLASS.test(node.html)) }
        : violation,
    )
    .filter((violation) => violation.nodes.length > 0)
}

async function expectNoScreenProblems(page: Page, path: string) {
  await page.goto(path)
  await page.waitForLoadState('networkidle')

  const { violations } = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
  const found = withoutKnownViolations(violations).map((violation) => ({
    rule: violation.id,
    impact: violation.impact,
    help: violation.help,
    targets: violation.nodes.map((node) => node.target.join(' ')),
  }))
  // 접근성 위반이 있어도 넘침까지 검사해 한 번에 모두 보이도록 soft로 확인합니다.
  expect.soft(found, `${path}의 접근성 위반`).toEqual([])

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(overflow.scrollWidth, `${path}의 가로 넘침`).toBeLessThanOrEqual(overflow.clientWidth)
}

// 데이터 없이 열 수 있는 화면입니다. 사람이 만든 데이터가 없으면 빈 상태가 검사됩니다.
for (const path of ['/', '/monitoring', '/infrastructure', '/alerts', '/notifications', '/settings', '/e2e-not-found']) {
  test(`${path} 화면에 접근성 위반과 가로 넘침이 없다`, async ({ page }) => {
    await expectNoScreenProblems(page, path)
  })
}

test.describe('상세 화면', () => {
  let checkId: number
  let serverId: number

  test.beforeAll(async () => {
    const api = await openApi()
    try {
      checkId = (await createCheck(api, { name: testDataName('screen'), type: 'ICMP', target: '127.0.0.1', port: null })).id
      serverId = (await createServer(api, testDataName('screen'))).id
    } finally {
      await api.dispose()
    }
  })

  test.afterAll(async () => {
    const api = await openApi()
    try {
      await deleteCheck(api, checkId)
      await deleteServer(api, serverId)
    } finally {
      await api.dispose()
    }
  })

  test('Check 상세 화면에 접근성 위반과 가로 넘침이 없다', async ({ page }) => {
    await expectNoScreenProblems(page, `/monitoring/checks/${checkId}`)
  })

  test('서버 상세 화면에 접근성 위반과 가로 넘침이 없다', async ({ page }) => {
    await expectNoScreenProblems(page, `/infrastructure/servers/${serverId}`)
  })
})
