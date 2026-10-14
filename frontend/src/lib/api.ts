/**
 * Backend와 같은 origin으로 서비스하므로 여기에 Backend 주소를 넣지 않습니다.
 * 개발 서버는 Vite proxy가, Docker에서는 Nginx가 /api를 Backend로 넘깁니다.
 *
 * Prometheus를 직접 호출하거나 PromQL을 만들지 않습니다. (CLAUDE.md 12번)
 */

type ProblemDetail = {
  title?: string
  detail?: string
  fieldErrors?: Record<string, string>
}

export class ApiError extends Error {
  readonly status: number

  /** Form에서 어느 칸에 오류를 표시할지 판단할 때 씁니다. */
  readonly fieldErrors: Record<string, string>

  constructor(message: string, status: number, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

const FALLBACK_MESSAGE = '서버와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: { Accept: 'application/json', ...(init?.body ? { 'Content-Type': 'application/json' } : {}) },
    })
  } catch {
    // 네트워크 자체가 끊긴 경우입니다.
    throw new ApiError(FALLBACK_MESSAGE, 0)
  }

  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as ProblemDetail | null
    throw new ApiError(problem?.detail ?? FALLBACK_MESSAGE, response.status, problem?.fieldErrors ?? {})
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const apiGet = <T>(path: string) => request<T>(path)

export const apiPost = <T>(path: string, body: unknown) =>
  request<T>(path, { method: 'POST', body: JSON.stringify(body) })

export const apiPut = <T>(path: string, body: unknown) =>
  request<T>(path, { method: 'PUT', body: JSON.stringify(body) })

export const apiDelete = (path: string) => request<void>(path, { method: 'DELETE' })

// ---------------------------------------------------------------------------
// 공통 타입
// ---------------------------------------------------------------------------

export type MonitoringStatus = 'OK' | 'CRITICAL' | 'UNKNOWN' | 'DISABLED'

export type CheckTypeName = 'ICMP' | 'TCP' | 'HTTP' | 'HTTPS'

export type SystemInfo = {
  application: string
  grafanaUrl: string
  telegramConfigured: boolean
}

export type SnmpState = 'NOT_CONFIGURED' | 'DISABLED' | 'CONNECTED' | 'NO_RESPONSE'

export type ServerTarget = {
  id: number
  name: string
  host: string
  environment: string
  description: string | null
  enabled: boolean
  /** Threshold가 없어 사용률로 CRITICAL을 만들지 않습니다. OK / UNKNOWN / DISABLED만 나옵니다. */
  status: MonitoringStatus
  snmpState: SnmpState
  monitoredResourceCount: number
  createdAt: string
  updatedAt: string
}

export type ServerTargetInput = {
  name: string
  host: string
  environment: string
  description: string | null
  enabled: boolean
}

/** blackboxTarget은 Prometheus가 확인하는 최종 주소입니다. TCP는 host와 port를 합친 값입니다. */
export type MonitoringCheckTarget = {
  id: number
  target: string
  port: number | null
  blackboxTarget: string
  enabled: boolean
  status: MonitoringStatus
  checkedAt: string | null
}

export type MonitoringCheck = {
  id: number
  name: string
  type: CheckTypeName
  typeDisplayName: string
  environment: string
  description: string | null
  enabled: boolean
  status: MonitoringStatus
  targetCount: number
  targets: MonitoringCheckTarget[]
  createdAt: string
  updatedAt: string
}

/**
 * targets는 전체 목록입니다. id가 있으면 수정, 없으면 추가, 빠진 것은 삭제입니다.
 * id를 그대로 보내야 Prometheus에서 쌓아 온 기록이 끊기지 않습니다.
 */
export type MonitoringCheckInput = {
  name: string
  type: CheckTypeName
  environment: string
  description: string | null
  enabled: boolean
  targets: {
    id?: number
    target: string
    port: number | null
    enabled: boolean
  }[]
}

export type CheckTypeCapability = {
  type: CheckTypeName
  displayName: string
  requiresPort: boolean
  requiresUrl: boolean
}

