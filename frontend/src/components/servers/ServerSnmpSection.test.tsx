import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { ServerSnmpSection } from '@/components/servers/ServerSnmpSection'
import type { ServerSnmp } from '@/lib/api'
import { fakeApi, renderWithQueryClient } from '@/test/render'

const NOT_CONFIGURED: ServerSnmp = {
  configured: false,
  version: null,
  port: null,
  communityConfigured: false,
  enabled: false,
  scrapeIntervalSeconds: null,
  exporterSynced: true,
  exporterMessage: null,
}

const CONFIGURED: ServerSnmp = {
  configured: true,
  version: 'V2C',
  port: 161,
  communityConfigured: true,
  enabled: true,
  scrapeIntervalSeconds: 120,
  exporterSynced: true,
  exporterMessage: null,
}

const OUT_OF_RANGE_MESSAGE = '수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.'

async function saveWithInterval(value: string) {
  const user = userEvent.setup()
  const interval = await screen.findByLabelText('수집 주기')
  await user.clear(interval)
  await user.type(interval, value)
  await user.click(screen.getByRole('button', { name: '저장' }))
}

describe('SNMP 설정의 수집 주기', () => {
  it('SNMP 설정이 없으면 15초로 시작한다', async () => {
    fakeApi({ 'GET /api/servers/5/snmp': { body: NOT_CONFIGURED } })

    renderWithQueryClient(<ServerSnmpSection serverId={5} />)

    expect(await screen.findByLabelText('수집 주기')).toHaveProperty('value', '15')
  })

  it('저장한 수집 주기를 보인다', async () => {
    fakeApi({ 'GET /api/servers/5/snmp': { body: CONFIGURED } })

    renderWithQueryClient(<ServerSnmpSection serverId={5} />)

    expect(await screen.findByLabelText('수집 주기')).toHaveProperty('value', '120')
  })

  it('입력한 수집 주기를 저장 요청에 담는다', async () => {
    const sent = fakeApi({
      'GET /api/servers/5/snmp': { body: CONFIGURED },
      'PUT /api/servers/5/snmp': { body: { ...CONFIGURED, scrapeIntervalSeconds: 30 } },
    })
    renderWithQueryClient(<ServerSnmpSection serverId={5} />)

    await saveWithInterval('30')

    await waitFor(() => {
      expect(sent.find((request) => request.method === 'PUT')?.body).toMatchObject({
        scrapeIntervalSeconds: 30,
      })
    })
  })

  it.each(['9', '241', '15.5'])('%s초면 저장하지 않고 입력 칸 아래에 허용 범위를 보인다', async (value) => {
    const sent = fakeApi({ 'GET /api/servers/5/snmp': { body: CONFIGURED } })
    renderWithQueryClient(<ServerSnmpSection serverId={5} />)

    await saveWithInterval(value)

    expect(await screen.findByText(OUT_OF_RANGE_MESSAGE)).toBeTruthy()
    expect(screen.getByLabelText('수집 주기').getAttribute('aria-invalid')).toBe('true')
    expect(sent.some((request) => request.method === 'PUT')).toBe(false)
  })
})
