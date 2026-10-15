import { useState } from 'react'

import type { ContentState } from '@/components/layout/ContentState'

/**
 * 조회 결과를 틀이 그릴 상태로 바꿉니다. 실패하면 다시 시도로 같은 조회를 다시 보냅니다.
 * empty를 넘기면 빈 상태로 그립니다. 비었는지는 화면이 판단합니다.
 */
export function queryState(
  query: { isPending: boolean; isError: boolean; refetch: () => unknown },
  errorMessage: string,
  empty?: { title: string; description?: string } | null,
): ContentState {
  if (query.isPending) {
    return { kind: 'loading' }
  }
  if (query.isError) {
    return { kind: 'error', message: errorMessage, onRetry: () => void query.refetch() }
  }
  if (empty) {
    return { kind: 'empty', ...empty }
  }
  return { kind: 'ready' }
}

/**
 * 대화상자를 열 때마다 1씩 늘어나는 값입니다. 폼 상태를 가진 컴포넌트의 key로 쓰면
 * 열 때마다 입력값과 오류가 처음 상태로 돌아갑니다. 닫을 때는 바뀌지 않아 닫히는 동안 내용이 그대로 남습니다.
 */
export function useOpenCount(open: boolean) {
  const [tracked, setTracked] = useState({ open, count: 0 })
  if (tracked.open !== open) {
    setTracked({ open, count: open ? tracked.count + 1 : tracked.count })
  }
  return tracked.count
}
