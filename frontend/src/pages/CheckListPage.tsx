import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'

import { PageBody, PageHeader } from '@/components/PageHeader'
import { EmptyState, ErrorState, TableSkeleton } from '@/components/QueryStates'
import { StatusDot } from '@/components/StatusDot'
import { Toolbar } from '@/components/Toolbar'
import { CheckFormDialog } from '@/components/checks/CheckFormDialog'
import { Button } from '@/components/ui/button'
import { SearchInput, Select } from '@/components/ui/input'
import { Table, TableFrame, TableNotice, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { checksQuery, type MonitoringCheck, type MonitoringStatus } from '@/lib/api'

const STATUS_FILTERS: { value: MonitoringStatus | 'ALL'; label: string }[] = [
  { value: 'ALL', label: '상태 전체' },
  { value: 'OK', label: '정상' },
  { value: 'CRITICAL', label: '위험' },
  { value: 'UNKNOWN', label: '알 수 없음' },
  { value: 'DISABLED', label: '사용 안 함' },
]

export function CheckListPage() {
  const { data: checks, isPending, isError, error, refetch } = useQuery(checksQuery)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState<MonitoringStatus | 'ALL'>('ALL')
  const [environmentFilter, setEnvironmentFilter] = useState('ALL')
  const [formOpen, setFormOpen] = useState(false)

  const environments = useMemo(
    () => [...new Set((checks ?? []).map((check) => check.environment))].sort(),
    [checks],
  )

  const visibleChecks = useMemo(() => {
    const normalized = keyword.trim().toLowerCase()
    return (checks ?? []).filter((check) => {
      const matchesKeyword =
        normalized === '' ||
        check.name.toLowerCase().includes(normalized) ||
        check.targets.some((target) => target.blackboxTarget.toLowerCase().includes(normalized))
      const matchesStatus = statusFilter === 'ALL' || check.status === statusFilter
      const matchesEnvironment = environmentFilter === 'ALL' || check.environment === environmentFilter
      return matchesKeyword && matchesStatus && matchesEnvironment
    })
  }, [checks, keyword, statusFilter, environmentFilter])

  return (
    <>
      <PageHeader
        title="Monitoring Check"
        description="URL, Port, Ping 상태를 확인합니다. 하나의 Check에 대상을 여러 개 넣을 수 있습니다."
        actions={<Button onClick={() => setFormOpen(true)}>Check 추가</Button>}
      />

      <PageBody>
        {isPending ? (
          <TableSkeleton columns={5} />
        ) : isError ? (
          <ErrorState
            message={error instanceof Error ? error.message : 'Check 목록을 불러오지 못했습니다.'}
            onRetry={() => refetch()}
          />
        ) : checks.length === 0 ? (
          <EmptyState
            title="등록된 Check가 없습니다."
            description="Ping, HTTP, HTTPS, TCP 상태 확인을 추가할 수 있습니다. 서버를 먼저 등록하지 않아도 됩니다."
            action={<Button onClick={() => setFormOpen(true)}>Check 추가</Button>}
          />
        ) : (
          <div className="space-y-3">
            <Toolbar count={`${visibleChecks.length}개 / 전체 ${checks.length}개`}>
              <SearchInput
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                placeholder="이름 또는 대상 주소"
                className="w-64"
                aria-label="Check 검색"
              />
              <Select
                value={environmentFilter}
                onChange={(event) => setEnvironmentFilter(event.target.value)}
                className="w-36"
                aria-label="환경 필터"
              >
                <option value="ALL">환경 전체</option>
                {environments.map((environment) => (
                  <option key={environment} value={environment}>
                    {environment}
                  </option>
                ))}
              </Select>
              <Select
                value={statusFilter}
                onChange={(event) => setStatusFilter(event.target.value as MonitoringStatus | 'ALL')}
                className="w-36"
                aria-label="상태 필터"
              >
                {STATUS_FILTERS.map((filter) => (
                  <option key={filter.value} value={filter.value}>
                    {filter.label}
                  </option>
                ))}
              </Select>
            </Toolbar>

            <TableFrame>
              <Table>
                <THead>
                  <tr>
                    <TH className="w-72">이름</TH>
                    <TH className="w-20">종류</TH>
                    <TH className="w-28">환경</TH>
                    <TH>대상</TH>
                    <TH className="w-28">상태</TH>
                  </tr>
                </THead>
                <TBody>
                  {visibleChecks.map((check) => (
                    <TR key={check.id}>
                      <TD>
                        <Link
                          to={`/monitoring/checks/${check.id}`}
                          className="font-medium hover:underline"
                        >
                          {check.name}
                        </Link>
                      </TD>
                      <TD className="text-muted-foreground">{check.typeDisplayName}</TD>
                      <TD className="text-muted-foreground">{check.environment}</TD>
                      <TD className="max-w-0">
                        <TargetSummary check={check} />
                      </TD>
                      <TD>
                        <StatusDot status={check.status} />
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>

              {visibleChecks.length === 0 ? (
                <TableNotice>조건에 맞는 Check가 없습니다. 검색어나 filter를 바꿔 보세요.</TableNotice>
              ) : null}
            </TableFrame>
          </div>
        )}
      </PageBody>

      <CheckFormDialog open={formOpen} onOpenChange={setFormOpen} />
    </>
  )
}

function TargetSummary({ check }: { check: MonitoringCheck }) {
  const [first, ...rest] = check.targets

  if (!first) {
    return <span className="text-muted-foreground">없음</span>
  }

  return (
    <div className="flex items-baseline gap-2">
      <span className="tabular truncate text-muted-foreground">{first.blackboxTarget}</span>
      {rest.length > 0 ? (
        <span className="shrink-0 text-xs text-subtle-foreground">외 {rest.length}개</span>
      ) : null}
    </div>
  )
}
