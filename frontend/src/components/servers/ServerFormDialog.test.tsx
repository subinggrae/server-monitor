import { screen } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'

import { ServerFormDialog } from '@/components/servers/ServerFormDialog'
import { renderWithProviders } from '@/test/render'
import { problem, server } from '@/test/server'

async function submitNewServer() {
  const { user } = renderWithProviders(<ServerFormDialog open onOpenChange={() => {}} />)
  await user.type(screen.getByLabelText('이름'), 'WAS-01')
  await user.type(screen.getByLabelText('Host / IP'), 'http://10.0.0.11:8080/')
  await user.click(screen.getByRole('button', { name: '저장' }))
}

describe('ServerFormDialog', () => {
  it('showsFieldErrorNextToTheField', async () => {
    server.use(
      http.post('/api/servers', () =>
        problem(400, '입력값을 확인해 주세요.', { host: 'IP 또는 hostname만 입력해 주세요.' }),
      ),
    )

    await submitNewServer()

    expect(await screen.findByText('IP 또는 hostname만 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByLabelText('Host / IP')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.queryByText('입력값을 확인해 주세요.')).not.toBeInTheDocument()
  })

  it('showsErrorWithoutFieldBelowTheForm', async () => {
    server.use(http.post('/api/servers', () => problem(409, '같은 이름의 서버가 이미 있습니다.')))

    await submitNewServer()

    expect(await screen.findByText('같은 이름의 서버가 이미 있습니다.')).toBeInTheDocument()
  })
})