export type CheckStatus = {
  checkId: number
  status: MonitoringStatus
  message: string
  targets: {
    targetId: number
    blackboxTarget: string
    status: MonitoringStatus
    success: boolean | null
    checkedAt: string | null
  }[]
}

// ---------------------------------------------------------------------------
// 알림
// ---------------------------------------------------------------------------

/** Bot Token은 응답에 들어오지 않고 화면에서 입력하지도 않습니다. (CLAUDE.md 11번) */
export type TelegramDestination = {
  id: number
  name: string
  chatId: number
  /** Forum topic을 쓰는 그룹에만 값이 있습니다. */
  messageThreadId: number | null
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export type TelegramDestinationInput = {
  name: string
  chatId: number
  messageThreadId: number | null
  enabled: boolean
}

export type TelegramStatus = {
  /** Bot Token을 읽었습니다. */
  configured: boolean
  /** /register를 쓸 수 있습니다. */
  webhookConfigured: boolean
  publicUrlConfigured: boolean
}

export type TestMessageResult = {
  success: boolean
  message: string
}

export type NotificationBinding = {
  id: number
  telegramDestinationId: number
  telegramDestinationName: string
  /** 그룹이 꺼져 있으면 연결이 켜져 있어도 알림이 가지 않습니다. */
  telegramDestinationEnabled: boolean
  warningEnabled: boolean
  criticalEnabled: boolean
  sendResolved: boolean
  enabled: boolean
}

export type CheckNotifications = {
  checkId: number
  bindings: NotificationBinding[]
}

export type CheckNotificationsInput = {
  bindings: {
    telegramDestinationId: number
    warningEnabled: boolean
    criticalEnabled: boolean
    sendResolved: boolean
    enabled: boolean
  }[]
}

/** 연결이 없는 Check도 들어 있습니다. */
export type CheckNotificationSummary = {
  checkId: number
  checkName: string
  environment: string
  checkEnabled: boolean
  bindings: NotificationBinding[]
}

// ---------------------------------------------------------------------------
// SNMP와 Resource
// ---------------------------------------------------------------------------

export type SnmpVersion = 'V2C'

/** community는 응답에 들어오지 않습니다. 설정 여부만 옵니다. (CLAUDE.md 11번) */
export type ServerSnmp = {
  configured: boolean
  version: SnmpVersion | null
  port: number | null
  communityConfigured: boolean
  enabled: boolean
  /** false면 저장은 됐지만 snmp_exporter에 반영되지 않은 상태입니다. */
  exporterSynced: boolean
  exporterMessage: string | null
}

/** community는 새로 입력했을 때만 보냅니다. null이면 서버가 기존 값을 그대로 둡니다. */
export type ServerSnmpInput = {
  version: SnmpVersion
  port: number
  community: string | null
  enabled: boolean
}

export type SnmpTestResult = {
  success: boolean
  message: string
  systemName: string | null
  systemDescription: string | null
  responseTimeMs: number | null
}

export type ResourceType = 'CPU' | 'MEMORY' | 'STORAGE'

export type ResourceStatus = 'AVAILABLE' | 'UNAVAILABLE' | 'DISABLED'

export type DiscoveredResource = {
  resourceType: ResourceType
  resourceKey: string
  displayName: string
  description: string | null
  snmpIndex: number | null
  totalBytes: number | null
  selected: boolean
}

export type ResourceDiscoveryResult = {
  success: boolean
  message: string
  systemName: string | null
  systemDescription: string | null
  resources: DiscoveredResource[]
}

export type ResourceUsage = {
  id: number
  resourceType: ResourceType
  resourceKey: string
  displayName: string
  description: string | null
  status: ResourceStatus
  usagePercent: number | null
  usedBytes: number | null
  totalBytes: number | null
  sampledAt: string | null
}

export type MonitoredResourceInput = {
  resources: {
    resourceType: ResourceType
    resourceKey: string
    displayName: string
    description: string | null
    snmpIndex: number | null
  }[]
}

export type AlertStatus = 'FIRING' | 'RESOLVED'

export type AlertEvent = {
  id: number
  alertName: string
  checkId: number | null
  checkTargetId: number | null
  severity: string | null
  status: AlertStatus
  instance: string | null
  summary: string | null
  startsAt: string
  resolvedAt: string | null
  lastReceivedAt: string
}

// ---------------------------------------------------------------------------
// Query key
// ---------------------------------------------------------------------------

export const queryKeys = {
  systemInfo: ['system-info'] as const,
  servers: ['servers'] as const,
  serverDetail: (serverId: number) => ['server-detail', serverId] as const,
  checks: ['checks'] as const,
  checkDetail: (checkId: number) => ['check-detail', checkId] as const,
  checkStatus: (checkId: number) => ['check-status', checkId] as const,
  checkTypes: ['check-types'] as const,
  telegramDestinations: ['telegram-destinations'] as const,
  telegramStatus: ['telegram-status'] as const,
  checkNotifications: (checkId: number) => ['check-notifications', checkId] as const,
  notificationBindings: ['notification-bindings'] as const,
  alerts: ['alerts'] as const,
  serverSnmp: (serverId: number) => ['server-snmp', serverId] as const,
  serverResources: (serverId: number) => ['server-resources', serverId] as const,
}

// ---------------------------------------------------------------------------
// Query 정의
// ---------------------------------------------------------------------------

export const systemInfoQuery = {
  queryKey: queryKeys.systemInfo,
  queryFn: () => apiGet<SystemInfo>('/api/system/info'),
}

export const serversQuery = {
  queryKey: queryKeys.servers,
  queryFn: () => apiGet<ServerTarget[]>('/api/servers'),
}

export const serverDetailQuery = (serverId: number) => ({
  queryKey: queryKeys.serverDetail(serverId),
  queryFn: () => apiGet<ServerTarget>(`/api/servers/${serverId}`),
})

export const checksQuery = {
  queryKey: queryKeys.checks,
  queryFn: () => apiGet<MonitoringCheck[]>('/api/checks'),
}

export const checkDetailQuery = (checkId: number) => ({
  queryKey: queryKeys.checkDetail(checkId),
  queryFn: () => apiGet<MonitoringCheck>(`/api/checks/${checkId}`),
})

export const checkTypesQuery = {
  queryKey: queryKeys.checkTypes,
  queryFn: () => apiGet<CheckTypeCapability[]>('/api/check-types'),
  // Check Type은 배포하지 않는 한 바뀌지 않습니다.
  staleTime: Infinity,
}

export const telegramDestinationsQuery = {
  queryKey: queryKeys.telegramDestinations,
  queryFn: () => apiGet<TelegramDestination[]>('/api/telegram/destinations'),
}

export const telegramStatusQuery = {
  queryKey: queryKeys.telegramStatus,
  queryFn: () => apiGet<TelegramStatus>('/api/telegram/status'),
}

export const checkNotificationsQuery = (checkId: number) => ({
  queryKey: queryKeys.checkNotifications(checkId),
  queryFn: () => apiGet<CheckNotifications>(`/api/checks/${checkId}/notifications`),
})

export const notificationBindingsQuery = {
  queryKey: queryKeys.notificationBindings,
  queryFn: () => apiGet<CheckNotificationSummary[]>('/api/notifications/bindings'),
}

export const alertsQuery = {
  queryKey: queryKeys.alerts,
  queryFn: () => apiGet<AlertEvent[]>('/api/alerts'),
}

export const serverSnmpQuery = (serverId: number) => ({
  queryKey: queryKeys.serverSnmp(serverId),
  queryFn: () => apiGet<ServerSnmp>(`/api/servers/${serverId}/snmp`),
})

export const serverResourcesQuery = (serverId: number) => ({
  queryKey: queryKeys.serverResources(serverId),
  queryFn: () => apiGet<ResourceUsage[]>(`/api/servers/${serverId}/resources`),
})
