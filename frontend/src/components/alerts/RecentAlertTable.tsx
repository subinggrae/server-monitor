import { Link } from 'react-router'

import { AlertStatusLabel } from '@/components/StatusDot'
import { Table, TableFrame, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import type { AlertEvent } from '@/lib/api'
import { formatDateTime } from '@/lib/format'

/** 대시보드와 감시 항목 상세가 함께 쓰는 짧은 장애 표입니다. 자세한 기록은 장애 기록 화면에서 봅니다. */
export function RecentAlertTable({ alerts }: { alerts: AlertEvent[] }) {
  return (
    <TableFrame>
      <Table>
        <THead>
          <tr>
            <TH className="w-24">상태</TH>
            <TH>내용</TH>
            <TH className="w-52">대상</TH>
            <TH className="w-44">발생</TH>
          </tr>
        </THead>
        <TBody>
          {alerts.map((alert) => (
            <TR key={alert.id}>
              <TD>
                <AlertStatusLabel status={alert.status} />
              </TD>
              <TD>
                {alert.checkId ? (
                  <Link to={`/monitoring/checks/${alert.checkId}`} className="hover:underline">
                    {alert.summary ?? alert.alertName}
                  </Link>
                ) : (
                  (alert.summary ?? alert.alertName)
                )}
              </TD>
              <TD className="address max-w-0 truncate text-muted-foreground">{alert.instance ?? '-'}</TD>
              <TD className="tabular text-muted-foreground">{formatDateTime(alert.startsAt)}</TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </TableFrame>
  )
}
