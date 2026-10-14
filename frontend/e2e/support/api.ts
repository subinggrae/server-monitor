import { request, type APIRequestContext, type APIResponse } from '@playwright/test'

import { baseURL } from './env.ts'

/** 이 접두사가 붙은 Check와 서버만 테스트 데이터로 보고 지웁니다. 사람이 만든 데이터를 지우지 않기 위해서입니다. */
const TEST_DATA_PREFIX = 'e2e-'

export const testDataName = (label: string) =>
  `${TEST_DATA_PREFIX}${label}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`

type Named = { id: number; name: string }

export async function openApi(): Promise<APIRequestContext> {
  return request.newContext({ baseURL })
}

function ensureOk(response: APIResponse, action: string) {
  if (!response.ok()) {
    throw new Error(`${action} 요청이 실패했습니다. status=${response.status()}`)
  }
}

export async function createCheck(
  api: APIRequestContext,
  input: { name: string; type: 'ICMP' | 'TCP' | 'HTTP' | 'HTTPS'; target: string; port: number | null },
): Promise<Named> {
  const response = await api.post('/api/checks', {
    data: {
      name: input.name,
      type: input.type,
      environment: 'Development',
      description: null,
      enabled: true,
      scrapeIntervalSeconds: 15,
      targets: [{ target: input.target, port: input.port, enabled: true }],
    },
  })
  ensureOk(response, 'Check 생성')
  return (await response.json()) as Named
}

export async function createServer(api: APIRequestContext, name: string): Promise<Named> {
  const response = await api.post('/api/servers', {
    data: { name, host: '127.0.0.1', environment: 'Development', description: null, enabled: true },
  })
  ensureOk(response, '서버 생성')
  return (await response.json()) as Named
}

export async function listChecks(api: APIRequestContext): Promise<Named[]> {
  const response = await api.get('/api/checks')
  ensureOk(response, 'Check 목록 조회')
  return (await response.json()) as Named[]
}

async function listServers(api: APIRequestContext): Promise<Named[]> {
  const response = await api.get('/api/servers')
  ensureOk(response, '서버 목록 조회')
  return (await response.json()) as Named[]
}

/** 이미 지워졌으면(404) 그대로 둡니다. 테스트가 화면에서 지운 뒤에도 정리 단계가 실패하지 않아야 합니다. */
export async function deleteCheck(api: APIRequestContext, id: number) {
  const response = await api.delete(`/api/checks/${id}`)
  if (response.status() !== 404) ensureOk(response, 'Check 삭제')
}

export async function deleteServer(api: APIRequestContext, id: number) {
  const response = await api.delete(`/api/servers/${id}`)
  if (response.status() !== 404) ensureOk(response, '서버 삭제')
}

/** 이전 실행이 중간에 멈춰 남긴 테스트 데이터를 지웁니다. */
export async function deleteLeftoverTestData(api: APIRequestContext) {
  for (const check of await listChecks(api)) {
    if (check.name.startsWith(TEST_DATA_PREFIX)) await deleteCheck(api, check.id)
  }
  for (const server of await listServers(api)) {
    if (server.name.startsWith(TEST_DATA_PREFIX)) await deleteServer(api, server.id)
  }
}
