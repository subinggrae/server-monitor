import { CircleAlert, Info, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

type Tone = 'info' | 'warning' | 'critical'

const TONES: Record<Tone, { icon: typeof Info; frame: string; mark: string; heading?: string }> = {
  info: { icon: Info, frame: 'border-border-strong', mark: 'text-muted-foreground' },
  warning: { icon: CircleAlert, frame: 'border-warning', mark: 'text-warning', heading: 'text-warning-foreground' },
  critical: { icon: TriangleAlert, frame: 'border-critical', mark: 'text-critical', heading: 'text-critical-foreground' },
}

/**
 * 화면이나 절 전체에 걸린 안내와 경고입니다. 목록 틀의 알림 띠 자리와 절 안에서 씁니다.
 * 한쪽 가장자리만 굵게 칠하지 않고, 아이콘과 제목 문장으로 무엇이 문제인지 알립니다.
 * 장애처럼 지금 대응해야 하는 것만 critical이고, 설정이 빠진 것처럼 알아 두어야 하는 것은 warning입니다.
 */
export function Notice({
  tone = 'info',
  title,
  children,
  hint,
}: {
  tone?: Tone
  title: string
  children?: ReactNode
  hint?: ReactNode
}) {
  const style = TONES[tone]
  const Icon = style.icon

  return (
    <div
      role={tone === 'critical' ? 'alert' : 'status'}
      className={cn('flex gap-3 rounded-md border bg-surface px-4 py-3 text-body', style.frame)}
    >
      <Icon className={cn('mt-0.5 size-4 shrink-0', style.mark)} aria-hidden />
      <div className="min-w-0 space-y-1">
        <p className={cn('font-medium', style.heading)}>{title}</p>
        {children ? <div className="text-muted-foreground">{children}</div> : null}
        {hint ? <p className="text-caption text-muted-foreground">{hint}</p> : null}
      </div>
    </div>
  )
}
