import { useSearchParams } from 'react-router'

/**
 * 필터, 정렬, 탭처럼 다시 열었을 때 그대로 보여야 하는 화면 상태를 URL 검색 매개변수에 둡니다.
 * 바꿀 때는 방문 기록을 쌓지 않습니다. 빈 값과 null은 매개변수를 지웁니다.
 */
export function useSearchParamState() {
  const [params, setParams] = useSearchParams()

  const update = (patch: Record<string, string | null>) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        for (const [key, value] of Object.entries(patch)) {
          if (value === null || value === '') {
            next.delete(key)
          } else {
            next.set(key, value)
          }
        }
        return next
      },
      { replace: true },
    )

  return { params, update }
}

/** 주소에서 온 값은 정해 둔 값일 때만 씁니다. 아니면 null입니다. */
export function pickParam<T extends string>(value: string | null, allowed: readonly T[]): T | null {
  return value !== null && (allowed as readonly string[]).includes(value) ? (value as T) : null
}
