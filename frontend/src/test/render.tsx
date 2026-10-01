import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router'

/** 화면과 같은 Provider로 감싸서 그립니다. 테스트마다 cache를 새로 만듭니다. */
export function renderWithProviders(ui: ReactElement) {
  const queryClient = new QueryClient({
    // 실패를 바로 확인하려고 재시도를 끕니다.
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return {
    user: userEvent.setup(),
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{ui}</MemoryRouter>
      </QueryClientProvider>,
    ),
  }
}
