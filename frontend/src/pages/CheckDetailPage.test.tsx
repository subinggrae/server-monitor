import { screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'

import type { MonitoringCheck } from '@/lib/api'
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
})
