import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { ServerResourceSection } from '@/components/servers/ServerResourceSection'
import { fakeApi, renderWithQueryClient } from '@/test/render'

describe('리소스 절의 다음 동작', () => {
  it('수집이 꺼져 있으면 리소스 찾기를 막고 수집 설정으로 보낸다', async () => {
    fakeApi({ 'GET /api/servers/5/resources': { body: [] } })
    const onOpenCollection = vi.fn()
    renderWithQueryClient(
      <ServerResourceSection serverId={5} collectionEnabled={false} collectionFailed={false} onOpenCollection={onOpenCollection} />,
    )

    expect(await screen.findByText('OS exporter 수집이 꺼져 있어 리소스를 찾을 수 없습니다.')).toBeTruthy()
    expect(screen.getByRole('button', { name: '리소스 찾기' })).toHaveProperty('disabled', true)
    await userEvent.setup().click(screen.getByRole('button', { name: '수집 설정 열기' }))
    expect(onOpenCollection).toHaveBeenCalledOnce()
  })

  it('수집이 실패하면 경고와 수집 설정으로 가는 동작을 보이고 빈 상태 문구는 되풀이하지 않는다', async () => {
    fakeApi({ 'GET /api/servers/5/resources': { body: [] } })
    renderWithQueryClient(
      <ServerResourceSection serverId={5} collectionEnabled collectionFailed onOpenCollection={() => {}} />,
    )

    expect(await screen.findByText('exporter를 읽지 못하고 있습니다.')).toBeTruthy()
    expect(screen.getByRole('button', { name: '수집 설정 열기' })).toBeTruthy()
    expect(screen.queryByText(/감시하는 리소스가 없습니다/)).toBeNull()
  })

  it('수집 중이고 고른 리소스가 없으면 리소스 찾기를 안내한다', async () => {
    fakeApi({ 'GET /api/servers/5/resources': { body: [] } })
    renderWithQueryClient(
      <ServerResourceSection serverId={5} collectionEnabled collectionFailed={false} onOpenCollection={() => {}} />,
    )

    expect(await screen.findByText('감시하는 리소스가 없습니다. [리소스 찾기]로 CPU, Memory, Storage를 고릅니다.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '수집 설정 열기' })).toBeNull()
  })
})
