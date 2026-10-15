import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { PageBody, PageHeader } from '@/components/PageHeader'
import { ErrorState, LoadingState } from '@/components/QueryStates'
import { SnmpStateDot, StatusDot } from '@/components/StatusDot'
import { ServerFormDialog } from '@/components/servers/ServerFormDialog'
import { ServerResourceSection } from '@/components/servers/ServerResourceSection'
import { ServerSnmpSection } from '@/components/servers/ServerSnmpSection'
import { Button } from '@/components/ui/button'
import { DetailList, Section, SectionHeader } from '@/components/ui/section'
import { Tabs } from '@/components/ui/tabs'
import { apiDelete, queryKeys, serverDetailQuery, serverSnmpQuery } from '@/lib/api'

type TabValue = 'overview' | 'resources' | 'snmp'

const TABS: { value: TabValue; label: string }[] = [
  { value: 'overview', label: '개요' },
  { value: 'resources', label: '리소스' },
  { value: 'snmp', label: 'SNMP' },
]

/** Blackbox Check는 Server 소유가 아니므로 여기에 표시하지 않습니다. (CLAUDE.md 2-1번) */
export function ServerDetailPage() {
  const { serverId: rawServerId } = useParams()
  const serverId = Number(rawServerId)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const serverResult = useQuery(serverDetailQuery(serverId))
  const snmp = useQuery(serverSnmpQuery(serverId))
  const [tab, setTab] = useState<TabValue>('overview')
  const [formOpen, setFormOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const deleteMutation = useMutation({
    mutationFn: () => apiDelete(`/api/servers/${serverId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.servers })
      navigate('/infrastructure')
    },
  })

  if (serverResult.isPending) {
    return (
      <PageBody>
        <LoadingState message="서버 정보를 불러오는 중…" />
      </PageBody>
    )
  }

  if (serverResult.isError) {
    return (
      <PageBody>
        <ErrorState
          message={
            serverResult.error instanceof Error
              ? serverResult.error.message
              : '서버 정보를 불러오지 못했습니다.'
          }
          onRetry={() => serverResult.refetch()}
        />
      </PageBody>
    )
  }

  const server = serverResult.data
  const snmpConfigured = Boolean(snmp.data?.configured)

  return (
    <>
      <PageHeader
        title={server.name}
        back={{ to: '/infrastructure', label: '서버 목록' }}
        meta={
          <>
            <StatusDot status={server.status} />
            <span className="tabular">{server.host}</span>
            <span>{server.environment}</span>
            <SnmpStateDot state={server.snmpState} />
          </>
        }
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
      />

      <PageBody>
        <Tabs value={tab} onChange={setTab} items={TABS} label="서버 상세" />

        {tab === 'overview' ? (
          <Section>
            <SectionHeader title="서버 정보" />
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
          </Section>
        ) : null}

        {tab === 'resources' ? (
          <ServerResourceSection serverId={serverId} snmpConfigured={snmpConfigured} />
        ) : null}

        {tab === 'snmp' ? <ServerSnmpSection serverId={serverId} /> : null}
      </PageBody>

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
  )
}
