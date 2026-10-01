import '@testing-library/jest-dom/vitest'

import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'

import { server } from './server'

/**
 * 처리하지 않은 요청은 네트워크 오류가 됩니다. 화면은 그 오류를 안내 문구로 바꾸므로
 * 그것만으로는 테스트가 실패하지 않을 수 있습니다. 그래서 테스트가 끝날 때 따로 확인합니다.
 */
const unhandledRequests: string[] = []

beforeAll(() => {
  server.events.on('request:unhandled', ({ request }) => {
    unhandledRequests.push(`${request.method} ${request.url}`)
  })
  server.listen({ onUnhandledRequest: 'error' })
})

afterEach(() => {
  cleanup()
  server.resetHandlers()

  const requests = unhandledRequests.splice(0)
  if (requests.length > 0) {
    throw new Error(`처리하지 않은 요청이 있습니다: ${requests.join(', ')}`)
  }
})

afterAll(() => server.close())
