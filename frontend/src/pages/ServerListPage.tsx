import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'

import { PageBody, PageHeader } from '@/components/PageHeader'
import { EmptyState, ErrorState, TableSkeleton } from '@/components/QueryStates'
import { SnmpStateDot } from '@/components/StatusDot'
import { Toolbar } from '@/components/Toolbar'
import { ServerFormDialog } from '@/components/servers/ServerFormDialog'
import { Button } from '@/components/ui/button'
import { SearchInput, Select } from '@/components/ui/input'
import { Table, TableFrame, TableNotice, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { serversQuery } from '@/lib/api'

export function ServerListPage() {
  const { data: servers, isPending, isError, error, refetch } = useQuery(serversQuery)
  const [keyword, setKeyword] = useState('')
  const [environmentFilter, setEnvironmentFilter] = useState('ALL')
  const [formOpen, setFormOpen] = useState(false)

  const environments = useMemo(
    () => [...new Set((servers ?? []).map((server) => server.environment))].sort(),
    [servers],
  )

  const visibleServers = useMemo(() => {
    const normalized = keyword.trim().toLowerCase()
    return (servers ?? []).filter((server) => {
      const matchesKeyword =
        normalized === '' ||
        server.name.toLowerCase().includes(normalized) ||
        server.host.toLowerCase().includes(normalized)
      const matchesEnvironment = environmentFilter === 'ALL' || server.environment === environmentFilter
      return matchesKeyword && matchesEnvironment
    })
  }, [servers, keyword, environmentFilter])

  return (
    <>
      <PageHeader
        title="서버"
        description="서버 자산을 등록합니다. SNMP 기반 CPU, Memory, Storage 모니터링에 사용할 예정입니다."
        actions={<Button onClick={() => setFormOpen(true)}>서버 추가</Button>}
      />

      <PageBody>
        {isPending ? (
          <TableSkeleton columns={4} />
        ) : isError ? (
          <ErrorState
            message={error instanceof Error ? error.message : '서버 목록을 불러오지 못했습니다.'}
            onRetry={() => refetch()}
          />
        ) : servers.length === 0 ? (
          <EmptyState
            title="등록된 서버가 없습니다."
            description="서버를 등록해 두면 SNMP Resource 모니터링을 붙일 때 바로 사용할 수 있습니다."
            action={<Button onClick={() => setFormOpen(true)}>서버 추가</Button>}
          />
        ) : (
          <div className="space-y-3">
            <Toolbar count={`${visibleServers.length}개 / 전체 ${servers.length}개`}>
              <SearchInput
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                placeholder="이름 또는 Host"
                className="w-64"
                aria-label="서버 검색"
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
            </Toolbar>

            <TableFrame>
              <Table>
                <THead>
                  <tr>
                    <TH>이름</TH>
                    <TH className="w-44">Host</TH>
                    <TH className="w-32">환경</TH>
                    <TH className="w-32">SNMP</TH>
                    <TH className="w-24 text-right">Resource</TH>
                  </tr>
                </THead>
                <TBody>
                  {visibleServers.map((server) => (
                    <TR key={server.id}>
                      <TD>
                        <Link
                          to={`/infrastructure/servers/${server.id}`}
                          className="font-medium hover:underline"
                        >
                          {server.name}
                        </Link>
                      </TD>
                      <TD className="tabular text-muted-foreground">{server.host}</TD>
                      <TD className="text-muted-foreground">{server.environment}</TD>
                      <TD>
                        <SnmpStateDot state={server.snmpState} />
                      </TD>
                      <TD className="tabular text-right text-muted-foreground">
                        {server.monitoredResourceCount > 0 ? server.monitoredResourceCount : '-'}
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>

              {visibleServers.length === 0 ? (
                <TableNotice>조건에 맞는 서버가 없습니다. 검색어나 filter를 바꿔 보세요.</TableNotice>
              ) : null}
            </TableFrame>

            <p className="text-xs text-muted-foreground">
              Resource는 서버 상세에서 SNMP를 설정하고 검색해서 고릅니다. 아직 Threshold가 없어 사용률이
              높아도 위험으로 표시하지 않습니다.
            </p>
          </div>
        )}
      </PageBody>

      <ServerFormDialog open={formOpen} onOpenChange={setFormOpen} />
    </>
  )
}
