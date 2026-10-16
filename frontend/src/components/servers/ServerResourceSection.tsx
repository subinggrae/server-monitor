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
  EXPORTER_TYPE_LABELS,
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

const TYPE_LABELS: Record<ResourceType, string> = { CPU: 'CPU', MEMORY: 'Memory', STORAGE: 'Storage' }

/**
 * 고른 리소스와 지금 값입니다. 리소스 찾기는 켠 수집 설정의 exporter를 읽으므로 수집이 꺼져 있으면 막습니다.
 * 탭 이름(리소스)을 절 제목으로 되풀이하지 않습니다.
 */
export function ServerResourceSection({
  serverId,
  collectionEnabled,
  collectionFailed,
  onOpenCollection,
}: {
  serverId: number
  collectionEnabled: boolean
  /** 켜져 있지만 Prometheus가 exporter를 읽지 못하는 상태입니다. */
  collectionFailed: boolean
  onOpenCollection: () => void
}) {
  const resources = useQuery(serverResourcesQuery(serverId))
  const [discovery, setDiscovery] = useState<ResourceDiscoveryResult | null>(null)

  const discoverMutation = useMutation({
    mutationFn: () => apiPost<ResourceDiscoveryResult>(`/api/servers/${serverId}/resources/discover`, {}),
    onSuccess: (result) => setDiscovery(result),
    onError: () =>
      setDiscovery({
        success: false,
        message: '리소스를 찾지 못했습니다. 잠시 후 다시 시도해 주세요.',
        exporterType: null,
        version: null,
        resources: [],
      }),
  })

  return (
    <PageSection
      title="감시하는 리소스"
      description="exporter에서 찾은 CPU, Memory, Storage 가운데 고른 것만 사용률을 보입니다."
      actions={
        <Button
          variant="secondary"
          size="sm"
          disabled={!collectionEnabled || discoverMutation.isPending}
          onClick={() => discoverMutation.mutate()}
        >
          {discoverMutation.isPending ? '찾는 중…' : '리소스 찾기'}
        </Button>
      }
      state={queryState(resources, '리소스를 불러오지 못했습니다.')}
    >
      {!collectionEnabled ? (
        <div className="flex flex-wrap items-center gap-3">
          <p className="text-body text-muted-foreground">OS exporter 수집이 꺼져 있어 리소스를 찾을 수 없습니다.</p>
          <Button variant="secondary" size="sm" onClick={onOpenCollection}>
            수집 설정 열기
          </Button>
        </div>
      ) : null}

      {collectionEnabled && collectionFailed ? (
        <Notice tone="warning" title="exporter를 읽지 못하고 있습니다.">
          <div className="flex flex-wrap items-center gap-3">
            <p>수집 탭에서 연결 시험으로 원인을 확인해 주세요. 그동안 사용률 값은 비어 있습니다.</p>
            <Button variant="secondary" size="sm" onClick={onOpenCollection}>
              수집 설정 열기
            </Button>
          </div>
        </Notice>
      ) : null}

      {discoverMutation.isPending ? (
        <p className="text-body text-muted-foreground">리소스를 찾는 중…</p>
      ) : null}

      {discovery ? (
        <DiscoveryResult
          serverId={serverId}
          result={discovery}
          onDone={() => setDiscovery(null)}
        />
      ) : null}

      {/* 비어 있어도 위의 찾기 결과는 보여야 하므로 빈 상태를 절의 state로 두지 않습니다.
          수집이 꺼져 있으면 위 안내가 이미 다음 동작을 알려 주므로 같은 뜻을 되풀이하지 않습니다. */}
      {resources.data?.length === 0 ? (
        collectionEnabled && !collectionFailed && !discovery ? (
          <p className="text-body text-muted-foreground">
            감시하는 리소스가 없습니다. [리소스 찾기]로 CPU, Memory, Storage를 고릅니다.
          </p>
        ) : null
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
    return <Notice tone="warning" title={result.message} />
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
          <p className="text-body font-medium">찾은 리소스</p>
          {result.exporterType ? (
            <p className="mt-0.5 text-caption text-muted-foreground" translate="no">
              {EXPORTER_TYPE_LABELS[result.exporterType]} {result.version}
            </p>
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
