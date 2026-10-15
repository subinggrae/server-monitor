import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { SnmpStateDot, StatusDot } from '@/components/StatusDot'
import { queryState } from '@/components/layout/state'
import { DetailPage } from '@/components/layout/DetailPage'
import { ServerFormDialog } from '@/components/servers/ServerFormDialog'
import { ServerResourceSection } from '@/components/servers/ServerResourceSection'
import { ServerSnmpSection } from '@/components/servers/ServerSnmpSection'
import { Button } from '@/components/ui/button'
import { DetailList } from '@/components/ui/section'
import { Tabs } from '@/components/ui/tabs'
import { apiDelete, queryKeys, serverDetailQuery, serverSnmpQuery } from '@/lib/api'

type TabValue = 'resources' | 'snmp'

const TABS: { value: TabValue; label: string }[] = [
  { value: 'resources', label: '리소스' },
  { value: 'snmp', label: 'SNMP' },
]

/** 주소의 tab 값이 정한 값이 아니면 첫 탭을 엽니다. */
function tabFrom(value: string | null): TabValue {
  return TABS.find((item) => item.value === value)?.value ?? TABS[0].value
}

/** Blackbox Check는 Server 소유가 아니므로 여기에 표시하지 않습니다. (CLAUDE.md 2-1번) */
export function ServerDetailPage() {
  const { serverId: rawServerId } = useParams()
  const serverId = Number(rawServerId)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  const serverResult = useQuery(serverDetailQuery(serverId))
  const snmp = useQuery(serverSnmpQuery(serverId))
  const tab = tabFrom(searchParams.get('tab'))
  const [formOpen, setFormOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const deleteMutation = useMutation({
    mutationFn: () => apiDelete(`/api/servers/${serverId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.servers })
      navigate('/infrastructure')
    },
  })

  const changeTab = (value: TabValue) => {
    setSearchParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        next.set('tab', value)
        return next
      },
      { replace: true },
    )
  }

  const server = serverResult.data
  const snmpConfigured = Boolean(snmp.data?.configured)

  return (
    <>
      <DetailPage
        back={{ to: '/infrastructure', label: '서버 목록' }}
        title={server?.name}
        state={queryState(
          serverResult,
          serverResult.error instanceof Error ? serverResult.error.message : '서버 정보를 불러오지 못했습니다.',
        )}
        meta={server ? <StatusDot status={server.status} /> : undefined}
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
            <div className="space-y-3">
              <DetailList
                items={[
                  { label: '호스트', value: <span className="tabular">{server.host}</span> },
                  { label: '환경', value: server.environment },
                  { label: '감시', value: server.enabled ? '사용' : '사용 안 함' },
                  { label: 'SNMP', value: <SnmpStateDot state={server.snmpState} /> },
                  { label: '리소스', value: `${server.monitoredResourceCount}개` },
                  ...(server.description ? [{ label: '설명', value: server.description }] : []),
                ]}
              />
              <p className="text-caption text-muted-foreground">
                아직 임계값이 없어 사용률만으로 위험 여부를 판단하지 않습니다. SNMP로 값을 읽고 있으면
                정상으로 봅니다.
              </p>
            </div>
          ) : undefined
        }
      >
        <Tabs value={tab} onChange={changeTab} items={TABS} label="서버 상세" />

        {tab === 'resources' ? (
          <ServerResourceSection serverId={serverId} snmpConfigured={snmpConfigured} />
        ) : null}

        {tab === 'snmp' ? <ServerSnmpSection serverId={serverId} /> : null}
      </DetailPage>

      {server ? (
        <>
          <ServerFormDialog open={formOpen} onOpenChange={setFormOpen} server={server} />

          <ConfirmDialog
            open={deleting}
            onOpenChange={setDeleting}
            title="이 서버를 삭제할까요?"
            description={
              'SNMP 설정과 고른 리소스가 함께 삭제됩니다.\n감시 항목은 서버에 속하지 않으므로 그대로 남습니다.'
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
