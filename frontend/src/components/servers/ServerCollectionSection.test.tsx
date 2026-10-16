import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { ServerCollectionSection } from '@/components/servers/ServerCollectionSection'
import { queryKeys, type ExporterTestResult, type ServerExporter } from '@/lib/api'
import { fakeApi, renderWithQueryClient } from '@/test/render'

const NOT_CONFIGURED_WINDOWS: ServerExporter = {
  configured: false,
  enabled: false,
  port: 9182,
  scrapeIntervalSeconds: 15,
  defaultPort: 9182,
}

const CONFIGURED: ServerExporter = {
  configured: true,
  enabled: true,
  port: 9200,
  scrapeIntervalSeconds: 120,
  defaultPort: 9100,
}

const OK_RESULT: ExporterTestResult = {
  success: true,
  outcome: 'OK',
  httpStatus: 200,
  exporterType: 'NODE_EXPORTER',
  version: '1.12.1',
  osMatches: true,
  message: 'exporter를 읽었습니다.',
  responseTimeMs: 12,
}

describe('OS exporter 수집 설정', () => {
  it('설정이 없으면 OS 기본 포트와 15초로 시작하고 수집은 꺼져 있다', async () => {
    fakeApi({ 'GET /api/servers/5/exporter': { body: NOT_CONFIGURED_WINDOWS } })

    renderWithQueryClient(<ServerCollectionSection serverId={5} os="WINDOWS" />)

    expect(await screen.findByLabelText('포트')).toHaveProperty('value', '9182')
    expect(screen.getByLabelText('수집 주기')).toHaveProperty('value', '15')
    expect(screen.getByRole('checkbox', { name: /OS exporter로 수집합니다/ })).toHaveProperty('checked', false)
    expect(screen.getByRole('link', { name: 'windows_exporter 설치 안내' }).getAttribute('href')).toBe('https://github.com/prometheus-community/windows_exporter')
  })

  it('저장하면 사용 여부, 포트, 수집 주기를 보낸다', async () => {
    const sent = fakeApi({
      'GET /api/servers/5/exporter': { body: CONFIGURED },
      'PUT /api/servers/5/exporter': { body: CONFIGURED },
    })
    const user = userEvent.setup()
    renderWithQueryClient(<ServerCollectionSection serverId={5} os="LINUX" />)

    const interval = await screen.findByLabelText('수집 주기')
    await user.clear(interval)
    await user.type(interval, '30')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => expect(sent.some((request) => request.method === 'PUT')).toBe(true))
    expect(sent.find((request) => request.method === 'PUT')?.body).toEqual({
      enabled: true,
      port: 9200,
      scrapeIntervalSeconds: 30,
    })
    expect(await screen.findByText(/저장했습니다/)).toBeTruthy()
  })

  it('범위 밖의 포트와 수집 주기는 보내지 않고 칸 아래에 오류를 보인다', async () => {
    const sent = fakeApi({ 'GET /api/servers/5/exporter': { body: CONFIGURED } })
    const user = userEvent.setup()
    renderWithQueryClient(<ServerCollectionSection serverId={5} os="LINUX" />)

    const port = await screen.findByLabelText('포트')
    await user.clear(port)
    await user.type(port, '70000')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('포트는 1에서 65535 사이 숫자로 입력해 주세요.')).toBeTruthy()
    expect(port.getAttribute('aria-invalid')).toBe('true')

    await user.clear(port)
    await user.type(port, '9100')
    const interval = screen.getByLabelText('수집 주기')
    await user.clear(interval)
    await user.type(interval, '15.5')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.')).toBeTruthy()
    expect(sent.some((request) => request.method === 'PUT')).toBe(false)
  })

  it('연결 시험은 저장하지 않은 포트로 보내고 exporter 종류와 버전을 보인다', async () => {
    const sent = fakeApi({
      'GET /api/servers/5/exporter': { body: CONFIGURED },
      'POST /api/servers/5/exporter/test': { body: OK_RESULT },
    })
    const user = userEvent.setup()
    renderWithQueryClient(<ServerCollectionSection serverId={5} os="LINUX" />)

    const port = await screen.findByLabelText('포트')
    await user.clear(port)
    await user.type(port, '9101')
    await user.click(screen.getByRole('button', { name: '연결 시험' }))

    expect(await screen.findByText('node_exporter')).toBeTruthy()
    expect(screen.getByText('1.12.1')).toBeTruthy()
    expect(sent.find((request) => request.method === 'POST')?.body).toEqual({ port: 9101 })
    expect(sent.some((request) => request.method === 'PUT')).toBe(false)
  })

  it('OS가 다르면 경고로 알린다', async () => {
    fakeApi({
      'GET /api/servers/5/exporter': { body: CONFIGURED },
      'POST /api/servers/5/exporter/test': {
        body: { ...OK_RESULT, osMatches: false, message: 'exporter는 응답했지만 서버에 고른 OS와 종류가 다릅니다.' },
      },
    })
    const user = userEvent.setup()
    renderWithQueryClient(<ServerCollectionSection serverId={5} os="WINDOWS" />)

    await user.click(await screen.findByRole('button', { name: '연결 시험' }))

    expect(await screen.findByText('exporter는 응답했지만 서버에 고른 OS와 종류가 다릅니다.')).toBeTruthy()
  })

  it('연결 시험이 실패하면 서버가 준 문구를 보인다', async () => {
    fakeApi({
      'GET /api/servers/5/exporter': { body: CONFIGURED },
      'POST /api/servers/5/exporter/test': {
        body: {
          success: false,
          outcome: 'CONNECTION_REFUSED',
          httpStatus: null,
          exporterType: null,
          version: null,
          osMatches: null,
          message: 'exporter에 연결하지 못했습니다. exporter가 실행 중인지, 포트와 방화벽을 확인해 주세요.',
          responseTimeMs: null,
        } satisfies ExporterTestResult,
      },
    })
    const user = userEvent.setup()
    renderWithQueryClient(<ServerCollectionSection serverId={5} os="LINUX" />)

    await user.click(await screen.findByRole('button', { name: '연결 시험' }))

    expect(await screen.findByText(/exporter에 연결하지 못했습니다/)).toBeTruthy()
  })
})

describe('서버 OS가 바뀐 뒤의 수집 설정', () => {
  it('수집 설정이 없으면 다시 불러온 OS 기본 포트로 폼을 새로 채운다', async () => {
    fakeApi({ 'GET /api/servers/5/exporter': { body: { ...NOT_CONFIGURED_WINDOWS, port: 9100, defaultPort: 9100 } } })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const view = render(
      <QueryClientProvider client={queryClient}>
        <ServerCollectionSection serverId={5} os="LINUX" />
      </QueryClientProvider>,
    )
    expect(await screen.findByLabelText('포트')).toHaveProperty('value', '9100')

    fakeApi({ 'GET /api/servers/5/exporter': { body: NOT_CONFIGURED_WINDOWS } })
    await queryClient.invalidateQueries({ queryKey: queryKeys.serverExporter(5) })
    view.rerender(
      <QueryClientProvider client={queryClient}>
        <ServerCollectionSection serverId={5} os="WINDOWS" />
      </QueryClientProvider>,
    )

    await waitFor(() => expect(screen.getByLabelText('포트')).toHaveProperty('value', '9182'))
    expect(screen.getByText(/작업 관리자/)).toBeTruthy()
  })
})
