import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'

import { CheckFormDialog } from '@/components/checks/CheckFormDialog'
import type { MonitoringCheck, MonitoringCheckInput, MonitoringCheckTarget } from '@/lib/api'
import { renderWithProviders } from '@/test/render'
import { problem, server } from '@/test/server'

function target(id: number, url: string): MonitoringCheckTarget {
  return { id, target: url, port: null, blackboxTarget: url, enabled: true, status: 'UNKNOWN', checkedAt: null }
}

const EXISTING: MonitoringCheck = {
  id: 7,
  name: '전자결재 Health',
  type: 'HTTP',
  typeDisplayName: 'HTTP',
  environment: 'Production',
  description: null,
  enabled: true,
  status: 'UNKNOWN',
  targetCount: 2,
  targets: [target(11, 'http://10.0.0.11:8080/health'), target(12, 'http://10.0.0.12:8080/health')],
  createdAt: '2026-09-30T00:00:00Z',
  updatedAt: '2026-09-30T00:00:00Z',
}

/** 저장 요청의 본문을 모읍니다. */
function captureSave(method: 'post' | 'put', path: string) {
  const bodies: MonitoringCheckInput[] = []
  server.use(
    http[method](path, async ({ request }) => {
      bodies.push((await request.json()) as MonitoringCheckInput)
      return HttpResponse.json(EXISTING)
    }),
  )
  return bodies
}

async function openNewCheckForm() {
  const rendered = renderWithProviders(<CheckFormDialog open onOpenChange={() => {}} />)
  // 종류 목록은 API로 받습니다. 받기 전에는 Port가 필요한지 알 수 없습니다.
  await screen.findByRole('option', { name: 'TCP' })
  return rendered
}

describe('CheckFormDialog', () => {
  it('clearsTargetsAndShowsPortOnlyForPortTypesWhenTypeChanges', async () => {
    const { user } = await openNewCheckForm()
    await user.type(screen.getByLabelText('Target 1'), '10.0.0.11')
    await user.click(screen.getByRole('button', { name: '대상 추가' }))
    expect(screen.queryByLabelText('Port 1')).not.toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('종류'), 'TCP')

    // 종류가 바뀌면 입력 칸의 의미가 달라지므로 대상을 하나만 남기고 비웁니다.
    expect(screen.getByLabelText('Host 1')).toHaveValue('')
    expect(screen.queryByLabelText('Host 2')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Port 1')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('종류'), 'HTTP')

    expect(screen.getByLabelText('URL 1')).toBeInTheDocument()
    expect(screen.queryByLabelText('Port 1')).not.toBeInTheDocument()
  })

  it('sendsPortAsNumberForPortTypes', async () => {
    const bodies = captureSave('post', '/api/checks')
    const { user } = await openNewCheckForm()

    await user.type(screen.getByLabelText('이름'), 'MariaDB Port')
    await user.selectOptions(screen.getByLabelText('종류'), 'TCP')
    await user.type(screen.getByLabelText('Host 1'), '10.0.0.11')
    await user.type(screen.getByLabelText('Port 1'), '3306')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await vi.waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toMatchObject({
      name: 'MariaDB Port',
      type: 'TCP',
      targets: [{ target: '10.0.0.11', port: 3306, enabled: true }],
    })
  })

  it('sendsNullPortForTypesWithoutPort', async () => {
    const bodies = captureSave('post', '/api/checks')
    const { user } = await openNewCheckForm()

    await user.type(screen.getByLabelText('이름'), 'Ping')
    await user.type(screen.getByLabelText('Target 1'), '10.0.0.11')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await vi.waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0].targets).toEqual([{ target: '10.0.0.11', port: null, enabled: true }])
  })

  it('keepsTheLastTargetFromBeingDeleted', async () => {
    const { user } = await openNewCheckForm()

    expect(screen.getByRole('button', { name: '대상 1 삭제' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: '대상 추가' }))

    expect(screen.getByRole('button', { name: '대상 1 삭제' })).toBeEnabled()
  })

  it('sendsExistingTargetIdsWhenEditing', async () => {
    const bodies = captureSave('put', '/api/checks/7')
    const onOpenChange = vi.fn()
    const { user } = renderWithProviders(
      <CheckFormDialog open onOpenChange={onOpenChange} check={EXISTING} />,
    )
    await screen.findByRole('option', { name: 'TCP' })

    await user.click(screen.getByRole('button', { name: '대상 1 삭제' }))
    await user.click(screen.getByRole('button', { name: '대상 추가' }))
    await user.type(screen.getByLabelText('URL 2'), 'http://10.0.0.13:8080/health')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await vi.waitFor(() => expect(bodies).toHaveLength(1))
    // 남은 대상은 id를 그대로 보내야 Prometheus 기록이 끊기지 않습니다. 새 대상에는 id가 없습니다.
    expect(bodies[0].targets).toEqual([
      { id: 12, target: 'http://10.0.0.12:8080/health', port: null, enabled: true },
      { target: 'http://10.0.0.13:8080/health', port: null, enabled: true },
    ])
    await vi.waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('showsFieldErrorNextToTheField', async () => {
    server.use(
      http.post('/api/checks', () =>
        problem(400, '입력값을 확인해 주세요.', { name: '이름을 입력해 주세요.' }),
      ),
    )
    const { user } = await openNewCheckForm()

    await user.type(screen.getByLabelText('Target 1'), '10.0.0.11')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('이름을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByLabelText('이름')).toHaveAttribute('aria-invalid', 'true')
    // 칸에 표시한 오류는 입력 화면 아래에 다시 쓰지 않습니다.
    expect(screen.queryByText('입력값을 확인해 주세요.')).not.toBeInTheDocument()
  })

  it('showsErrorWithoutFieldBelowTheForm', async () => {
    server.use(
      http.post('/api/checks', () => problem(400, '같은 대상이 두 번 들어 있습니다.')),
    )
    const { user } = await openNewCheckForm()

    await user.type(screen.getByLabelText('이름'), 'Ping')
    await user.type(screen.getByLabelText('Target 1'), '10.0.0.11')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('같은 대상이 두 번 들어 있습니다.')).toBeInTheDocument()
  })
})
