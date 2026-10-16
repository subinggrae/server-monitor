import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { Link } from 'react-router'

import { AlertStatusLabel } from '@/components/StatusDot'
import { Toolbar } from '@/components/Toolbar'
import { queryState } from '@/components/layout/state'
import { ListPage } from '@/components/layout/ListPage'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { Table, TableFrame, TableNotice, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { alertsQuery, type AlertStatus } from '@/lib/api'
import { formatDateTime } from '@/lib/format'
import { pickParam, useSearchParamState } from '@/lib/searchParams'

const STATUS_LABELS: Record<AlertStatus, string> = { FIRING: '발생 중', RESOLVED: '복구' }

const STATUSES = Object.keys(STATUS_LABELS) as AlertStatus[]

/** 장애 관리 기능은 만들지 않습니다. 언제 무엇이 장애였고 언제 풀렸는지만 봅니다. */
export function AlertListPage() {
  const { data: alerts, isPending, isError, error, refetch } = useQuery({
    ...alertsQuery,
    // 장애 상황에서는 목록이 자주 바뀝니다.
    refetchInterval: 30_000,
  })
  const { params, update } = useSearchParamState()
  const statusFilter = pickParam(params.get('status'), STATUSES)

  // 발생 중인 장애를 위에 두고, 그 안에서는 최근에 시작한 것부터 보입니다.
  const visibleAlerts = useMemo(
    () =>
      (alerts ?? [])
        .filter((alert) => statusFilter === null || alert.status === statusFilter)
        .sort(
          (a, b) =>
            STATUSES.indexOf(a.status) - STATUSES.indexOf(b.status) ||
            new Date(b.startsAt).getTime() - new Date(a.startsAt).getTime(),
        ),
    [alerts, statusFilter],
  )

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
                '장애가 감지되면 여기에 기록됩니다. 감시 항목이 위험 상태인데 기록이 없으면 장애가 Monitoring Manager까지 전달되지 않는 것입니다. 설치할 때 정한 장애 전달 설정을 확인해 주세요.',
            }
          : null,
      )}
      skeletonColumns={6}
      toolbar={
        <Toolbar
          count={`${visibleAlerts.length}건 / 전체 ${alerts?.length ?? 0}건`}
          activeFilters={statusFilter ? [`상태 ${STATUS_LABELS[statusFilter]}`] : []}
          onReset={() => update({ status: null })}
        >
          <Select
            value={statusFilter ?? 'ALL'}
            onChange={(event) => update({ status: event.target.value === 'ALL' ? null : event.target.value })}
            className="w-36"
            aria-label="상태 필터"
          >
            <option value="ALL">상태 전체</option>
            {STATUSES.map((status) => (
              <option key={status} value={status}>
                {STATUS_LABELS[status]}
              </option>
            ))}
          </Select>
        </Toolbar>
      }
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
            {visibleAlerts.map((alert) => (
              <TR key={alert.id}>
                <TD>
                  <AlertStatusLabel status={alert.status} />
                </TD>
                <TD className="text-muted-foreground">{severityLabel(alert.severity)}</TD>
                <TD>
                  {alert.checkId ? (
                    <Link to={`/monitoring/checks/${alert.checkId}`} className="hover:underline">
                      {alert.summary ?? alert.alertName}
                    </Link>
                  ) : (
                    <span>{alert.summary ?? alert.alertName}</span>
                  )}
                </TD>
                <TD className="address max-w-0 truncate text-muted-foreground" title={alert.instance ?? undefined}>
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

        {visibleAlerts.length === 0 ? (
          <TableNotice>
            조건에 맞는 장애 기록이 없습니다.{' '}
            <Button variant="ghost" size="sm" onClick={() => update({ status: null })}>
              필터 초기화
            </Button>
          </TableNotice>
        ) : null}
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
