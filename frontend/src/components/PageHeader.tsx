import { ChevronLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'

/** 화면이 넓어도 표가 지나치게 늘어나지 않도록 본문 너비를 제한합니다. */
const CONTENT_WIDTH = 'mx-auto w-full max-w-[1232px]'

type PageHeaderProps = {
  title: string
  description?: string
  meta?: ReactNode
  back?: { to: string; label: string }
  actions?: ReactNode
}

export function PageHeader({ title, description, meta, back, actions }: PageHeaderProps) {
  return (
    <header className="border-b border-border bg-surface px-6 pb-4 pt-5">
      <div className={CONTENT_WIDTH}>
        {back ? (
          <Link
            to={back.to}
            className="-ml-1 mb-2 inline-flex items-center gap-0.5 text-xs text-muted-foreground hover:text-foreground"
          >
            <ChevronLeft className="size-3.5" aria-hidden />
            {back.label}
          </Link>
        ) : null}

        <div className="flex items-start justify-between gap-6">
          <div className="min-w-0">
            <h1 className="truncate text-[17px] font-semibold tracking-[-0.01em]">{title}</h1>
            {description ? (
              <p className="mt-1 max-w-2xl text-[13px] text-muted-foreground">{description}</p>
            ) : null}
            {meta ? (
              <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[13px] text-muted-foreground">
                {meta}
              </div>
            ) : null}
          </div>
          {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
        </div>
      </div>
    </header>
  )
}

export function PageBody({ children }: { children: ReactNode }) {
  return (
    <div className="px-6 py-5">
      <div className={`${CONTENT_WIDTH} space-y-7`}>{children}</div>
    </div>
  )
}
