import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'

import { ServerSnmpSection } from '@/components/servers/ServerSnmpSection'
import type { ServerSnmp, ServerSnmpInput } from '@/lib/api'
import { renderWithProviders } from '@/test/render'
import { server } from '@/test/server'

const CONFIGURED: ServerSnmp = {
  configured: true,
  version: 'V2C',
  port: 161,
  communityConfigured: true,
  enabled: true,
  exporterSynced: true,
  exporterMessage: null,
}

const EXPORTER_WARNING = 'snmp_exporter에 반영되지 않았습니다.'

function givenSnmp(snmp: ServerSnmp) {
  server.use(http.get('/api/servers/3/snmp', () => HttpResponse.json(snmp)))
}

describe('ServerSnmpSection', () => {
  it('sendsNullCommunityWhenLeftBlankToKeepTheSavedOne', async () => {
    givenSnmp(CONFIGURED)
    const bodies: ServerSnmpInput[] = []
    server.use(
      http.put('/api/servers/3/snmp', async ({ request }) => {
        bodies.push((await request.json()) as ServerSnmpInput)
        return HttpResponse.json(CONFIGURED)
      }),
    )
    const { user } = renderWithProviders(<ServerSnmpSection serverId={3} />)

    // 서버는 community를 돌려주지 않습니다. 칸은 비어 있고 설정 여부만 안내합니다.
    expect(await screen.findByLabelText('Community')).toHaveValue('')
    expect(screen.getByText('설정되어 있습니다. 바꿀 때만 새 값을 입력해 주세요.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '저장' }))

    await vi.waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toEqual({ version: 'V2C', port: 161, community: null, enabled: true })
  })

  it('warnsWhenSavedConfigurationIsNotAppliedToExporter', async () => {
    givenSnmp({
      ...CONFIGURED,
      exporterSynced: false,
      exporterMessage: 'snmp_exporter에 reload를 요청하지 못했습니다.',
    })

    renderWithProviders(<ServerSnmpSection serverId={3} />)

    expect(await screen.findByText(EXPORTER_WARNING)).toBeInTheDocument()
    expect(screen.getByText(/snmp_exporter에 reload를 요청하지 못했습니다\./)).toBeInTheDocument()
  })

  it('doesNotWarnWhenExporterIsInSync', async () => {
    givenSnmp(CONFIGURED)

    renderWithProviders(<ServerSnmpSection serverId={3} />)

    await screen.findByLabelText('Community')
    expect(screen.queryByText(EXPORTER_WARNING)).not.toBeInTheDocument()
  })
})
