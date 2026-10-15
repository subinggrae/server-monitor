import { fireEvent, screen, within } from '@testing-library/react'
import { useEffect } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'

import type { AlertEvent } from '@/lib/api'
import { AlertListPage } from '@/pages/AlertListPage'
import { fakeApi, renderWithQueryClient } from '@/test/render'

function alert(id: number, summary: string, status: AlertEvent['status'], startsAt: string): AlertEvent {
  return {
    id,
    alertName: 'MonitoringCheckDown',
    checkId: null,
    checkTargetId: null,
    severity: 'critical',
    status,
    instance: '10.0.0.1',
    summary,
    startsAt,
    resolvedAt: status === 'RESOLVED' ? '2026-10-15T05:00:00Z' : null,
    lastReceivedAt: startsAt,
  }
}

// API는 마지막으로 받은 순서로 돌려줍니다. 복구된 장애가 앞에 섞여 있습니다.
const ALERTS = [
  alert(1, '복구된 최근 장애', 'RESOLVED', '2026-10-15T04:50:00Z'),
  alert(2, '발생 중인 오래된 장애', 'FIRING', '2026-10-15T01:00:00Z'),
  alert(3, '발생 중인 최근 장애', 'FIRING', '2026-10-15T04:00:00Z'),
]

let location = ''

/** 화면이 바꾼 주소를 테스트가 읽을 수 있게 남깁니다. */
function LocationProbe() {
  const { search } = useLocation()
  useEffect(() => {
    location = search
  }, [search])
  return null
}

function renderAt(search: string) {
  fakeApi({ 'GET /api/alerts': { body: ALERTS } })
  renderWithQueryClient(
    <MemoryRouter initialEntries={[`/alerts${search}`]}>
      <Routes>
        <Route
          path="/alerts"
          element={
            <>
              <AlertListPage />
              <LocationProbe />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  )
}

async function rowSummaries() {
  const table = await screen.findByRole('table')
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell')[2].textContent)
}

describe('장애 기록', () => {
  it('발생 중인 장애를 위에, 그 안에서는 최근에 시작한 것부터 보인다', async () => {
    renderAt('')

    expect(await rowSummaries()).toEqual(['발생 중인 최근 장애', '발생 중인 오래된 장애', '복구된 최근 장애'])
  })

  it('상태로 거르면 주소에 남고, 주소로 열어도 같다', async () => {
    renderAt('')
    await rowSummaries()

    fireEvent.change(screen.getByLabelText('상태 필터'), { target: { value: 'RESOLVED' } })

    expect(await rowSummaries()).toEqual(['복구된 최근 장애'])
    expect(location).toContain('status=RESOLVED')
  })

  it('정하지 않은 상태 값은 무시한다', async () => {
    renderAt('?status=ALL_OF_IT')

    expect(await rowSummaries()).toHaveLength(3)
  })
})
