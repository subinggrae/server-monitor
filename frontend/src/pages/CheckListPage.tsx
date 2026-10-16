import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'

import { StatusDot } from '@/components/StatusDot'
import { Toolbar } from '@/components/Toolbar'
import { CheckFormDialog } from '@/components/checks/CheckFormDialog'
import { queryState } from '@/components/layout/state'
import { ListPage } from '@/components/layout/ListPage'
import { Button } from '@/components/ui/button'
import { SearchInput, Select } from '@/components/ui/input'
import {
  SortableTH,
  Table,
  TableFrame,
  TableNotice,
  TBody,
  TD,
  TH,
  THead,
  TR,
  type SortDirection,
} from '@/components/ui/table'
import { checksQuery, type MonitoringCheck, type MonitoringStatus } from '@/lib/api'
import { pickParam, useSearchParamState } from '@/lib/searchParams'

const STATUS_LABELS: Record<MonitoringStatus, string> = {
  CRITICAL: '위험',
  UNKNOWN: '알 수 없음',
  OK: '정상',
  DISABLED: '사용 안 함',
}

/** 기본 정렬입니다. 행동이 필요한 상태가 먼저 보여야 합니다. */
const STATUS_ORDER: MonitoringStatus[] = ['CRITICAL', 'UNKNOWN', 'OK', 'DISABLED']

const SORT_KEYS = ['status', 'name', 'environment'] as const
type SortKey = (typeof SORT_KEYS)[number]

const COMPARE: Record<SortKey, (a: MonitoringCheck, b: MonitoringCheck) => number> = {
  status: (a, b) => STATUS_ORDER.indexOf(a.status) - STATUS_ORDER.indexOf(b.status),
  name: (a, b) => a.name.localeCompare(b.name, 'ko-KR'),
  environment: (a, b) => a.environment.localeCompare(b.environment, 'ko-KR'),
}

export function CheckListPage() {
  const { data: checks, isPending, isError, error, refetch } = useQuery(checksQuery)
  const { params, update } = useSearchParamState()
  const [formOpen, setFormOpen] = useState(false)

  const environments = useMemo(
    () => [...new Set((checks ?? []).map((check) => check.environment))].sort(),
    [checks],
  )

  const keyword = params.get('q') ?? ''
  const statusFilter = pickParam(params.get('status'), STATUS_ORDER)
  const environmentFilter = pickParam(params.get('env'), environments)
  const sortKey = pickParam(params.get('sort'), SORT_KEYS) ?? 'status'
  const direction: SortDirection = params.get('dir') === 'desc' ? 'desc' : 'asc'

  const visibleChecks = useMemo(() => {
    const normalized = keyword.trim().toLowerCase()
    const sign = direction === 'asc' ? 1 : -1
    return (checks ?? [])
      .filter((check) => {
        const matchesKeyword =
          normalized === '' ||
          check.name.toLowerCase().includes(normalized) ||
          check.targets.some((target) => target.blackboxTarget.toLowerCase().includes(normalized))
        const matchesStatus = statusFilter === null || check.status === statusFilter
        const matchesEnvironment = environmentFilter === null || check.environment === environmentFilter
        return matchesKeyword && matchesStatus && matchesEnvironment
      })
      .sort((a, b) => sign * COMPARE[sortKey](a, b) || COMPARE.name(a, b))
  }, [checks, keyword, statusFilter, environmentFilter, sortKey, direction])

  const activeFilters = [
    keyword.trim() ? `검색 "${keyword.trim()}"` : null,
    environmentFilter ? `환경 ${environmentFilter}` : null,
    statusFilter ? `상태 ${STATUS_LABELS[statusFilter]}` : null,
  ].filter((filter): filter is string => filter !== null)

  const resetFilters = () => update({ q: null, env: null, status: null })

  const sortBy = (key: SortKey) => {
    const nextDirection = key === sortKey && direction === 'asc' ? 'desc' : 'asc'
    update({ sort: key === 'status' ? null : key, dir: nextDirection === 'asc' ? null : 'desc' })
  }

  const sortable = (key: SortKey, label: string, className?: string) => (
    <SortableTH
      label={label}
      active={sortKey === key}
      direction={direction}
      onSort={() => sortBy(key)}
      className={className}
    />
  )

  return (
    <>
      <ListPage
        title="감시 항목(Check)"
        description="Ping, TCP, HTTP, HTTPS로 대상이 응답하는지 확인합니다. 감시 항목 하나에 대상을 여러 개 넣을 수 있습니다."
        primaryAction={{ label: '감시 항목 추가', onClick: () => setFormOpen(true) }}
        state={queryState(
          { isPending, isError, refetch },
          error instanceof Error ? error.message : '감시 항목을 불러오지 못했습니다.',
          checks?.length === 0
            ? {
                title: '등록된 감시 항목이 없습니다.',
                description:
                  '감시 항목을 추가하면 Ping, TCP, HTTP, HTTPS로 대상이 응답하는지 확인합니다. 서버를 먼저 등록하지 않아도 됩니다.',
              }
            : null,
        )}
        skeletonColumns={5}
        toolbar={
          <Toolbar
            count={`${visibleChecks.length}개 / 전체 ${checks?.length ?? 0}개`}
            activeFilters={activeFilters}
            onReset={resetFilters}
          >
            <SearchInput
              value={keyword}
              onChange={(event) => update({ q: event.target.value })}
              placeholder="이름 또는 대상 주소"
              className="w-64"
              aria-label="감시 항목 검색"
            />
            <Select
              value={environmentFilter ?? 'ALL'}
              onChange={(event) => update({ env: event.target.value === 'ALL' ? null : event.target.value })}
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
              value={statusFilter ?? 'ALL'}
              onChange={(event) => update({ status: event.target.value === 'ALL' ? null : event.target.value })}
              className="w-36"
              aria-label="상태 필터"
            >
              <option value="ALL">상태 전체</option>
              {STATUS_ORDER.map((status) => (
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
                {sortable('name', '이름', 'w-72')}
                <TH className="w-20">종류</TH>
                {sortable('environment', '환경', 'w-28')}
                <TH>대상</TH>
                {sortable('status', '상태', 'w-28')}
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
            <TableNotice>
              조건에 맞는 감시 항목이 없습니다.{' '}
              <Button variant="ghost" size="sm" onClick={resetFilters}>
                필터 초기화
              </Button>
            </TableNotice>
          ) : null}
        </TableFrame>
      </ListPage>

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
      <span className="tabular truncate text-muted-foreground" title={first.blackboxTarget}>
        {first.blackboxTarget}
      </span>
      {rest.length > 0 ? (
        <span className="shrink-0 text-caption text-subtle-foreground">외 {rest.length}개</span>
      ) : null}
    </div>
  )
}
