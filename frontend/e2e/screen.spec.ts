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

/** axe의 html에는 자식 요소까지 들어 있어, 여는 태그만 봐야 그 요소 자신의 class로 거를 수 있습니다. */
const openingTag = (html: string) => html.slice(0, html.indexOf('>') + 1)

function withoutKnownViolations(violations: Violations) {
  return violations
    .map((violation) =>
      violation.id === 'color-contrast'
        ? {
            ...violation,
            nodes: violation.nodes.filter((node) => !SUBTLE_FOREGROUND_CLASS.test(openingTag(node.html))),
          }
        : violation,
    )
    .filter((violation) => violation.nodes.length > 0)
}

/** 지금 열린 화면을 검사합니다. label은 실패 메시지에서 어느 화면인지 알려 줍니다. */
async function expectNoScreenProblems(page: Page, label: string) {
  const { violations } = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
  const found = withoutKnownViolations(violations).map((violation) => ({
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
