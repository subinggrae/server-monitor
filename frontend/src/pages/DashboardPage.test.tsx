import { act, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { AlertEvent, MonitoringCheck } from '@/lib/api'
import { DashboardPage } from '@/pages/DashboardPage'
import { fakeApi, renderWithQueryClient, type SentRequest } from '@/test/render'

const NOW = new Date('2026-10-15T05:41:00Z')

function check(id: number, name: string, status: MonitoringCheck['status']): MonitoringCheck {
  return {
    id,
    name,
    type: 'HTTPS',
    typeDisplayName: 'HTTPS',
    environment: 'Production',
    description: null,
    enabled: status !== 'DISABLED',
    scrapeIntervalSeconds: 15,
    status,
    targetCount: 1,
    targets: [
      {
        id: id * 10,
        target: 'https://10.20.0.31',
        port: null,
        blackboxTarget: 'https://10.20.0.31',
        enabled: true,
        status,
        checkedAt: null,
      },
    ],
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  }
}

function firing(checkId: number, startsAt: string): AlertEvent {
  return {
    id: checkId * 100,
    alertName: 'MonitoringCheckDown',
    checkId,
    checkTargetId: checkId * 10,
    severity: 'critical',
    status: 'FIRING',
    instance: 'https://10.20.0.31',
    summary: '전자결재 Health 확인에 실패했습니다',
    startsAt,
    resolvedAt: null,
    lastReceivedAt: startsAt,
  }
}

function replies(checks: MonitoringCheck[], alerts: AlertEvent[]) {
  return {
    'GET /api/checks': { body: checks },
    'GET /api/alerts': { body: alerts },
    'GET /api/servers': { body: [] },
    'GET /api/system/info': { body: { application: 'monitoring-manager', grafanaUrl: null } },
  }
}

function renderDashboard() {
  renderWithQueryClient(
    <MemoryRouter>
      <DashboardPage />
    </MemoryRouter>,
  )
}

const checkRequests = (sent: SentRequest[]) => sent.filter((request) => request.path === '/api/checks').length

describe('대시보드', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('위험한 감시 항목 수와, 위험이 시작된 시각과 지속 시간을 보인다', async () => {
    fakeApi(
      replies(
        [check(3, '전자결재 Health', 'CRITICAL'), check(4, 'ERP 로그인', 'OK')],
        [firing(3, '2026-10-15T05:29:00Z')],
      ),
    )

    renderDashboard()

    expect(await screen.findByText('위험한 감시 항목')).toBeTruthy()
    expect(screen.getByTestId('critical-count').textContent).toBe('1')
    expect(screen.getByRole('link', { name: '전자결재 Health' })).toBeTruthy()
    expect(screen.getByText('12분째')).toBeTruthy()
  })

  it('위험한 감시 항목이 없으면 모두 정상이라고 보인다', async () => {
    fakeApi(replies([check(4, 'ERP 로그인', 'OK')], []))

    renderDashboard()

    expect(await screen.findByText('모든 감시 항목이 정상입니다')).toBeTruthy()
  })

  it('30초마다 저절로 다시 확인하고 마지막 확인 시각을 보인다', async () => {
    const sent = fakeApi(replies([check(4, 'ERP 로그인', 'OK')], []))

    renderDashboard()
    await screen.findByText('모든 감시 항목이 정상입니다')
    expect(screen.getByText(/마지막 확인/)).toBeTruthy()
    const before = checkRequests(sent)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })

    expect(checkRequests(sent)).toBe(before + 1)
  })

  it('다시 확인하지 못하면 이전 값을 그대로 두고 확인하지 못했다고 알린다', async () => {
    fakeApi(replies([check(3, '전자결재 Health', 'CRITICAL')], [firing(3, '2026-10-15T05:29:00Z')]))

    renderDashboard()
    await screen.findByRole('link', { name: '전자결재 Health' })

    fakeApi({ 'GET /api/checks': { status: 500 }, 'GET /api/alerts': { status: 500 } })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })

    expect(await screen.findByText(/최신 상태를 확인하지 못했습니다/)).toBeTruthy()
    expect(screen.getByRole('link', { name: '전자결재 Health' })).toBeTruthy()
  })
})
