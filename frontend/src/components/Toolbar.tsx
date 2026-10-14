import type { ReactNode } from 'react'

export function Toolbar({ children, count }: { children: ReactNode; count?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      {children}
      {count ? <span className="ml-auto text-xs text-muted-foreground">{count}</span> : null}
    </div>
  )
}
