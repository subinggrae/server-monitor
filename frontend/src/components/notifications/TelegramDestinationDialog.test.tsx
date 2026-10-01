import { screen } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'

import { TelegramDestinationDialog } from '@/components/notifications/TelegramDestinationDialog'
import { renderWithProviders } from '@/test/render'
import { problem, server } from '@/test/server'

async function submitNewDestination() {
  const { user } = renderWithProviders(<TelegramDestinationDialog open onOpenChange={() => {}} />)
  await user.type(screen.getByLabelText('이름'), '서비스 운영팀')
  await user.type(screen.getByLabelText('Chat ID'), '-1001234567890')
  await user.click(screen.getByRole('button', { name: '저장' }))
}

describe('TelegramDestinationDialog', () => {
  it('showsFieldErrorNextToTheField', async () => {
    server.use(
      http.post('/api/telegram/destinations', () =>
        problem(400, '입력값을 확인해 주세요.', { name: '이름은 100자까지 입력할 수 있습니다.' }),
      ),
    )

    await submitNewDestination()

    expect(await screen.findByText('이름은 100자까지 입력할 수 있습니다.')).toBeInTheDocument()
    expect(screen.getByLabelText('이름')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.queryByText('입력값을 확인해 주세요.')).not.toBeInTheDocument()
  })

  it('showsErrorWithoutFieldBelowTheForm', async () => {
    server.use(
      http.post('/api/telegram/destinations', () =>
        problem(409, '이미 등록된 Telegram 그룹입니다. 목록에서 기존 그룹을 확인해 주세요.'),
      ),
    )

    await submitNewDestination()

    expect(
      await screen.findByText('이미 등록된 Telegram 그룹입니다. 목록에서 기존 그룹을 확인해 주세요.'),
    ).toBeInTheDocument()
  })
})
