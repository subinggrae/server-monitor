import { expect, test } from '@playwright/test'

import { deleteCheck, listChecks, openApi, testDataName } from './support/api.ts'

const checkName = testDataName('lifecycle')

test.afterEach(async () => {
  // 중간에 실패해도 만든 Check를 남기지 않습니다.
  const api = await openApi()
  try {
    for (const check of await listChecks(api)) {
      if (check.name === checkName) await deleteCheck(api, check.id)
    }
  } finally {
    await api.dispose()
  }
})

test('Check를 등록하면 목록과 상세에 보이고 삭제하면 목록에서 사라진다', async ({ page }) => {
  await test.step('Check를 등록한다', async () => {
    await page.goto('/monitoring')
    // 빈 목록이면 같은 이름의 버튼이 빈 상태 안에도 있습니다.
    await page.getByRole('button', { name: '감시 항목 추가', exact: true }).first().click()

    const dialog = page.getByRole('dialog', { name: '감시 항목 추가', exact: true })
    await dialog.getByLabel('이름', { exact: true }).fill(checkName)
    await dialog.getByLabel('종류', { exact: true }).selectOption('TCP')
    await dialog.getByLabel('호스트 1', { exact: true }).fill('127.0.0.1')
    await dialog.getByLabel('포트 1', { exact: true }).fill('1')
    await dialog.getByRole('button', { name: '추가', exact: true }).click()
    await expect(dialog).toBeHidden()
  })

  await test.step('목록에서 등록한 Check를 연다', async () => {
    await page.getByRole('link', { name: checkName, exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: checkName, exact: true })).toBeVisible()
    await expect(page.getByText('127.0.0.1:1')).toBeVisible()
  })

  await test.step('상세에서 Check를 삭제한다', async () => {
    await page.getByRole('button', { name: '삭제', exact: true }).click()
    const confirm = page.getByRole('dialog', { name: '이 감시 항목을 삭제할까요?', exact: true })
    await confirm.getByRole('button', { name: '삭제', exact: true }).click()

    await expect(page).toHaveURL(/\/monitoring$/)
    await expect(page.getByRole('link', { name: checkName, exact: true })).toHaveCount(0)
  })
})
