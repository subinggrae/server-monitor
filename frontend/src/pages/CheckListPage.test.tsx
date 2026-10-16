import { fireEvent, screen, within } from '@testing-library/react'
import { useEffect } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'

import type { MonitoringCheck } from '@/lib/api'
import { CheckListPage } from '@/pages/CheckListPage'
import { fakeApi, renderWithQueryClient } from '@/test/render'

function check(id: number, name: string, status: MonitoringCheck['status'], environment = 'Production'): MonitoringCheck {
  return {
    id,
    name,
    type: 'ICMP',
    typeDisplayName: 'ICMP',
    environment,
    description: null,
    enabled: status !== 'DISABLED',
    scrapeIntervalSeconds: 15,
    status,
    targetCount: 0,
    targets: [],
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  }
}

const CHECKS = [
  check(1, '가 정상', 'OK'),
  check(2, '나 사용 안 함', 'DISABLED'),
  check(3, '다 위험', 'CRITICAL'),
  check(4, '라 알 수 없음', 'UNKNOWN', 'Development'),
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
  fakeApi({ 'GET /api/checks': { body: CHECKS } })
  renderWithQueryClient(
    <MemoryRouter initialEntries={[`/monitoring${search}`]}>
      <Routes>
        <Route
          path="/monitoring"
          element={
            <>
              <CheckListPage />
              <LocationProbe />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  )
}

async function rowNames() {
  const table = await screen.findByRole('table')
  return within(table)
    .getAllByRole('link')
    .map((link) => link.textContent)
}

describe('감시 항목 목록', () => {
  it('처음에는 위험, 알 수 없음, 정상, 사용 안 함 순서로 보인다', async () => {
    renderAt('')

    expect(await rowNames()).toEqual(['다 위험', '라 알 수 없음', '가 정상', '나 사용 안 함'])
  })

  it('열 머리글을 누르면 정렬이 바뀌고 주소에 남는다', async () => {
    renderAt('')
    await rowNames()

    fireEvent.click(screen.getByRole('button', { name: '이름' }))

    expect(await rowNames()).toEqual(['가 정상', '나 사용 안 함', '다 위험', '라 알 수 없음'])
    expect(location).toContain('sort=name')
    expect(screen.getByRole('columnheader', { name: '이름' }).getAttribute('aria-sort')).toBe('ascending')
  })

  it('주소의 정렬과 필터로 열고, 적용 중인 필터를 보인다', async () => {
    renderAt('?sort=name&dir=desc&env=Production')

    expect(await rowNames()).toEqual(['다 위험', '나 사용 안 함', '가 정상'])
    expect(screen.getByText(/적용 중/)).toBeTruthy()
  })

  it('필터를 바꾸면 주소에 남고, 필터 초기화로 기본값으로 돌아간다', async () => {
    renderAt('')
    await rowNames()

    fireEvent.change(screen.getByLabelText('상태 필터'), { target: { value: 'CRITICAL' } })
    expect(await rowNames()).toEqual(['다 위험'])
    expect(location).toContain('status=CRITICAL')

    fireEvent.click(screen.getAllByRole('button', { name: '필터 초기화' })[0])
    expect(await rowNames()).toHaveLength(4)
    expect(location).toBe('')
  })

  it('정하지 않은 정렬과 필터 값은 무시하고 기본값을 쓴다', async () => {
    renderAt('?sort=<script>&status=BROKEN&dir=sideways')

    expect(await rowNames()).toEqual(['다 위험', '라 알 수 없음', '가 정상', '나 사용 안 함'])
    expect(screen.queryByText(/적용 중/)).toBeNull()
  })
})
