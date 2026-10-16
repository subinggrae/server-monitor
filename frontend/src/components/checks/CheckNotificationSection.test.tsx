import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { CheckNotificationSection } from '@/components/checks/CheckNotificationSection'
import type { CheckNotifications, TelegramDestination } from '@/lib/api'
import { fakeApi, renderWithQueryClient } from '@/test/render'

const DESTINATIONS: TelegramDestination[] = [
  {
    id: 20,
    name: '운영팀',
    chatId: -100,
    messageThreadId: null,
    enabled: true,
    createdAt: '2026-10-14T00:00:00Z',
    updatedAt: '2026-10-14T00:00:00Z',
  },
]

const SAVED: CheckNotifications = { checkId: 3, bindings: [], repeatIntervalMinutes: 30 }

const OUT_OF_RANGE_MESSAGE = '재발송 주기는 5분에서 1440분 사이 정수로 입력해 주세요.'

function givenApi(extra: Parameters<typeof fakeApi>[0] = {}) {
  return fakeApi({
    'GET /api/telegram/destinations': { body: DESTINATIONS },
    'GET /api/checks/3/notifications': { body: SAVED },
    ...extra,
  })
}

async function saveWithInterval(minutes: string) {
  const user = userEvent.setup()
  const interval = await screen.findByLabelText('재발송 주기')
  await user.clear(interval)
  await user.type(interval, minutes)
  await user.click(screen.getByRole('button', { name: '저장' }))
}

describe('Check 알림 설정의 받는 그룹', () => {
  it('받는 그룹이 없으면 위험이 아닌 경고 안내로 알린다', async () => {
    givenApi()

    renderWithQueryClient(<CheckNotificationSection checkId={3} />)

    const title = await screen.findByText('받는 그룹이 없습니다.')
    expect(title.closest('[role="status"]')).not.toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('Check 알림 설정의 재발송 주기', () => {
  it('저장된 재발송 주기로 시작한다', async () => {
    givenApi()

    renderWithQueryClient(<CheckNotificationSection checkId={3} />)

    expect(await screen.findByLabelText('재발송 주기')).toHaveProperty('value', '30')
  })

  it('실제 알림이 주기보다 늦거나 이를 수 있다는 안내를 보인다', async () => {
    givenApi()

    renderWithQueryClient(<CheckNotificationSection checkId={3} />)

    expect(await screen.findByText(/최대 5분 늦거나 1분 이르게/)).toBeTruthy()
  })

  it('입력한 재발송 주기를 저장 요청에 담는다', async () => {
    const sent = givenApi({ 'PUT /api/checks/3/notifications': { body: { ...SAVED, repeatIntervalMinutes: 15 } } })
    renderWithQueryClient(<CheckNotificationSection checkId={3} />)

    await saveWithInterval('15')

    await waitFor(() => {
      expect(sent.find((request) => request.method === 'PUT')?.body).toMatchObject({ repeatIntervalMinutes: 15 })
    })
  })

  it.each(['4', '1441', '15.5'])('%s분이면 저장하지 않고 입력 칸 아래에 허용 범위를 보인다', async (value) => {
    const sent = givenApi()
    renderWithQueryClient(<CheckNotificationSection checkId={3} />)

    await saveWithInterval(value)

    expect(await screen.findByText(OUT_OF_RANGE_MESSAGE)).toBeTruthy()
    expect(screen.getByLabelText('재발송 주기').getAttribute('aria-invalid')).toBe('true')
    expect(sent.some((request) => request.method === 'PUT')).toBe(false)
  })

  it('API가 돌려준 재발송 주기 오류를 입력 칸 아래에 보인다', async () => {
    givenApi({
      'PUT /api/checks/3/notifications': {
        status: 400,
        body: { detail: '입력값을 확인해 주세요.', fieldErrors: { repeatIntervalMinutes: '서버가 거부한 주기' } },
      },
    })
    renderWithQueryClient(<CheckNotificationSection checkId={3} />)

    await saveWithInterval('15')

    expect(await screen.findByText('서버가 거부한 주기')).toBeTruthy()
  })
})

describe('Check 알림 설정의 저장 실패', () => {
  it('서버가 이유와 함께 거부하면 그 이유를 보인다', async () => {
    givenApi({
      'PUT /api/checks/3/notifications': {
        status: 400,
        body: { detail: '같은 Telegram 그룹이 두 번 들어 있습니다.' },
      },
    })
    renderWithQueryClient(<CheckNotificationSection checkId={3} />)

    await saveWithInterval('30')

    expect(await screen.findByText('같은 Telegram 그룹이 두 번 들어 있습니다.')).toBeTruthy()
    expect(screen.queryByText('저장하지 못했습니다. 잠시 후 다시 시도해 주세요.')).toBeNull()
  })

  it('서버 오류면 잠시 후 다시 시도하라고 안내한다', async () => {
    givenApi({ 'PUT /api/checks/3/notifications': { status: 500, body: { detail: '내부 처리 중 오류' } } })
    renderWithQueryClient(<CheckNotificationSection checkId={3} />)

    await saveWithInterval('30')

    expect(await screen.findByText('저장하지 못했습니다. 잠시 후 다시 시도해 주세요.')).toBeTruthy()
    expect(screen.queryByText('내부 처리 중 오류')).toBeNull()
  })
})
