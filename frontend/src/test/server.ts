import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

import type { CheckTypeCapability } from '@/lib/api'

export const CHECK_TYPES: CheckTypeCapability[] = [
  { type: 'ICMP', displayName: 'Ping', requiresPort: false, requiresUrl: false },
  { type: 'TCP', displayName: 'TCP', requiresPort: true, requiresUrl: false },
  { type: 'HTTP', displayName: 'HTTP', requiresPort: false, requiresUrl: true },
  { type: 'HTTPS', displayName: 'HTTPS', requiresPort: false, requiresUrl: true },
]

/**
 * 테스트가 Backend 대신 응답합니다.
 * 처리하지 않은 요청은 setup.ts에서 오류로 만들어서 테스트가 밖으로 요청을 보내지 못하게 합니다.
 */
export const server = setupServer(http.get('/api/check-types', () => HttpResponse.json(CHECK_TYPES)))

/** Backend의 ProblemDetail 오류 응답과 같은 모양입니다. */
export function problem(status: number, detail: string, fieldErrors?: Record<string, string>) {
  return HttpResponse.json({ title: 'Bad Request', status, detail, fieldErrors }, { status })
}
