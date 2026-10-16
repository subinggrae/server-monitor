import { screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { MonitoringCheck } from '@/lib/api'
import { formatTime } from '@/lib/format'
import { CheckDetailPage } from '@/pages/CheckDetailPage'
import { fakeApi, renderWithQueryClient } from '@/test/render'

const CHECK: MonitoringCheck = {
  id: 3,
  name: '전자결재 Health',
  type: 'ICMP',
  typeDisplayName: 'ICMP',
  environment: 'Production',
  description: null,
  enabled: true,
  scrapeIntervalSeconds: 45,
  status: 'OK',
  targetCount: 0,
  targets: [],
  createdAt: '2026-10-14T00:00:00Z',
  updatedAt: '2026-10-14T00:00:00Z',
}

describe('Check 상세 화면', () => {
  it('저장한 수집 주기를 보인다', async () => {
    fakeApi({ 'GET /api/checks/3': { body: CHECK } })

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/monitoring/3']}>
        <Routes>
          <Route path="/monitoring/:checkId" element={<CheckDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    // 주요 속성은 요약의 속성 목록(dt 이름, dd 값)에 있습니다.
    const term = await screen.findByText('수집 주기', { selector: 'dt' })
    expect(term.nextElementSibling?.textContent).toBe('45초')
  })

  it('이 감시 항목의 최근 장애만 보인다', async () => {
    const alert = (id: number, checkId: number, summary: string) => ({
      id,
      alertName: 'MonitoringCheckDown',
      checkId,
      checkTargetId: null,
      severity: 'critical',
      status: 'RESOLVED',
      instance: '10.0.0.1',
      summary,
      startsAt: '2026-10-15T01:00:00Z',
      resolvedAt: '2026-10-15T01:10:00Z',
      lastReceivedAt: '2026-10-15T01:10:00Z',
    })
    fakeApi({
      'GET /api/checks/3': { body: CHECK },
      'GET /api/alerts': { body: [alert(1, 3, '전자결재 응답 없음'), alert(2, 9, '다른 감시 항목 장애')] },
    })

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/monitoring/3']}>
        <Routes>
          <Route path="/monitoring/:checkId" element={<CheckDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: '최근 장애' })).toBeTruthy()
    expect(await screen.findByText('전자결재 응답 없음')).toBeTruthy()
    expect(screen.queryByText('다른 감시 항목 장애')).toBeNull()
  })

  describe('머리', () => {
    const NOW = new Date('2026-10-15T05:41:00Z')
    const CRITICAL: MonitoringCheck = {
      ...CHECK,
      status: 'CRITICAL',
      targetCount: 2,
      targets: [
        {
          id: 30,
          target: '10.0.0.1',
          port: null,
          blackboxTarget: '10.0.0.1',
          enabled: true,
          status: 'CRITICAL',
          checkedAt: '2026-10-15T05:40:00Z',
        },
        {
          id: 31,
          target: '10.0.0.2',
          port: null,
          blackboxTarget: '10.0.0.2',
          enabled: true,
          status: 'OK',
          checkedAt: '2026-10-15T05:40:30Z',
        },
      ],
    }
    const firing = (checkId: number, startsAt: string) => ({
      id: checkId * 100,
      alertName: 'MonitoringCheckDown',
      checkId,
      checkTargetId: 30,
      severity: 'critical',
      status: 'FIRING',
      instance: '10.0.0.1',
      summary: '전자결재 응답 없음',
      startsAt,
      resolvedAt: null,
      lastReceivedAt: startsAt,
    })

    beforeEach(() => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      vi.setSystemTime(NOW)
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    const renderDetail = () =>
      renderWithQueryClient(
        <MemoryRouter initialEntries={['/monitoring/3']}>
          <Routes>
            <Route path="/monitoring/:checkId" element={<CheckDetailPage />} />
          </Routes>
        </MemoryRouter>,
      )

    it('위험이면 발생 중인 장애 가운데 가장 이른 시작부터 지난 시간과 마지막 확인 시각을 보인다', async () => {
      fakeApi({
        'GET /api/checks/3': { body: CRITICAL },
        'GET /api/alerts': {
          body: [firing(3, '2026-10-15T05:35:00Z'), firing(3, '2026-10-15T05:29:00Z'), firing(9, '2026-10-15T04:00:00Z')],
        },
      })

      renderDetail()

      expect(await screen.findByText('12분째')).toBeTruthy()
      expect(screen.getByText(`마지막 확인 ${formatTime('2026-10-15T05:40:30Z')}`)).toBeTruthy()
    })

    it('위험인데 장애 기록에서 시작을 찾지 못하면 시작 시각 모름이라고 보인다', async () => {
      fakeApi({ 'GET /api/checks/3': { body: CRITICAL }, 'GET /api/alerts': { body: [] } })

      renderDetail()

      expect(await screen.findByText('시작 시각 모름')).toBeTruthy()
    })

    it('정상이면 지난 시간을 보이지 않는다', async () => {
      fakeApi({ 'GET /api/checks/3': { body: CHECK }, 'GET /api/alerts': { body: [] } })

      renderDetail()

      await screen.findByText('수집 주기', { selector: 'dt' })
      expect(screen.queryByText(/분째|시작 시각 모름/)).toBeNull()
    })
  })
})
