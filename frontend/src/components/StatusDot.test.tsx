import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { AlertStatusLabel, StatusDot, WarningLabel } from '@/components/StatusDot'

describe('상태 표시', () => {
  it('상태마다 문구를 함께 보인다', () => {
    render(
      <>
        <StatusDot status="OK" />
        <StatusDot status="CRITICAL" />
        <StatusDot status="UNKNOWN" />
        <StatusDot status="DISABLED" />
      </>,
    )

    for (const label of ['정상', '위험', '알 수 없음', '사용 안 함']) {
      expect(screen.getByText(label)).toBeTruthy()
    }
  })

  it('위험은 점이 아닌 아이콘 모양으로 구분한다', () => {
    const { container: critical } = render(<StatusDot status="CRITICAL" />)
    const { container: ok } = render(<StatusDot status="OK" />)

    expect(critical.querySelector('svg')).not.toBeNull()
    expect(ok.querySelector('svg')).toBeNull()
  })

  it('장애 상태도 같은 모양 규칙으로 보인다', () => {
    const { container: firing } = render(<AlertStatusLabel status="FIRING" />)
    const { container: resolved } = render(<AlertStatusLabel status="RESOLVED" />)

    expect(screen.getByText('발생 중')).toBeTruthy()
    expect(firing.querySelector('svg')).not.toBeNull()
    expect(resolved.querySelector('svg')).toBeNull()
  })

  it('설정 경고는 위험 아이콘이 아닌 경고 모양으로 보인다', () => {
    const { container } = render(<WarningLabel label="없음" />)

    expect(screen.getByText('없음')).toBeTruthy()
    expect(container.querySelector('svg')).toBeNull()
    expect(container.querySelector('.rotate-45')).not.toBeNull()
  })
})
