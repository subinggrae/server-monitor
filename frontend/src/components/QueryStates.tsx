import type { ReactNode } from 'react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/** 화면 전체를 spinner로 막지 않고 표가 들어올 자리를 그대로 남겨 둡니다. */
export function TableSkeleton({ rows = 6, columns = 4 }: { rows?: number; columns?: number }) {
  return (
    <div
      className="overflow-hidden rounded-md border border-border bg-surface"
      role="status"
      aria-label="불러오는 중입니다."
    >
      <div className="h-8 border-b border-border bg-surface-subtle" />
      {Array.from({ length: rows }).map((_, rowIndex) => (
        <div
          key={rowIndex}
          className="flex items-center gap-3 border-b border-border px-3 py-2.5 last:border-0"
        >
          {Array.from({ length: columns }).map((__, columnIndex) => (
            <span
              key={columnIndex}
              className={cn(
                'h-3 animate-pulse rounded bg-accent',
                columnIndex === 0 ? 'w-1/4' : 'w-[12%]',
              )}
            />
          ))}
        </div>
      ))}
    </div>
  )
}

export function LoadingState({ message = '불러오는 중입니다.' }: { message?: string }) {
  return (
    <p className="py-10 text-center text-body text-muted-foreground" role="status">
      {message}
    </p>
  )
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="rounded-md border border-border bg-surface px-4 py-10 text-center">
      <p className="text-body font-medium">{message}</p>
      {onRetry ? (
        <Button variant="secondary" size="sm" className="mt-3" onClick={onRetry}>
          다시 시도
        </Button>
      ) : null}
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="rounded-md border border-dashed border-border px-4 py-12 text-center">
      <p className="text-body font-medium">{title}</p>
      <p className="mx-auto mt-1 max-w-xl text-body text-muted-foreground">{description}</p>
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  )
}
