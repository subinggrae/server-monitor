import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'

import { PageBody, PageHeader } from '@/components/PageHeader'
import { EmptyState, ErrorState, TableSkeleton } from '@/components/QueryStates'
import { AlertStatusLabel } from '@/components/StatusDot'
import { Table, TableFrame, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { alertsQuery } from '@/lib/api'
import { formatDateTime } from '@/lib/format'

/** 장애 관리 기능은 만들지 않습니다. 언제 무엇이 장애였고 언제 풀렸는지만 봅니다. */
export function AlertListPage() {
  const { data: alerts, isPending, isError, error, refetch } = useQuery({
    ...alertsQuery,
    // 장애 상황에서는 목록이 자주 바뀝니다.
    refetchInterval: 30_000,
  })

  return (
    <>
      <PageHeader
        title="Alert"
        description="Prometheus가 감지하고 Alertmanager가 전달한 Alert입니다. 알림 대상은 Check 상세에서 정합니다."
      />

      <PageBody>
        {isPending ? (
          <TableSkeleton columns={6} />
        ) : isError ? (
          <ErrorState
            message={error instanceof Error ? error.message : 'Alert를 불러오지 못했습니다.'}
            onRetry={() => refetch()}
          />
        ) : alerts.length === 0 ? (
          <EmptyState
            title="받은 Alert가 없습니다."
            description="아직 장애가 감지되지 않았거나, Alertmanager가 Manager로 Alert를 보내지 않고 있습니다."
          />
        ) : (
          <TableFrame>
            <Table>
              <THead>
                <tr>
                  <TH className="w-24">상태</TH>
                  <TH className="w-24">심각도</TH>
                  <TH className="w-80">Check</TH>
                  <TH>대상</TH>
                  <TH className="w-28">발생</TH>
                  <TH className="w-28">해소</TH>
                </tr>
              </THead>
              <TBody>
                {alerts.map((alert) => (
                  <TR key={alert.id}>
                    <TD>
                      <AlertStatusLabel status={alert.status} />
                    </TD>
                    <TD className="text-muted-foreground">{severityLabel(alert.severity)}</TD>
                    <TD>
                      {alert.checkId ? (
                        <Link
                          to={`/monitoring/checks/${alert.checkId}`}
                          className="hover:underline"
                        >
                          {alert.summary ?? alert.alertName}
                        </Link>
                      ) : (
                        <span>{alert.summary ?? alert.alertName}</span>
                      )}
                    </TD>
                    <TD className="tabular max-w-0 truncate text-muted-foreground">
                      {alert.instance ?? '-'}
                    </TD>
                    <TD className="tabular text-muted-foreground">{formatDateTime(alert.startsAt)}</TD>
                    <TD className="tabular text-muted-foreground">
                      {alert.resolvedAt ? formatDateTime(alert.resolvedAt) : '-'}
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </TableFrame>
        )}
      </PageBody>
    </>
  )
}

/** Alertmanager가 보내는 severity를 그대로 보여 주지 않고 화면 문구로 바꿉니다. */
function severityLabel(severity: string | null) {
  if (severity === 'critical') {
    return 'Critical'
  }
  if (severity === 'warning') {
    return 'Warning'
  }
  return severity ?? '-'
}

