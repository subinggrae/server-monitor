import type { ReactNode } from 'react'

import { PageBody, PageHeader } from '@/components/PageHeader'

/**
 * 개요 화면(대시보드)의 틀입니다. 머리 → 핵심 숫자 → 우선 절 → 나머지 절 순서입니다.
 * 절은 PageSection으로 넣고, 가장 먼저 볼 절을 첫 번째에 둡니다.
 */
export function OverviewPage({
  title,
  description,
  freshness,
  actions,
  figure,
  children,
}: {
  title: string
  description?: string
  freshness?: ReactNode
  actions?: ReactNode
  figure?: ReactNode
  children: ReactNode
}) {
  return (
    <>
      <PageHeader title={title} description={description} meta={freshness} actions={actions} />
      <PageBody>
        {figure}
        {children}
      </PageBody>
    </>
  )
}
