import { cn } from '@/lib/utils'

/** 화면 전체를 spinner로 막지 않고 표가 들어올 자리를 그대로 남겨 둡니다. */
export function TableSkeleton({ rows = 6, columns = 4 }: { rows?: number; columns?: number }) {
  return (
    <div
      className="overflow-hidden rounded-md border border-border bg-surface"
      role="status"
      aria-label="불러오는 중…"
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
