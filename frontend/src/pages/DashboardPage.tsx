import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'

import { OverviewPage } from '@/components/layout/OverviewPage'
import { PageSection } from '@/components/layout/PageSection'
import { AlertStatusLabel, StatusCount } from '@/components/StatusDot'
import { Button, buttonVariants } from '@/components/ui/button'
import { Table, TableFrame, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import {
  alertsQuery,
  checksQuery,
  serversQuery,
  systemInfoQuery,
  type AlertEvent,
  type MonitoringCheck,
  SNMP_STATE_LABELS,
  type ServerTarget,
  type SnmpState,
} from '@/lib/api'
import { formatDateTime, formatElapsed, formatTime } from '@/lib/format'

const RECENT_ALERT_LIMIT = 5

/** 장애 기록 화면과 같은 간격입니다. 열어 둔 채로 상태가 바뀌는 것을 볼 수 있어야 합니다. */
const REFRESH_INTERVAL = 30_000

const SNMP_STATE_ORDER: SnmpState[] = ['CONNECTED', 'NO_RESPONSE', 'DISABLED', 'NOT_CONFIGURED']

/** Telegram 알림을 받고 연 사람이 "무엇이, 언제부터 문제인가"를 첫 화면에서 읽게 합니다. */
export function DashboardPage() {
  const systemInfo = useQuery(systemInfoQuery)
  const checks = useQuery({ ...checksQuery, refetchInterval: REFRESH_INTERVAL })
  const alerts = useQuery({ ...alertsQuery, refetchInterval: REFRESH_INTERVAL })
  const servers = useQuery({ ...serversQuery, refetchInterval: REFRESH_INTERVAL })

  const criticalChecks = (checks.data ?? []).filter((check) => check.status === 'CRITICAL')
  const countOf = (status: MonitoringCheck['status']) =>
    (checks.data ?? []).filter((check) => check.status === status).length
  const startedAt = firingSince(alerts.data ?? [])

  return (
    <OverviewPage
      title="대시보드"
      freshness={<Freshness updatedAt={checks.dataUpdatedAt} stale={checks.isError && checks.data !== undefined} />}
      actions={
        systemInfo.data?.grafanaUrl ? (
          <a
            href={systemInfo.data.grafanaUrl}
            target="_blank"
            rel="noopener noreferrer"
            className={buttonVariants({ variant: 'secondary' })}
          >
            Grafana에서 자세히 보기
          </a>
        ) : (
          <Button variant="secondary" disabled>
            Grafana에서 자세히 보기
          </Button>
        )
      }
      figure={
        checks.data ? (
          <div className="space-y-2">
            {criticalChecks.length > 0 ? (
              <p className="flex items-baseline gap-2">
                <span data-testid="critical-count" className="tabular text-figure font-bold text-critical-foreground">
                  {criticalChecks.length}
                </span>
                <span className="text-section font-semibold">위험한 감시 항목</span>
              </p>
            ) : (
              <p className="text-section font-semibold">모든 감시 항목이 정상입니다</p>
            )}
            <div className="flex flex-wrap items-center gap-x-6 gap-y-1">
              <StatusCount status="OK" label="정상" value={countOf('OK')} />
              <StatusCount status="UNKNOWN" label="알 수 없음" value={countOf('UNKNOWN')} />
              <StatusCount status="DISABLED" label="사용 안 함" value={countOf('DISABLED')} />
              <span className="text-body text-muted-foreground">
                전체 <span className="tabular">{checks.data.length}</span>개
              </span>
            </div>
          </div>
        ) : null
      }
    >
      <PageSection
        title="지금 위험한 감시 항목"
        state={
          checks.data
            ? criticalChecks.length === 0
              ? { kind: 'empty', title: '위험 상태인 감시 항목이 없습니다.' }
              : { kind: 'ready' }
            : checks.isError
              ? { kind: 'error', message: '감시 항목 상태를 불러오지 못했습니다.', onRetry: () => checks.refetch() }
              : { kind: 'loading' }
        }
      >
        <CriticalTable checks={criticalChecks} startedAt={startedAt} />
      </PageSection>

      <PageSection
        title="최근 장애"
        actions={
          <Link to="/alerts" className="text-body text-muted-foreground underline-offset-4 hover:text-foreground hover:underline">
            모두 보기
          </Link>
        }
        state={
          alerts.data
            ? alerts.data.length === 0
              ? { kind: 'empty', title: '기록된 장애가 없습니다.' }
              : { kind: 'ready' }
            : alerts.isError
              ? { kind: 'error', message: '장애 기록을 불러오지 못했습니다.', onRetry: () => alerts.refetch() }
              : { kind: 'loading' }
        }
      >
        <RecentAlerts alerts={(alerts.data ?? []).slice(0, RECENT_ALERT_LIMIT)} />
      </PageSection>

      <PageSection
        title="서버"
        state={
          servers.data
            ? servers.data.length === 0
              ? { kind: 'empty', title: '등록한 서버가 없습니다.' }
              : { kind: 'ready' }
            : servers.isError
              ? { kind: 'error', message: '서버 목록을 불러오지 못했습니다.', onRetry: () => servers.refetch() }
              : { kind: 'loading' }
        }
      >
        <ServerSummary servers={servers.data ?? []} />
      </PageSection>
    </OverviewPage>
  )
}

function Freshness({ updatedAt, stale }: { updatedAt: number; stale: boolean }) {
  if (!updatedAt) {
    return null
  }
  return stale ? (
    <span className="font-medium text-critical-foreground" role="status">
      최신 상태를 확인하지 못했습니다. {formatTime(updatedAt)} 기준 값입니다.
    </span>
  ) : (
    <span role="status">
      마지막 확인 {formatTime(updatedAt)} · 30초마다 새로고침
    </span>
  )
}

/** 감시 항목마다 발생 중인 장애 가운데 가장 이른 시작 시각입니다. 장애 기록에 없으면 비어 있습니다. */
function firingSince(alerts: AlertEvent[]) {
  const since = new Map<number, string>()
  for (const alert of alerts) {
    if (alert.status !== 'FIRING' || alert.checkId === null) {
      continue
    }
    const current = since.get(alert.checkId)
    if (!current || new Date(alert.startsAt) < new Date(current)) {
      since.set(alert.checkId, alert.startsAt)
    }
  }
  return since
}

function CriticalTable({ checks, startedAt }: { checks: MonitoringCheck[]; startedAt: Map<number, string> }) {
  return (
    <TableFrame>
      <Table>
        <THead>
          <tr>
            <TH>이름</TH>
            <TH className="w-32">환경</TH>
            <TH className="w-28 text-right">실패한 대상</TH>
            <TH className="w-44">시작</TH>
            <TH className="w-36 text-right">지속</TH>
          </tr>
        </THead>
        <TBody>
          {checks.map((check) => {
            const since = startedAt.get(check.id)
            return (
              <TR key={check.id}>
                <TD>
                  <Link to={`/monitoring/checks/${check.id}`} className="font-medium hover:underline">
                    {check.name}
                  </Link>
                </TD>
                <TD className="text-muted-foreground">{check.environment}</TD>
                <TD className="tabular text-right">
                  {check.targets.filter((target) => target.status === 'CRITICAL').length} / {check.targetCount}
                </TD>
                <TD className="tabular text-muted-foreground">{since ? formatDateTime(since) : '시작 시각 모름'}</TD>
                <TD className="tabular text-right font-medium">{since ? formatElapsed(since) : '-'}</TD>
              </TR>
            )
          })}
        </TBody>
      </Table>
    </TableFrame>
  )
}

function RecentAlerts({ alerts }: { alerts: AlertEvent[] }) {
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

function ServerSummary({ servers }: { servers: ServerTarget[] }) {
  const snmpCounts = SNMP_STATE_ORDER.map((state) => ({
    state,
    count: servers.filter((server) => server.snmpState === state).length,
  })).filter(({ count }) => count > 0)

  return (
    <p className="text-body text-muted-foreground">
      서버 <span className="tabular font-medium text-foreground">{servers.length}</span>대를 등록했습니다. SNMP 상태는{' '}
      {snmpCounts.map(({ state, count }) => `${SNMP_STATE_LABELS[state]} ${count}대`).join(', ')}입니다.
    </p>
  )
}
