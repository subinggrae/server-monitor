import type { ComponentProps, ReactNode } from 'react'

import { cn } from '@/lib/utils'

/** 영역은 Card가 아니라 제목과 여백으로 나눕니다. 테두리는 표와 목록에만 씁니다. */
export function Section({ className, ...props }: ComponentProps<'section'>) {
  return <section className={cn('space-y-2.5', className)} {...props} />
}

export function SectionHeader({
  title,
  description,
  actions,
}: {
  title: string
  description?: string
  actions?: ReactNode
}) {
  return (
    <div className="flex items-end justify-between gap-4">
      <div>
        <h2 className="text-[13px] font-semibold">{title}</h2>
        {description ? <p className="mt-0.5 text-xs text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-1.5">{actions}</div> : null}
    </div>
  )
}

export function DetailList({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-[minmax(6rem,9rem)_1fr] gap-x-6 gap-y-2 text-[13px]">
      {items.map((item) => (
        <div key={item.label} className="contents">
          <dt className="text-muted-foreground">{item.label}</dt>
          <dd className="min-w-0">{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}

/** 아직 만들지 않은 기능 자리입니다. 있는 것처럼 꾸미지 않습니다. */
export function NotBuiltYet({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-md border border-dashed border-border px-3 py-6 text-center text-[13px] text-muted-foreground">
      {children}
    </p>
  )
}
