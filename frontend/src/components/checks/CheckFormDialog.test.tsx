import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { CheckFormDialog } from '@/components/checks/CheckFormDialog'
import type { CheckTypeCapability, MonitoringCheck } from '@/lib/api'
import { fakeApi, renderWithQueryClient } from '@/test/render'

const CHECK_TYPES: CheckTypeCapability[] = [
  { type: 'ICMP', displayName: 'ICMP', requiresPort: false, requiresUrl: false },
]

const SAVED_CHECK: MonitoringCheck = {
  id: 3,
  name: '전자결재 Health',
  type: 'ICMP',
  typeDisplayName: 'ICMP',
  environment: 'Production',
  description: null,
  enabled: true,
  scrapeIntervalSeconds: 60,
  status: 'OK',
  targetCount: 1,
  targets: [
    {
      id: 7,
      target: '10.0.0.1',
      port: null,
      blackboxTarget: '10.0.0.1',
      enabled: true,
      status: 'OK',
      checkedAt: null,
    },
  ],
  createdAt: '2026-10-14T00:00:00Z',
  updatedAt: '2026-10-14T00:00:00Z',
}

const OUT_OF_RANGE_MESSAGE = '수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.'

async function fillNewCheck(scrapeInterval: string) {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('이름'), '전자결재 Health')
  await user.type(screen.getByLabelText('대상 1'), '10.0.0.1')
  const interval = screen.getByLabelText('수집 주기')
  await user.clear(interval)
  await user.type(interval, scrapeInterval)
  await user.click(screen.getByRole('button', { name: '추가' }))
}

describe('Check 폼의 실행 버튼', () => {
  it('새 감시 항목은 추가, 기존 감시 항목은 저장이다', () => {
    fakeApi({ 'GET /api/check-types': { body: CHECK_TYPES } })

    const { unmount } = renderWithQueryClient(<CheckFormDialog open onOpenChange={() => {}} />)
    expect(screen.getByRole('button', { name: '추가' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull()
    unmount()

    renderWithQueryClient(<CheckFormDialog open onOpenChange={() => {}} check={SAVED_CHECK} />)
    expect(screen.getByRole('button', { name: '저장' })).toBeTruthy()
  })
})

describe('Check 폼의 수집 주기', () => {
  it('새 Check는 15초로 시작한다', () => {
    fakeApi({ 'GET /api/check-types': { body: CHECK_TYPES } })

    renderWithQueryClient(<CheckFormDialog open onOpenChange={() => {}} />)

    expect(screen.getByLabelText('수집 주기')).toHaveProperty('value', '15')
  })

  it('기존 Check를 고칠 때는 저장된 주기로 시작한다', () => {
    fakeApi({ 'GET /api/check-types': { body: CHECK_TYPES } })

    renderWithQueryClient(<CheckFormDialog open onOpenChange={() => {}} check={SAVED_CHECK} />)

    expect(screen.getByLabelText('수집 주기')).toHaveProperty('value', '60')
  })

  it('입력한 수집 주기를 저장 요청에 담는다', async () => {
    const sent = fakeApi({
      'GET /api/check-types': { body: CHECK_TYPES },
      'POST /api/checks': { body: { ...SAVED_CHECK, scrapeIntervalSeconds: 30 } },
    })
    renderWithQueryClient(<CheckFormDialog open onOpenChange={() => {}} />)
    await screen.findByRole('option', { name: 'ICMP' })

    await fillNewCheck('30')

    await waitFor(() => {
      expect(sent.find((request) => request.method === 'POST')?.body).toMatchObject({
        scrapeIntervalSeconds: 30,
      })
    })
  })

  it.each(['9', '241', '15.5'])('%s초면 저장하지 않고 입력 칸 아래에 허용 범위를 보인다', async (value) => {
    const sent = fakeApi({ 'GET /api/check-types': { body: CHECK_TYPES } })
    renderWithQueryClient(<CheckFormDialog open onOpenChange={() => {}} />)
    await screen.findByRole('option', { name: 'ICMP' })

    await fillNewCheck(value)

    expect(await screen.findByText(OUT_OF_RANGE_MESSAGE)).toBeTruthy()
    expect(screen.getByLabelText('수집 주기').getAttribute('aria-invalid')).toBe('true')
    expect(sent.some((request) => request.method === 'POST')).toBe(false)
  })

  it('API가 돌려준 수집 주기 오류를 입력 칸 아래에 보인다', async () => {
    fakeApi({
      'GET /api/check-types': { body: CHECK_TYPES },
      'POST /api/checks': {
        status: 400,
        body: { detail: '입력값을 확인해 주세요.', fieldErrors: { scrapeIntervalSeconds: '서버가 거부한 주기' } },
      },
    })
    renderWithQueryClient(<CheckFormDialog open onOpenChange={() => {}} />)
    await screen.findByRole('option', { name: 'ICMP' })

    await fillNewCheck('30')

    expect(await screen.findByText('서버가 거부한 주기')).toBeTruthy()
  })
})
