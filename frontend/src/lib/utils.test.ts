import { describe, expect, it } from 'vitest'

import { cn } from '@/lib/utils'

describe('className 병합', () => {
  it.each(['text-caption', 'text-body', 'text-section', 'text-page', 'text-figure'])(
    '글자 크기 토큰 %s와 글자 색을 함께 남긴다',
    (size) => {
      expect(cn('text-primary-foreground', size).split(' ')).toEqual(['text-primary-foreground', size])
    },
  )

  it('글자 크기 토큰끼리는 뒤의 것만 남긴다', () => {
    expect(cn('text-body', 'text-caption')).toBe('text-caption')
  })

  it('글자 색끼리는 뒤의 것만 남긴다', () => {
    expect(cn('text-muted-foreground', 'text-foreground')).toBe('text-foreground')
  })
})
