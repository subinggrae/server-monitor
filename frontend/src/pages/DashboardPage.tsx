import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'

import { PageBody, PageHeader } from '@/components/PageHeader'
import { ErrorState, TableSkeleton } from '@/components/QueryStates'
import { AlertStatusLabel, StatusCount } from '@/components/StatusDot'
import { Button } from '@/components/ui/button'
import { Section, SectionHeader } from '@/components/ui/section'
import { Table, TableFrame, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { alertsQuery, checksQuery, serversQuery, systemInfoQuery, type MonitoringStatus } from '@/lib/api'
import { formatDateTime } from '@/lib/format'

const RECENT_ALERT_LIMIT = 5

export function DashboardPage() {
  const systemInfo = useQuery(systemInfoQuery)
  const checks = useQuery(checksQuery)
  const servers = useQuery(serversQuery)
  const alerts = useQuery(alertsQuery)

  const countByStatus = (status: MonitoringStatus) =>
    (checks.data ?? []).filter((check) => check.status === status).length

  const criticalChecks = (checks.data ?? []).filter((check) => check.status === 'CRITICAL')
  const recentAlerts = (alerts.data ?? []).slice(0, RECENT_ALERT_LIMIT)

  return (
    <>
      <PageHeader
        title="대시보드"
        description="지금 무엇이 문제인지 먼저 확인합니다."
        actions={
          <Button
            variant="secondary"
            disabled={!systemInfo.data?.grafanaUrl}
            onClick={() => window.open(systemInfo.data?.grafanaUrl, '_blank', 'noopener')}
          >
            Grafana에서 자세히 보기
          </Button>
        }
      />

      <PageBody>
        <Section>
          <SectionHeader title="Monitoring Check" />

          {checks.isPending ? (
            <p className="text-body text-muted-foreground">확인 중입니다.</p>
          ) : checks.isError ? (
            <ErrorState
              message="Check 상태를 불러오지 못했습니다."
              onRetry={() => checks.refetch()}
            />
          ) : (
            <div className="flex flex-wrap items-baseline gap-x-7 gap-y-2">
              <StatusCount status="OK" label="정상" value={countByStatus('OK')} />
              <StatusCount status="CRITICAL" label="위험" value={countByStatus('CRITICAL')} />
              <StatusCount status="UNKNOWN" label="알 수 없음" value={countByStatus('UNKNOWN')} />
              <StatusCount status="DISABLED" label="사용 안 함" value={countByStatus('DISABLED')} />
              <span className="text-caption text-muted-foreground">전체 {checks.data.length}개</span>
            </div>
          )}
        </Section>

        {criticalChecks.length > 0 ? (
          <Section>
            <SectionHeader title="지금 위험한 Check" />
            <TableFrame>
              <Table>
                <TBody>
                  {criticalChecks.map((check) => (
                    <TR key={check.id}>
                      <TD>
                        <Link
                          to={`/monitoring/checks/${check.id}`}
                          className="font-medium hover:underline"
                        >
                          {check.name}
                        </Link>
                      </TD>
                      <TD className="w-32 text-muted-foreground">{check.environment}</TD>
                      <TD className="w-36 text-right text-muted-foreground">
                        <span className="tabular">
                          {check.targets.filter((target) => target.status === 'CRITICAL').length} /{' '}
                          {check.targetCount}
                        </span>{' '}
                        대상 실패
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            </TableFrame>
          </Section>
        ) : checks.isSuccess ? (
          <Section>
            <SectionHeader title="지금 위험한 Check" />
            <p className="text-body text-muted-foreground">위험 상태인 Check가 없습니다.</p>
          </Section>
        ) : null}

        <Section>
          <SectionHeader
            title="최근 Alert"
            actions={
              <Link
                to="/alerts"
                className="text-caption text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
              >
                모두 보기
              </Link>
            }
          />

          {alerts.isPending ? (
            <TableSkeleton rows={3} columns={4} />
          ) : alerts.isError ? (
            <p className="text-body text-muted-foreground">Alert를 불러오지 못했습니다.</p>
          ) : recentAlerts.length === 0 ? (
            <p className="text-body text-muted-foreground">받은 Alert가 없습니다.</p>
          ) : (
            <TableFrame>
              <Table>
                <THead>
                  <tr>
                    <TH className="w-24">상태</TH>
                    <TH>Check</TH>
                    <TH className="w-52">대상</TH>
                    <TH className="w-28">발생</TH>
                  </tr>
                </THead>
                <TBody>
                  {recentAlerts.map((alert) => (
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
                      <TD className="tabular truncate text-muted-foreground">{alert.instance ?? '-'}</TD>
                      <TD className="tabular text-muted-foreground">{formatDateTime(alert.startsAt)}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            </TableFrame>
          )}
        </Section>

        <Section>
          <SectionHeader title="서버" />
          {servers.isPending ? (
            <p className="text-body text-muted-foreground">확인 중입니다.</p>
          ) : servers.isError ? (
            <p className="text-body text-muted-foreground">서버 목록을 불러오지 못했습니다.</p>
          ) : (
            <p className="text-body text-muted-foreground">
              <span className="tabular font-medium text-foreground">{servers.data.length}</span>대
              등록했습니다. Resource 모니터링(SNMP)을 아직 설정하지 않아 서버 상태는 계산하지
              않습니다.
            </p>
          )}
        </Section>
      </PageBody>
    </>
  )
}
