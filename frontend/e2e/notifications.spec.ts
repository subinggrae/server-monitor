import { expect, test } from '@playwright/test'

test('connectsTelegramGroupToCheck', async ({ page }) => {
  const suffix = Date.now()
  const groupName = `E2E 운영팀 ${suffix}`
  const checkName = `E2E Ping ${suffix}`
  // 실제 그룹과 겹치지 않게 E2E마다 다른 Chat ID를 씁니다. Bot Token이 없어 메시지는 나가지 않습니다.
  const chatId = `-100${suffix}`

  await page.goto('/')
  await page.getByRole('link', { name: 'Telegram 그룹', exact: true }).click()

  // E2E는 Bot Token 없이 실행합니다.
  await expect(page.getByText('Telegram Bot Token이 설정되지 않았습니다.')).toBeVisible()

  await page.getByRole('button', { name: '그룹 추가' }).first().click()
  const groupForm = page.getByRole('dialog')
  await groupForm.getByLabel('이름').fill(groupName)
  await groupForm.getByLabel('Chat ID').fill(chatId)
  await groupForm.getByRole('button', { name: '저장' }).click()
  await expect(groupForm).toBeHidden()

  const groupRow = page.getByRole('row').filter({ hasText: groupName })
  await expect(groupRow).toContainText(chatId)

  // Bot Token이 없으므로 테스트 메시지는 실패 안내로 끝납니다.
  await groupRow.getByRole('button', { name: '테스트 메시지' }).click()
  await expect(groupRow).toContainText('Telegram 메시지를 보내지 못했습니다.')

  // Check를 만들고 알림을 연결합니다.
  await page.getByRole('link', { name: 'Check', exact: true }).click()
  await page.getByRole('button', { name: 'Check 추가' }).first().click()
  const checkForm = page.getByRole('dialog')
  await checkForm.getByLabel('이름').fill(checkName)
  await checkForm.getByLabel('Target 1').fill('10.0.0.11')
  await checkForm.getByRole('button', { name: '저장' }).click()
  await expect(checkForm).toBeHidden()

  await page.getByRole('link', { name: checkName }).click()
  await expect(page.getByText('알림 대상이 없습니다.', { exact: false })).toBeVisible()

  await page.getByRole('checkbox', { name: groupName }).check()
  await page.getByRole('checkbox', { name: 'Warning' }).check()
  const saved = page.waitForResponse(
    (response) => response.request().method() === 'PUT' && response.url().endsWith('/notifications'),
  )
  await page.getByRole('button', { name: '저장', exact: true }).click()
  expect((await saved).ok()).toBe(true)

  // 다시 불러와도 연결과 설정이 남아 있습니다.
  await page.reload()
  await expect(page.getByRole('checkbox', { name: groupName })).toBeChecked()
  await expect(page.getByRole('checkbox', { name: 'Warning' })).toBeChecked()
  await expect(page.getByRole('checkbox', { name: 'Critical' })).toBeChecked()
  await expect(page.getByText('알림 대상이 없습니다.', { exact: false })).toBeHidden()

  // 알림 화면의 Check별 요약에도 연결이 보입니다.
  await page.getByRole('link', { name: 'Telegram 그룹', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: checkName })).toContainText(groupName)
})
