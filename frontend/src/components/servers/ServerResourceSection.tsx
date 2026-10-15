import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { queryState } from '@/components/layout/state'
import { Notice } from '@/components/layout/Notice'
import { PageSection } from '@/components/layout/PageSection'
import { Button } from '@/components/ui/button'
import { Checkbox, CheckboxLabel } from '@/components/ui/input'
import { Table, TableFrame, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import {
  apiPost,
  apiPut,
  queryKeys,
  serverResourcesQuery,
  type DiscoveredResource,
  type MonitoredResourceInput,
  type ResourceDiscoveryResult,
  type ResourceType,
  type ResourceUsage,
} from '@/lib/api'
import { formatBytes, formatDateTime, formatPercent } from '@/lib/format'

const TYPE_ORDER = { CPU: 0, MEMORY: 1, STORAGE: 2 } as const

const TYPE_LABELS: Record<ResourceType, string> = { CPU: 'CPU', MEMORY: '메모리', STORAGE: '스토리지' }

export function ServerResourceSection({
  serverId,
  snmpConfigured,
}: {
  serverId: number
  snmpConfigured: boolean
}) {
  const resources = useQuery(serverResourcesQuery(serverId))
  const [discovery, setDiscovery] = useState<ResourceDiscoveryResult | null>(null)

  const discoverMutation = useMutation({
    mutationFn: () => apiPost<ResourceDiscoveryResult>(`/api/servers/${serverId}/resources/discover`, {}),
    onSuccess: (result) => setDiscovery(result),
    onError: () =>
      setDiscovery({
        success: false,
        message: 'SNMP 응답을 받지 못했습니다. 호스트, 포트, Community와 네트워크 접근을 확인해 주세요.',
        systemName: null,
        systemDescription: null,
        resources: [],
      }),
  })

  return (
    <PageSection
      title="리소스"
      description="검색해서 찾은 리소스 가운데 수집할 것만 고릅니다."
      actions={
        <Button
          variant="secondary"
          size="sm"
          disabled={!snmpConfigured || discoverMutation.isPending}
          onClick={() => discoverMutation.mutate()}
        >
          {discoverMutation.isPending ? '검색 중…' : '리소스 검색'}
        </Button>
      }
      state={queryState(resources, '리소스를 불러오지 못했습니다.')}
    >
      {!snmpConfigured ? (
        <p className="text-body text-muted-foreground">
          SNMP를 먼저 설정해야 리소스를 검색할 수 있습니다.
        </p>
      ) : null}

      {discoverMutation.isPending ? (
        <p className="text-body text-muted-foreground">리소스를 검색하는 중…</p>
      ) : null}

      {discovery ? (
        <DiscoveryResult
          serverId={serverId}
          result={discovery}
          onDone={() => setDiscovery(null)}
        />
      ) : null}

      {/* 비어 있어도 위의 검색 결과는 보여야 하므로 빈 상태를 절의 state로 두지 않습니다. */}
      {resources.data?.length === 0 ? (
        <p className="text-body text-muted-foreground">
          수집하는 리소스가 없습니다. SNMP를 설정한 뒤 리소스 검색을 실행하면 CPU, 메모리, 스토리지를 고를 수
          있습니다.
        </p>
      ) : resources.data ? (
        <UsageTable resources={resources.data} />
      ) : null}
    </PageSection>
  )
}

function UsageTable({ resources }: { resources: ResourceUsage[] }) {
  const sorted = [...resources].sort(
    (left, right) =>
      TYPE_ORDER[left.resourceType] - TYPE_ORDER[right.resourceType] ||
      left.displayName.localeCompare(right.displayName),
  )

  return (
    <TableFrame>
      <Table>
        <THead>
          <tr>
            <TH className="w-24">종류</TH>
            <TH>이름</TH>
            <TH className="w-20 text-right">사용률</TH>
            <TH className="w-44 text-right">사용량</TH>
            <TH className="w-44">수집 시각</TH>
          </tr>
        </THead>
        <TBody>
          {sorted.map((resource) => (
            <TR key={resource.id}>
              <TD className="text-muted-foreground">{TYPE_LABELS[resource.resourceType]}</TD>
              <TD className="tabular">{resource.displayName}</TD>
              <TD className="tabular text-right">
                {resource.usagePercent == null ? (
                  <span className="text-muted-foreground">-</span>
                ) : (
                  formatPercent(resource.usagePercent)
                )}
              </TD>
              <TD className="tabular text-right text-muted-foreground">
                {resource.totalBytes == null || resource.usedBytes == null
                  ? '-'
                  : `${formatBytes(resource.usedBytes)} / ${formatBytes(resource.totalBytes)}`}
              </TD>
              <TD className="tabular text-muted-foreground">
                {resource.sampledAt ? formatDateTime(resource.sampledAt) : '수집 전'}
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </TableFrame>
  )
}

function DiscoveryResult({
  serverId,
  result,
  onDone,
}: {
  serverId: number
  result: ResourceDiscoveryResult
  onDone: () => void
}) {
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<Set<string>>(
    () => new Set(result.resources.filter((item) => item.selected).map(keyOf)),
  )

  const saveMutation = useMutation({
    mutationFn: (input: MonitoredResourceInput) =>
      apiPut<ResourceUsage[]>(`/api/servers/${serverId}/resources`, input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.serverResources(serverId) })
      queryClient.invalidateQueries({ queryKey: queryKeys.servers })
      onDone()
    },
  })

  if (!result.success) {
    return (
      <Notice tone="critical" title={result.message} />
    )
  }

  if (result.resources.length === 0) {
    return (
      <div className="rounded-md border border-border bg-surface px-3 py-2.5 text-body">
        <p>{result.message}</p>
        <Button variant="ghost" size="sm" className="-ml-2.5 mt-1" onClick={onDone}>
          닫기
        </Button>
      </div>
    )
  }

  const toggle = (item: DiscoveredResource) => {
    const key = keyOf(item)
    setSelected((previous) => {
      const next = new Set(previous)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  const grouped = groupByType(result.resources)

  return (
    <div className="rounded-md border border-border bg-surface">
      <div className="flex items-center justify-between border-b border-border px-3 py-2.5">
        <div>
          <p className="text-body font-medium">발견된 리소스</p>
          {result.systemName ? (
            <p className="mt-0.5 text-caption text-muted-foreground">{result.systemName}</p>
          ) : null}
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={onDone} disabled={saveMutation.isPending}>
            닫기
          </Button>
          <Button
            size="sm"
            disabled={saveMutation.isPending}
            onClick={() =>
              saveMutation.mutate({
                resources: result.resources
                  .filter((item) => selected.has(keyOf(item)))
                  .map((item) => ({
                    resourceType: item.resourceType,
                    resourceKey: item.resourceKey,
                    displayName: item.displayName,
                    description: item.description,
                    snmpIndex: item.snmpIndex,
                  })),
              })
            }
          >
            {saveMutation.isPending ? '저장 중…' : '선택 저장'}
          </Button>
        </div>
      </div>

      <div className="max-h-80 overflow-y-auto px-3 py-2.5">
        {grouped.map(([type, items]) => (
          <div key={type} className="mb-3 last:mb-0">
            <p className="mb-1 text-caption font-medium text-muted-foreground">{TYPE_LABELS[type]}</p>
            <div className="space-y-1">
              {items.map((item) => (
                <CheckboxLabel key={keyOf(item)} className="text-body">
                  <Checkbox checked={selected.has(keyOf(item))} onChange={() => toggle(item)} />
                  <span className="tabular">{item.displayName}</span>
                  {item.totalBytes ? (
                    <span className="text-caption text-subtle-foreground">{formatBytes(item.totalBytes)}</span>
                  ) : null}
                  {item.resourceType === 'CPU' && item.description ? (
                    <span className="text-caption text-subtle-foreground">{item.description}</span>
                  ) : null}
                </CheckboxLabel>
              ))}
            </div>
          </div>
        ))}
      </div>

      {saveMutation.isError ? (
        <p className="border-t border-border px-3 py-2 text-body text-destructive">
          저장하지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      ) : null}
    </div>
  )
}

function keyOf(item: DiscoveredResource) {
  return `${item.resourceType}\u0000${item.resourceKey}`
}

function groupByType(resources: DiscoveredResource[]) {
  const groups = new Map<ResourceType, DiscoveredResource[]>()
  for (const resource of resources) {
    const existing = groups.get(resource.resourceType)
    if (existing) {
      existing.push(resource)
    } else {
      groups.set(resource.resourceType, [resource])
    }
  }
  return [...groups.entries()].sort((left, right) => TYPE_ORDER[left[0]] - TYPE_ORDER[right[0]])
}
