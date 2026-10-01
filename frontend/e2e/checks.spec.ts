import { expect, test } from '@playwright/test'

test('createsEditsAndDeletesCheck', async ({ page }) => {
  const name = `E2E Health ${Date.now()}`

  await page.goto('/')
  await page.getByRole('link', { name: 'Check', exact: true }).click()

  // 추가: 대상 두 개
  await page.getByRole('button', { name: 'Check 추가' }).first().click()
  const form = page.getByRole('dialog')
  await form.getByLabel('이름').fill(name)
  await form.getByLabel('종류').selectOption('HTTP')
  await form.getByLabel('URL 1').fill('http://10.0.0.11:8080/health')
  await form.getByRole('button', { name: '대상 추가' }).click()
  await form.getByLabel('URL 2').fill('http://10.0.0.12:8080/health')
  await form.getByRole('button', { name: '저장' }).click()
  await expect(form).toBeHidden()

  const row = page.getByRole('row').filter({ hasText: name })
  await expect(row).toContainText('HTTP')
  await expect(row).toContainText('http://10.0.0.11:8080/health')
  await expect(row).toContainText('외 1개')

  // 상세
  await row.getByRole('link', { name }).click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'http://10.0.0.11:8080/health' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'http://10.0.0.12:8080/health' })).toBeVisible()

  // 수정: 첫 대상을 지우고 새 대상을 더합니다.
  await page.getByRole('button', { name: '대상 편집' }).click()
  await form.getByRole('button', { name: '대상 1 삭제' }).click()
  await form.getByRole('button', { name: '대상 추가' }).click()
  await form.getByLabel('URL 2').fill('http://10.0.0.13:8080/health')
  await form.getByRole('button', { name: '저장' }).click()
  await expect(form).toBeHidden()

  await expect(page.getByRole('cell', { name: 'http://10.0.0.11:8080/health' })).toBeHidden()
  await expect(page.getByRole('cell', { name: 'http://10.0.0.12:8080/health' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'http://10.0.0.13:8080/health' })).toBeVisible()

  // 삭제
  await page.getByRole('button', { name: '삭제' }).click()
  const confirm = page.getByRole('dialog', { name: '이 Check를 삭제할까요?' })
  await confirm.getByRole('button', { name: '삭제' }).click()

  await expect(page).toHaveURL(/\/monitoring$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Monitoring Check' })).toBeVisible()
  await expect(page.getByRole('link', { name })).toBeHidden()
})

test('keepsTcpCheckWithoutPortFromBeingSaved', async ({ page }) => {
  const name = `E2E Port ${Date.now()}`

  await page.goto('/monitoring')
  await page.getByRole('button', { name: 'Check 추가' }).first().click()
  const form = page.getByRole('dialog')
  await form.getByLabel('이름').fill(name)
  await form.getByLabel('종류').selectOption('TCP')
  await form.getByLabel('Host 1').fill('10.0.0.11')
  await form.getByRole('button', { name: '저장' }).click()

  // Backend가 거절한 이유가 입력 화면에 보이고 화면은 닫히지 않습니다.
  await expect(form).toContainText('Port가 필요합니다')
  await expect(form).toBeVisible()

  await form.getByRole('button', { name: '취소' }).click()
  await expect(form).toBeHidden()

  // 목록을 다시 받은 뒤에 확인합니다. 그리기 전에 확인하면 항상 통과합니다.
  const listed = page.waitForResponse(
    (response) => response.request().method() === 'GET' && response.url().endsWith('/api/checks'),
  )
  await page.reload()
  const checks = (await (await listed).json()) as { name: string }[]
  expect(checks.map((check) => check.name)).not.toContain(name)
  await expect(page.getByRole('heading', { level: 1, name: 'Monitoring Check' })).toBeVisible()
  await expect(page.getByRole('link', { name })).toBeHidden()
})
