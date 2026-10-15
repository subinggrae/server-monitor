import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'

import { AlertStatusLabel } from '@/components/StatusDot'
import { queryState } from '@/components/layout/state'
import { ListPage } from '@/components/layout/ListPage'
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
    <ListPage
      title="장애 기록(Alert)"
      description="감시 항목에서 감지한 장애와 복구 시각입니다. 알림을 받는 그룹은 감시 항목 상세에서 정합니다."
      state={queryState(
        { isPending, isError, refetch },
        error instanceof Error ? error.message : '장애 기록을 불러오지 못했습니다.',
        alerts?.length === 0
          ? {
              title: '기록된 장애가 없습니다.',
              description:
                '장애가 감지되면 여기에 기록됩니다. 감시 항목이 위험 상태인데 기록이 없으면 Alertmanager가 Monitoring Manager로 장애를 보내는지 설치 설정을 확인해 주세요.',
            }
          : null,
      )}
      skeletonColumns={6}
    >
      <TableFrame>
        <Table>
          <THead>
            <tr>
              <TH className="w-24">상태</TH>
              <TH className="w-24">심각도</TH>
              <TH className="w-80">내용</TH>
              <TH>대상</TH>
              <TH className="w-44">발생</TH>
              <TH className="w-44">복구</TH>
            </tr>
          </THead>
          <TBody>
            {(alerts ?? []).map((alert) => (
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
    </ListPage>
  )
}

/** Alertmanager가 보내는 severity를 그대로 보여 주지 않고 화면 문구로 바꿉니다. */
function severityLabel(severity: string | null) {
  if (severity === 'critical') {
    return '위험'
  }
  if (severity === 'warning') {
    return '경고'
  }
  return severity ?? '-'
}
