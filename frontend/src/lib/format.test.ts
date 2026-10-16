import { describe, expect, it } from 'vitest'

import { formatElapsed } from '@/lib/format'

const NOW = new Date('2026-10-15T12:00:00Z').getTime()
const minutesAgo = (minutes: number) => new Date(NOW - minutes * 60_000).toISOString()

describe('장애가 이어진 시간', () => {
  it('1분이 안 되면 1분 미만으로 쓴다', () => {
    expect(formatElapsed(minutesAgo(0.5), NOW)).toBe('1분 미만')
  })

  it('1시간이 안 되면 분으로 쓴다', () => {
    expect(formatElapsed(minutesAgo(1), NOW)).toBe('1분째')
    expect(formatElapsed(minutesAgo(59), NOW)).toBe('59분째')
  })

  it('하루가 안 되면 시간과 분으로 쓰고, 분이 0이면 시간만 쓴다', () => {
    expect(formatElapsed(minutesAgo(60), NOW)).toBe('1시간째')
    expect(formatElapsed(minutesAgo(98), NOW)).toBe('1시간 38분째')
  })

  it('하루가 넘으면 일과 시간으로 쓴다', () => {
    expect(formatElapsed(minutesAgo(24 * 60), NOW)).toBe('1일째')
    expect(formatElapsed(minutesAgo(51 * 60 + 10), NOW)).toBe('2일 3시간째')
  })

  it('시각이 어긋나 시작이 미래여도 음수를 쓰지 않는다', () => {
    expect(formatElapsed(minutesAgo(-5), NOW)).toBe('1분 미만')
  })
})
