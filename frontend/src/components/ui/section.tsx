import type { ReactNode } from 'react'

export function DetailList({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-[minmax(6rem,9rem)_1fr] gap-x-6 gap-y-2 text-body">
      {items.map((item) => (
        <div key={item.label} className="contents">
          <dt className="text-muted-foreground">{item.label}</dt>
          <dd className="min-w-0">{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}
