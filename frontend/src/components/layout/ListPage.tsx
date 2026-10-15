import type { ReactNode } from 'react'

import { StateEmpty, StateError, type ContentState, type PageAction } from '@/components/layout/ContentState'
import { PageBody, PageHeader } from '@/components/PageHeader'
import { TableSkeleton } from '@/components/QueryStates'
import { Button } from '@/components/ui/button'

/**
 * 목록 화면의 틀입니다. 머리 → 알림 띠 → 도구 막대 → 표 → 보조 절 순서입니다.
 * 화면 전체의 안내와 경고는 notice에만 둡니다. 표 아래에 문단을 두지 않습니다.
 */
export function ListPage({
  title,
  description,
  primaryAction,
  notice,
  toolbar,
  state,
  skeletonColumns = 5,
  aside,
  children,
}: {
  title: string
  description?: string
  primaryAction?: PageAction
  notice?: ReactNode
  toolbar?: ReactNode
  state: ContentState
  skeletonColumns?: number
  aside?: ReactNode
  children: ReactNode
}) {
  const actionButton = primaryAction ? (
    <Button onClick={primaryAction.onClick} disabled={primaryAction.disabled}>
      {primaryAction.label}
    </Button>
  ) : null

  return (
    <>
      <PageHeader title={title} description={description} actions={actionButton} />
      <PageBody>
        {notice ? <div>{notice}</div> : null}
        {state.kind === 'loading' ? (
          <TableSkeleton columns={skeletonColumns} />
        ) : state.kind === 'error' ? (
          <StateError message={state.message} onRetry={state.onRetry} />
        ) : state.kind === 'empty' ? (
          <StateEmpty title={state.title} description={state.description} action={actionButton} />
        ) : (
          <div className="space-y-3">
            {toolbar}
            {children}
          </div>
        )}
        {aside}
      </PageBody>
    </>
  )
}
