import { expect, test } from '@playwright/test'

test('configuresSnmpWithoutShowingCommunityAgain', async ({ page }) => {
  const name = `E2E-WAS-${Date.now()}`
  const community = `e2e-community-${Date.now()}`

  await page.goto('/')
  await page.getByRole('link', { name: '서버', exact: true }).click()

  // 실제 장비에 SNMP 요청이 가지 않도록 Backend container 자신의 주소를 씁니다.
  await page.getByRole('button', { name: '서버 추가' }).first().click()
  const form = page.getByRole('dialog')
  await form.getByLabel('이름').fill(name)
  await form.getByLabel('Host / IP').fill('127.0.0.1')
  await form.getByRole('button', { name: '저장' }).click()
  await expect(form).toBeHidden()

  await page.getByRole('link', { name }).click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()

  await page.getByRole('tab', { name: 'SNMP' }).click()
  await page.getByLabel('Community').fill(community)
  const saved = page.waitForResponse(
    (response) => response.request().method() === 'PUT' && response.url().endsWith('/snmp'),
  )
  await page.getByRole('button', { name: '저장' }).click()
  expect((await saved).ok()).toBe(true)

  // 저장한 뒤에는 community를 다시 보여 주지 않고 설정 여부만 알립니다.
  await expect(page.getByLabel('Community')).toHaveValue('')
  await expect(page.getByText('설정되어 있습니다. 바꿀 때만 새 값을 입력해 주세요.')).toBeVisible()
  // E2E에는 snmp_exporter가 없어서 반영 실패 경고가 보입니다.
  await expect(page.getByText('snmp_exporter에 반영되지 않았습니다.')).toBeVisible()

  // 장비가 없으므로 연결 테스트는 실패 안내로 끝납니다.
  // SNMP client는 응답을 3초씩 두 번 기다립니다.
  await page.getByRole('button', { name: '연결 테스트' }).click()
  await expect(page.getByText('SNMP 응답을 받지 못했습니다.', { exact: false })).toBeVisible({
    timeout: 15_000,
  })

  // 새로 불러온 화면과 다른 탭에도 community가 없어야 합니다.
  await page.reload()
  for (const tab of ['개요', 'Resource', 'SNMP']) {
    await page.getByRole('tab', { name: tab }).click()
    await expect(page.locator('body')).not.toContainText(community)
  }
  await expect(page.getByLabel('Community')).toHaveValue('')
})
