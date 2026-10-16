import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { ServerFormDialog } from '@/components/servers/ServerFormDialog'
import type { ServerTarget } from '@/lib/api'
import { fakeApi, renderWithQueryClient } from '@/test/render'

const SERVER: ServerTarget = {
  id: 5,
  name: 'web01',
  host: '10.0.0.5',
  environment: 'Production',
  os: 'LINUX',
  description: null,
  enabled: true,
  collectionState: 'COLLECTING',
  monitoredResourceCount: 3,
  createdAt: '2026-10-16T00:00:00Z',
  updatedAt: '2026-10-16T00:00:00Z',
}

describe('서버 입력 화면의 OS', () => {
  it('새 서버는 Linux로 시작하고 고른 OS를 보낸다', async () => {
    const sent = fakeApi({ 'POST /api/servers': { body: { ...SERVER, os: 'WINDOWS' } } })
    const user = userEvent.setup()
    renderWithQueryClient(<ServerFormDialog open onOpenChange={() => {}} />)

    const os = screen.getByLabelText('OS')
    expect(os).toHaveProperty('value', 'LINUX')

    await user.type(screen.getByLabelText('이름'), 'win01')
    await user.type(screen.getByLabelText(/호스트/), '10.0.0.9')
    await user.selectOptions(os, 'WINDOWS')
    await user.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => expect(sent.some((request) => request.method === 'POST')).toBe(true))
    expect(sent.find((request) => request.method === 'POST')?.body).toMatchObject({ os: 'WINDOWS' })
  })

  it('수정할 때는 저장된 OS로 시작하고 바꾸면 무엇이 바뀌는지 안내한다', () => {
    fakeApi({})
    renderWithQueryClient(<ServerFormDialog open onOpenChange={() => {}} server={{ ...SERVER, os: 'WINDOWS' }} />)

    expect(screen.getByLabelText('OS')).toHaveProperty('value', 'WINDOWS')
    expect(screen.getByText('OS를 바꾸면 포트 기본값과 리소스 찾기 방식이 바뀝니다.')).toBeTruthy()
  })

  it('OS 칸 오류를 칸 아래에 보인다', async () => {
    fakeApi({
      'POST /api/servers': {
        status: 400,
        body: {
          title: '입력값을 확인해 주세요',
          detail: 'OS를 선택해 주세요.',
          fieldErrors: { os: 'OS를 선택해 주세요.' },
        },
      },
    })
    const user = userEvent.setup()
    renderWithQueryClient(<ServerFormDialog open onOpenChange={() => {}} />)

    await user.type(screen.getByLabelText('이름'), 'web02')
    await user.type(screen.getByLabelText(/호스트/), '10.0.0.6')
    await user.click(screen.getByRole('button', { name: '추가' }))

    expect(await screen.findByText('OS를 선택해 주세요.')).toBeTruthy()
    expect(screen.getByLabelText('OS').getAttribute('aria-invalid')).toBe('true')
  })
})
