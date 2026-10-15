import { TriangleAlert } from 'lucide-react'

import type { AlertStatus, MonitoringStatus, SnmpState } from '@/lib/api'
import { cn } from '@/lib/utils'

/**
 * 모양 하나를 색만 바꿔 쓰지 않습니다. 위험은 아이콘, 경고는 마름모, 정상은 채운 점,
 * 알 수 없음은 회색 점, 꺼진 것은 빈 점입니다. 모양 옆에는 항상 문구를 씁니다.
 */
type Mark = 'critical' | 'warning' | 'ok' | 'unknown' | 'off'

type StatusStyle = {
  label: string
  mark: Mark
}

/** 행동이 필요한 상태만 글자를 강조합니다. 정상은 화면에서 가장 많아 조용해야 합니다. */
const TEXT_BY_MARK: Record<Mark, string> = {
  critical: 'font-semibold text-critical-foreground',
  warning: 'font-medium text-warning-foreground',
  ok: 'text-foreground',
  unknown: 'text-muted-foreground',
  off: 'text-muted-foreground',
}

function StatusMark({ mark }: { mark: Mark }) {
  if (mark === 'critical') {
    return <TriangleAlert className="size-3.5 shrink-0 text-critical" strokeWidth={2.5} aria-hidden />
  }
  if (mark === 'warning') {
    return <span className="size-2 shrink-0 rotate-45 bg-warning" aria-hidden />
  }
  return (
    <span
      className={cn(
        'size-2 shrink-0 rounded-full',
        mark === 'ok' && 'bg-ok',
        mark === 'unknown' && 'bg-neutral',
        mark === 'off' && 'border-2 border-neutral',
      )}
      aria-hidden
    />
  )
}

function StatusLabel({ style, className }: { style: StatusStyle; className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-1.5 whitespace-nowrap', TEXT_BY_MARK[style.mark], className)}>
      <StatusMark mark={style.mark} />
      {style.label}
    </span>
  )
}

const STATUS_STYLES: Record<MonitoringStatus, StatusStyle> = {
  OK: { label: '정상', mark: 'ok' },
  CRITICAL: { label: '위험', mark: 'critical' },
  UNKNOWN: { label: '알 수 없음', mark: 'unknown' },
  DISABLED: { label: '사용 안 함', mark: 'off' },
}

export function StatusDot({ status, className }: { status: MonitoringStatus; className?: string }) {
  return <StatusLabel style={STATUS_STYLES[status]} className={className} />
}

const SNMP_STATES: Record<SnmpState, StatusStyle> = {
  CONNECTED: { label: '연결됨', mark: 'ok' },
  NO_RESPONSE: { label: '응답 없음', mark: 'critical' },
  DISABLED: { label: '사용 안 함', mark: 'off' },
  NOT_CONFIGURED: { label: '미설정', mark: 'off' },
}

export function SnmpStateDot({ state, className }: { state: SnmpState; className?: string }) {
  return <StatusLabel style={SNMP_STATES[state]} className={className} />
}

const ALERT_STATES: Record<AlertStatus, StatusStyle> = {
  FIRING: { label: '발생 중', mark: 'critical' },
  RESOLVED: { label: '해소', mark: 'ok' },
}

export function AlertStatusLabel({ status, className }: { status: AlertStatus; className?: string }) {
  return <StatusLabel style={ALERT_STATES[status]} className={className} />
}

export function StatusCount({ status, label, value }: { status: MonitoringStatus; label: string; value: number }) {
  return (
    <div className="flex items-center gap-2">
      <StatusMark mark={STATUS_STYLES[status].mark} />
      <span className="text-body text-muted-foreground">{label}</span>
      <span className="tabular text-body font-medium">{value}</span>
    </div>
  )
}
