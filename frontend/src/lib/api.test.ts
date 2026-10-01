import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { ApiError, apiDelete, apiGet, apiPost } from '@/lib/api'
import { problem, server } from '@/test/server'

const FALLBACK_MESSAGE = '서버와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.'

async function caught(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => null,
    (thrown: unknown) => thrown,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

describe('api', () => {
  it('returnsJsonBodyOnSuccess', async () => {
    server.use(http.get('/api/servers', () => HttpResponse.json([{ id: 1, name: 'WAS-01' }])))

    await expect(apiGet('/api/servers')).resolves.toEqual([{ id: 1, name: 'WAS-01' }])
  })

  it('sendsBodyAsJson', async () => {
    let received: { contentType: string | null; body: unknown } | null = null
    server.use(
      http.post('/api/servers', async ({ request }) => {
        received = { contentType: request.headers.get('Content-Type'), body: await request.json() }
        return HttpResponse.json({ id: 1 }, { status: 201 })
      }),
    )

    await apiPost('/api/servers', { name: 'WAS-01' })

    expect(received).toEqual({ contentType: 'application/json', body: { name: 'WAS-01' } })
  })

  it('treatsNoContentAsSuccessWithoutBody', async () => {
    server.use(http.delete('/api/servers/1', () => new HttpResponse(null, { status: 204 })))

    await expect(apiDelete('/api/servers/1')).resolves.toBeUndefined()
  })

  it('readsDetailAndFieldErrorsFromProblemResponse', async () => {
    server.use(
      http.post('/api/servers', () =>
        problem(400, '입력값을 확인해 주세요.', { host: 'IP 또는 hostname을 입력해 주세요.' }),
      ),
    )

    const error = await caught(apiPost('/api/servers', {}))

    expect(error.status).toBe(400)
    expect(error.message).toBe('입력값을 확인해 주세요.')
    expect(error.fieldErrors).toEqual({ host: 'IP 또는 hostname을 입력해 주세요.' })
  })

  it('usesFallbackMessageWhenErrorHasNoBody', async () => {
    server.use(http.get('/api/servers', () => new HttpResponse('Bad Gateway', { status: 502 })))

    const error = await caught(apiGet('/api/servers'))

    expect(error.status).toBe(502)
    expect(error.message).toBe(FALLBACK_MESSAGE)
    expect(error.fieldErrors).toEqual({})
  })

  it('usesFallbackMessageWhenNetworkFails', async () => {
    server.use(http.get('/api/servers', () => HttpResponse.error()))

    const error = await caught(apiGet('/api/servers'))

    // status 0은 응답을 받지 못했다는 뜻입니다.
    expect(error.status).toBe(0)
    expect(error.message).toBe(FALLBACK_MESSAGE)
  })
})
