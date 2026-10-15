import type { ReactNode } from 'react'

import { Button } from '@/components/ui/button'

/** 틀이 본문 자리에 무엇을 그릴지 정합니다. 화면은 상태만 넘기고 문구 모양은 틀이 정합니다. */
export type ContentState =
  | { kind: 'ready' }
  | { kind: 'loading' }
  | { kind: 'error'; message: string; onRetry?: () => void }
  | { kind: 'empty'; title: string; description?: string }

export type PageAction = { label: string; onClick: () => void; disabled?: boolean }

/** 화면 전체를 막지 않고 내용이 들어올 자리를 그대로 남겨 둡니다. */
export function SkeletonRows({ rows = 3 }: { rows?: number }) {
  return (
    <div className="space-y-2 py-2" role="status" aria-label="불러오는 중…">
      {Array.from({ length: rows }).map((_, index) => (
        <span key={index} className="block h-3 animate-pulse rounded-sm bg-accent last:w-2/3" />
      ))}
    </div>
  )
}

export function StateError({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-md border border-border bg-surface px-4 py-3">
      <p className="text-body font-medium">{message}</p>
      {onRetry ? (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          다시 시도
        </Button>
      ) : null}
    </div>
  )
}

export function StateEmpty({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="rounded-md border border-dashed border-border-strong px-4 py-12 text-center">
      <p className="text-body font-medium">{title}</p>
      {description ? <p className="mx-auto mt-1 max-w-xl text-body text-muted-foreground">{description}</p> : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  )
}
