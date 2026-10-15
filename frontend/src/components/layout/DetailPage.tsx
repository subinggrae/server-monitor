import type { ReactNode } from 'react'

import { SkeletonRows, StateError, type ContentState } from '@/components/layout/ContentState'
import { PageBody, PageHeader } from '@/components/PageHeader'

/**
 * 상세 화면의 틀입니다. 머리 → 요약 → 탭이나 절 순서입니다.
 * 불러오는 중이거나 실패해도 머리와 "목록으로" 링크는 남겨 둡니다.
 */
export function DetailPage({
  back,
  title,
  meta,
  actions,
  state,
  summary,
  children,
}: {
  back: { to: string; label: string }
  title?: string
  meta?: ReactNode
  actions?: ReactNode
  state: ContentState
  summary?: ReactNode
  children?: ReactNode
}) {
  const ready = state.kind === 'ready'

  return (
    <>
      <PageHeader
        back={back}
        title={title ?? (state.kind === 'loading' ? '불러오는 중…' : back.label)}
        meta={ready ? meta : undefined}
        actions={ready ? actions : undefined}
      />
      <PageBody>
        {state.kind === 'loading' ? (
          <SkeletonRows rows={4} />
        ) : state.kind === 'error' ? (
          <StateError message={state.message} onRetry={state.onRetry} />
        ) : (
          <>
            {summary}
            {children}
          </>
        )}
      </PageBody>
    </>
  )
}
