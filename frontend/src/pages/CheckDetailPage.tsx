import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import { RecentAlertTable } from '@/components/alerts/RecentAlertTable'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { StatusDot } from '@/components/StatusDot'
import { CheckFormDialog } from '@/components/checks/CheckFormDialog'
import { CheckNotificationSection } from '@/components/checks/CheckNotificationSection'
import { queryState } from '@/components/layout/state'
import { DetailPage } from '@/components/layout/DetailPage'
import { PageSection } from '@/components/layout/PageSection'
import { Button } from '@/components/ui/button'
import { DetailList } from '@/components/ui/section'
import { Table, TableFrame, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { firingSince } from '@/lib/alerts'
import { alertsQuery, apiDelete, checkDetailQuery, queryKeys, type MonitoringCheck } from '@/lib/api'
import { formatDateTime, formatElapsed, formatTime } from '@/lib/format'

const RECENT_ALERT_LIMIT = 5

const REFRESH_INTERVAL = 30_000

/** 대상마다 따로 확인하므로 가장 늦게 확인한 대상의 시각을 씁니다. */
function latestCheckedAt(check: MonitoringCheck | undefined) {
  const times = (check?.targets ?? []).flatMap((target) => (target.checkedAt ? [target.checkedAt] : []))
  return times.length === 0 ? null : times.reduce((latest, time) => (new Date(time) > new Date(latest) ? time : latest))
}

export function CheckDetailPage() {
  const { checkId: rawCheckId } = useParams()
  const checkId = Number(rawCheckId)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // 머리의 지속 시간과 마지막 확인 시각이 열어 둔 채로도 바뀌어야 합니다. 대시보드와 같은 간격입니다.
  const checkResult = useQuery({ ...checkDetailQuery(checkId), refetchInterval: REFRESH_INTERVAL })
  const [formOpen, setFormOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const deleteMutation = useMutation({
    mutationFn: () => apiDelete(`/api/checks/${checkId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.checks })
      navigate('/monitoring')
    },
  })

  const check = checkResult.data

  // 장애 API는 최근 기록만 돌려줍니다. 그 안에서 이 감시 항목의 것만 고릅니다.
  const alerts = useQuery({ ...alertsQuery, refetchInterval: REFRESH_INTERVAL })
  const recentAlerts = (alerts.data ?? []).filter((alert) => alert.checkId === checkId).slice(0, RECENT_ALERT_LIMIT)
  const since = firingSince(alerts.data ?? []).get(checkId)
  const lastCheckedAt = latestCheckedAt(check)

  return (
    <>
      <DetailPage
        back={{ to: '/monitoring', label: '감시 항목 목록' }}
        title={check?.name}
        state={queryState(
          checkResult,
          checkResult.error instanceof Error ? checkResult.error.message : '감시 항목을 불러오지 못했습니다.',
        )}
        meta={
          check ? (
            <>
              <StatusDot status={check.status} />
              {check.status === 'CRITICAL' ? (
                since ? (
                  <span className="tabular font-medium text-foreground" title={formatDateTime(since)}>
                    {formatElapsed(since)}
                  </span>
                ) : (
                  <span>시작 시각 모름</span>
                )
              ) : null}
              {lastCheckedAt ? <span className="tabular">마지막 확인 {formatTime(lastCheckedAt)}</span> : null}
            </>
          ) : undefined
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
        summary={
          check ? (
            <DetailList
              items={[
                { label: '종류', value: check.typeDisplayName },
                { label: '환경', value: check.environment },
                { label: '수집 주기', value: `${check.scrapeIntervalSeconds}초` },
                ...(check.description ? [{ label: '설명', value: check.description }] : []),
              ]}
            />
          ) : undefined
        }
      >
        {check ? (
          <>
            <PageSection title="확인 대상" description={`확인하는 주소 ${check.targetCount}개입니다.`}>
              <TableFrame>
                <Table>
                  <THead>
                    <tr>
                      <TH>주소</TH>
                      <TH className="w-28">사용 여부</TH>
                      <TH className="w-32">상태</TH>
                    </tr>
                  </THead>
                  <TBody>
                    {check.targets.map((target) => (
                      <TR key={target.id}>
                        <TD className="tabular break-all">{target.blackboxTarget}</TD>
                        <TD className="text-muted-foreground">{target.enabled ? '사용' : '사용 안 함'}</TD>
                        <TD>
                          <StatusDot status={target.status} />
                        </TD>
                      </TR>
                    ))}
                  </TBody>
                </Table>
              </TableFrame>
            </PageSection>

            <PageSection
              title="최근 장애"
              actions={
                <Link
                  to="/alerts"
                  className="text-body text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
                >
                  장애 기록 전체
                </Link>
              }
              state={queryState(
                alerts,
                '장애 기록을 불러오지 못했습니다.',
                recentAlerts.length === 0 ? { title: '최근에 기록된 장애가 없습니다.' } : null,
              )}
            >
              <RecentAlertTable alerts={recentAlerts} />
            </PageSection>

            <CheckNotificationSection checkId={checkId} />
          </>
        ) : null}
      </DetailPage>

      {check ? (
        <>
          <CheckFormDialog open={formOpen} onOpenChange={setFormOpen} check={check} />

          <ConfirmDialog
            open={deleting}
            onOpenChange={setDeleting}
            title="이 감시 항목을 삭제할까요?"
            description={`확인 대상 ${check.targetCount}개가 함께 삭제됩니다.\n삭제하면 잠시 뒤부터 이 주소들을 확인하지 않습니다.`}
            pending={deleteMutation.isPending}
            errorMessage={deleteMutation.isError ? '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.' : undefined}
            onConfirm={() => deleteMutation.mutate()}
          />
        </>
      ) : null}
    </>
  )
}
