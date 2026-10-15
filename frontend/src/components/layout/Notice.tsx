import { Info, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

/**
 * 화면이나 절 전체에 걸린 안내와 경고입니다. 목록 틀의 알림 띠 자리와 절 안에서 씁니다.
 * 한쪽 가장자리만 굵게 칠하지 않고, 아이콘과 제목 문장으로 무엇이 문제인지 알립니다.
 */
export function Notice({
  tone = 'info',
  title,
  children,
  hint,
}: {
  tone?: 'info' | 'critical'
  title: string
  children?: ReactNode
  hint?: ReactNode
}) {
  const Icon = tone === 'critical' ? TriangleAlert : Info

  return (
    <div
      role={tone === 'critical' ? 'alert' : 'status'}
      className={cn(
        'flex gap-3 rounded-md border bg-surface px-4 py-3 text-body',
        tone === 'critical' ? 'border-critical' : 'border-border-strong',
      )}
    >
      <Icon
        className={cn('mt-0.5 size-4 shrink-0', tone === 'critical' ? 'text-critical' : 'text-muted-foreground')}
        aria-hidden
      />
      <div className="min-w-0 space-y-1">
        <p className={cn('font-medium', tone === 'critical' && 'text-critical-foreground')}>{title}</p>
        {children ? <div className="text-muted-foreground">{children}</div> : null}
        {hint ? <p className="text-caption text-muted-foreground">{hint}</p> : null}
      </div>
    </div>
  )
}
