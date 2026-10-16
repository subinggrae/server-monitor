import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { CollectionStateDot } from '@/components/StatusDot'
import { queryState } from '@/components/layout/state'
import { DetailPage } from '@/components/layout/DetailPage'
import { ServerFormDialog } from '@/components/servers/ServerFormDialog'
import { ServerCollectionSection } from '@/components/servers/ServerCollectionSection'
import { ServerResourceSection } from '@/components/servers/ServerResourceSection'
import { Button } from '@/components/ui/button'
import { DetailList } from '@/components/ui/section'
import { Tabs } from '@/components/ui/tabs'
import { apiDelete, queryKeys, serverDetailQuery, serverExporterQuery, SERVER_OS_LABELS } from '@/lib/api'
import { pickParam, useSearchParamState } from '@/lib/searchParams'

type TabValue = 'resources' | 'collection'

const TABS: { value: TabValue; label: string }[] = [
  { value: 'resources', label: '리소스' },
  { value: 'collection', label: '수집' },
]

/**
 * Blackbox Check는 Server 소유가 아니므로 여기에 표시하지 않습니다. (CLAUDE.md 2-1번)
 * 서버가 살아 있는지도 감시 항목이 보입니다. 여기의 상태는 자원 수집 상태뿐입니다.
 */
export function ServerDetailPage() {
  const { serverId: rawServerId } = useParams()
  const serverId = Number(rawServerId)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { params, update } = useSearchParamState()

  const serverResult = useQuery(serverDetailQuery(serverId))
  const exporter = useQuery(serverExporterQuery(serverId))
  // 주소의 tab 값이 정한 값이 아니면 첫 탭을 엽니다.
  const tab = pickParam(params.get('tab'), TABS.map((item) => item.value)) ?? TABS[0].value
  const [formOpen, setFormOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const deleteMutation = useMutation({
    mutationFn: () => apiDelete(`/api/servers/${serverId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.servers })
      navigate('/infrastructure')
    },
  })

  const changeTab = (value: TabValue) => update({ tab: value })

  const server = serverResult.data
  const collectionEnabled = Boolean(exporter.data?.configured && exporter.data.enabled)

  return (
    <>
      <DetailPage
        back={{ to: '/infrastructure', label: '서버 목록' }}
        title={server?.name}
        state={queryState(
          serverResult,
          serverResult.error instanceof Error ? serverResult.error.message : '서버 정보를 불러오지 못했습니다.',
        )}
        meta={server ? <CollectionStateDot state={server.collectionState} /> : undefined}
        actions={
          <>
            <Button variant="secondary" onClick={() => setFormOpen(true)}>
              수정
            </Button>
            <Button variant="destructive-ghost" onClick={() => setDeleting(true)}>
              삭제
            </Button>
          </>
        }
        summary={
          server ? (
            <DetailList
              items={[
                { label: '호스트', value: <span className="tabular">{server.host}</span> },
                { label: 'OS', value: SERVER_OS_LABELS[server.os] },
                { label: '환경', value: server.environment },
                { label: '감시', value: server.enabled ? '사용' : '사용 안 함' },
                { label: '수집', value: <CollectionStateDot state={server.collectionState} /> },
                { label: '리소스', value: `${server.monitoredResourceCount}개` },
                ...(server.description ? [{ label: '설명', value: server.description }] : []),
              ]}
            />
          ) : undefined
        }
      >
        <Tabs value={tab} onChange={changeTab} items={TABS} label="서버 상세" />

        {tab === 'resources' ? (
          <ServerResourceSection
            serverId={serverId}
            collectionEnabled={collectionEnabled}
            collectionFailed={server?.collectionState === 'FAILED'}
            onOpenCollection={() => changeTab('collection')}
          />
        ) : null}

        {tab === 'collection' && server ? <ServerCollectionSection serverId={serverId} os={server.os} /> : null}
      </DetailPage>

      {server ? (
        <>
          <ServerFormDialog open={formOpen} onOpenChange={setFormOpen} server={server} />

          <ConfirmDialog
            open={deleting}
            onOpenChange={setDeleting}
            title="이 서버를 삭제할까요?"
            description={
              '수집 설정과 고른 리소스가 함께 삭제됩니다.\n감시 항목은 서버에 속하지 않으므로 그대로 남습니다.'
            }
            pending={deleteMutation.isPending}
            errorMessage={deleteMutation.isError ? '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.' : undefined}
            onConfirm={() => deleteMutation.mutate()}
          />
        </>
      ) : null}
    </>
  )
}
