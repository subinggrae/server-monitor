import { deleteLeftoverTestData, openApi } from './support/api.ts'
import { baseURL } from './support/env.ts'

const CONNECT_TIMEOUT_MS = 10_000

export default async function globalSetup() {
  const api = await openApi()
  try {
    try {
      const response = await api.get('/api/checks', { timeout: CONNECT_TIMEOUT_MS })
      if (!response.ok()) throw new Error(`status=${response.status()}`)
    } catch (cause) {
      throw new Error(
        `${baseURL}에 접속하지 못했습니다. docker compose --profile local-db up -d --build 로 전체 실행 환경을 띄운 뒤 다시 실행하세요.`,
        { cause },
      )
    }
    await deleteLeftoverTestData(api)
  } finally {
    await api.dispose()
  }
}
