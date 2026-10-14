import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { vi } from 'vitest'

type Reply = { status?: number; body?: unknown }

export type SentRequest = { method: string; path: string; body: unknown }

/**
 * `GET /api/checks/1`처럼 method와 경로마다 정한 응답을 돌려주는 fetch 대역입니다.
 * 정하지 않은 요청에는 404를 돌려주고, 보낸 요청은 모두 기록합니다.
 */
export function fakeApi(replies: Record<string, Reply>): SentRequest[] {
  const sent: SentRequest[] = []

  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      sent.push({ method, path, body: init?.body ? JSON.parse(String(init.body)) : undefined })

      const reply = replies[`${method} ${path}`]
      if (!reply) {
        return new Response(null, { status: 404 })
      }
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status ?? 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }),
  )

  return sent
}

/** 테스트마다 새 QueryClient를 써서 앞 테스트의 캐시가 섞이지 않게 합니다. */
export function renderWithQueryClient(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}
