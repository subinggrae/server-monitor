import type { ReactNode } from 'react'

import { Button } from '@/components/ui/button'

/**
 * 목록 틀의 도구 막대입니다. 검색과 필터를 왼쪽에, 건수를 오른쪽 끝에 둡니다.
 * 적용 중인 필터가 있으면 그 아래 줄에 무엇으로 걸렀는지와 [필터 초기화]를 보입니다.
 */
export function Toolbar({
  children,
  count,
  activeFilters = [],
  onReset,
}: {
  children?: ReactNode
  count?: ReactNode
  activeFilters?: string[]
  onReset?: () => void
}) {
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        {children}
        {count ? <span className="ml-auto text-body text-muted-foreground">{count}</span> : null}
      </div>
      {activeFilters.length > 0 && onReset ? (
        <div className="flex flex-wrap items-center gap-2 text-body text-muted-foreground">
          <span>적용 중: {activeFilters.join(' · ')}</span>
          <Button variant="ghost" size="sm" onClick={onReset}>
            필터 초기화
          </Button>
        </div>
      ) : null}
    </div>
  )
}
