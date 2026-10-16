import type { ReactNode } from 'react'

import { SkeletonRows, StateError, type ContentState } from '@/components/layout/ContentState'
import { Button } from '@/components/ui/button'

/**
 * 절 하나의 틀입니다. 불러오는 중이거나 실패해도 절을 숨기지 않습니다.
 * 절 머리의 동작은 폼 밖의 동작(검색, 해제 등)만 둡니다. 저장은 FormActions로 폼 아래에 둡니다.
 */
export function PageSection({
  title,
  description,
  actions,
  state = { kind: 'ready' },
  children,
}: {
  title: string
  description?: ReactNode
  actions?: ReactNode
  state?: ContentState
  children?: ReactNode
}) {
  return (
    <section className="space-y-3">
      <div className="flex items-end justify-between gap-4">
        <div className="min-w-0">
          <h2 className="text-section font-semibold">{title}</h2>
          {description ? <p className="mt-1 text-body text-muted-foreground">{description}</p> : null}
        </div>
        {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
      </div>
      {state.kind === 'loading' ? (
        <SkeletonRows />
      ) : state.kind === 'error' ? (
        <StateError message={state.message} onRetry={state.onRetry} />
      ) : state.kind === 'empty' ? (
        <p className="text-body text-muted-foreground">{state.title}</p>
      ) : (
        children
      )}
    </section>
  )
}

/** 절 안 폼의 아래 막대입니다. 저장 버튼은 왼쪽에, 결과 문구는 그 옆에 둡니다. */
export function FormActions({
  submitLabel,
  pendingLabel,
  pending,
  result,
  children,
}: {
  submitLabel: string
  pendingLabel: string
  pending: boolean
  result?: { kind: 'success' | 'error'; text: string } | null
  children?: ReactNode
}) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <Button type="submit" disabled={pending}>
        {pending ? pendingLabel : submitLabel}
      </Button>
      {children}
      <p
        aria-live="polite"
        className={result?.kind === 'error' ? 'text-body text-destructive' : 'text-body text-muted-foreground'}
      >
        {result?.text ?? ''}
      </p>
    </div>
  )
}
