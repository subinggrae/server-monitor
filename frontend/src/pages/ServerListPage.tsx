import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'

import { CollectionStateDot } from '@/components/StatusDot'
import { Toolbar } from '@/components/Toolbar'
import { queryState } from '@/components/layout/state'
import { ListPage } from '@/components/layout/ListPage'
import { Notice } from '@/components/layout/Notice'
import { ServerFormDialog } from '@/components/servers/ServerFormDialog'
import { SearchInput, Select } from '@/components/ui/input'
import { Table, TableFrame, TableNotice, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { SERVER_OS_LABELS, serversQuery } from '@/lib/api'

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
      <ListPage
        title="서버"
        description="서버를 등록하고 OS exporter로 CPU, Memory, Storage 사용량을 수집합니다."
        primaryAction={{ label: '서버 추가', onClick: () => setFormOpen(true) }}
        notice={
          <Notice title="사용률이 높아도 아직 위험으로 표시하지 않습니다.">
            임계값을 정하는 기능이 아직 없습니다. 리소스는 서버 상세의 수집 탭에서 수집을 켠 뒤 리소스 탭에서 찾아서 고릅니다.
          </Notice>
        }
        state={queryState(
          { isPending, isError, refetch },
          error instanceof Error ? error.message : '서버 목록을 불러오지 못했습니다.',
          servers?.length === 0
            ? {
                title: '등록된 서버가 없습니다.',
                description:
                  '서버를 추가한 뒤 서버 상세에서 OS exporter 수집을 켜면 CPU, Memory, Storage 사용량을 수집합니다.',
              }
            : null,
        )}
        skeletonColumns={6}
        toolbar={
          <Toolbar count={`${visibleServers.length}개 / 전체 ${servers?.length ?? 0}개`}>
            <SearchInput
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              placeholder="이름 또는 호스트"
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
        }
      >
        <TableFrame>
          <Table>
            <THead>
              <tr>
                <TH>이름</TH>
                <TH className="w-44">호스트</TH>
                <TH className="w-24">OS</TH>
                <TH className="w-32">환경</TH>
                <TH className="w-32">수집</TH>
                <TH className="w-24 text-right">리소스</TH>
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
                  <TD className="text-muted-foreground">{SERVER_OS_LABELS[server.os]}</TD>
                  <TD className="text-muted-foreground">{server.environment}</TD>
                  <TD>
                    <CollectionStateDot state={server.collectionState} />
                  </TD>
                  <TD className="tabular text-right text-muted-foreground">
                    {server.monitoredResourceCount > 0 ? server.monitoredResourceCount : '-'}
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>

          {visibleServers.length === 0 ? (
            <TableNotice>조건에 맞는 서버가 없습니다. 검색어나 필터를 바꿔 보세요.</TableNotice>
          ) : null}
        </TableFrame>
      </ListPage>

      <ServerFormDialog open={formOpen} onOpenChange={setFormOpen} />
    </>
  )
}
