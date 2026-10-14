import { expect, test } from '@playwright/test'

import { createCheck, deleteCheck, openApi, testDataName } from './support/api.ts'

/** Prometheus가 SD를 다시 읽고(15초) 한 번 수집하기(15초)까지 걸리는 시간에 여유를 둔 값입니다. */
const COLLECTION_TIMEOUT_MS = 90_000

let checkId: number | undefined

test.afterEach(async () => {
  if (checkId === undefined) return
  const api = await openApi()
  try {
    await deleteCheck(api, checkId)
  } finally {
    await api.dispose()
  }
})

test('닿지 않는 대상의 Check는 수집된 뒤 대시보드의 위험한 Check 표에 보인다', async ({ page }) => {
  test.setTimeout(COLLECTION_TIMEOUT_MS + 30_000)
  const checkName = testDataName('unreachable')

  await test.step('blackbox-exporter에서 연결이 거부되는 TCP 대상으로 Check를 만든다', async () => {
    const api = await openApi()
    try {
      // blackbox-exporter container 안의 1번 port에는 아무것도 없어서 연결이 바로 거부됩니다.
      checkId = (await createCheck(api, { name: checkName, type: 'TCP', target: '127.0.0.1', port: 1 })).id
    } finally {
      await api.dispose()
    }
  })

  await test.step('대시보드의 위험한 Check 표에 Check가 나타난다', async () => {
    const dangerousCheck = page.getByRole('table').getByRole('link', { name: checkName, exact: true })

    // 화면은 자동으로 다시 불러오지 않으므로 새로 열어 가며 확인합니다.
    await expect(async () => {
      await page.goto('/')
      await expect(dangerousCheck).toBeVisible({ timeout: 5_000 })
    }).toPass({ timeout: COLLECTION_TIMEOUT_MS, intervals: [5_000] })
  })
})
