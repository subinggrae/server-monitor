import type { ReactNode } from 'react'

import { PageBody, PageHeader } from '@/components/PageHeader'

/** 설정 화면의 틀입니다. 머리 아래에 PageSection을 차례로 둡니다. 절마다 머리 모양이 같습니다. */
export function SettingsPage({
  title,
  description,
  children,
}: {
  title: string
  description?: string
  children: ReactNode
}) {
  return (
    <>
      <PageHeader title={title} description={description} />
      <PageBody>{children}</PageBody>
    </>
  )
}
