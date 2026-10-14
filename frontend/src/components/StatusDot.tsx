import type { MonitoringStatus, SnmpState } from '@/lib/api'
import { cn } from '@/lib/utils'

type StatusStyle = {
  label: string
  dot: string
  text: string
}

/**
 * 색만으로 구분하지 않습니다. 점 옆에 항상 한국어 문구를 함께 씁니다.
 * 배경을 칠한 pill을 쓰지 않습니다. 상태는 화면의 주인공이 아닙니다.
 */
const STATUS_STYLES: Record<MonitoringStatus, StatusStyle> = {
  OK: { label: '정상', dot: 'bg-ok', text: 'text-foreground' },
  CRITICAL: { label: '위험', dot: 'bg-critical', text: 'text-critical font-medium' },
  UNKNOWN: { label: '알 수 없음', dot: 'bg-neutral', text: 'text-muted-foreground' },
  DISABLED: { label: '사용 안 함', dot: 'bg-neutral/50', text: 'text-muted-foreground' },
}

export function StatusDot({
  status,
  className,
}: {
  status: MonitoringStatus
  className?: string
}) {
  const style = STATUS_STYLES[status]

  return (
    <span className={cn('inline-flex items-center gap-1.5 whitespace-nowrap', style.text, className)}>
      <span className={cn('size-1.5 shrink-0 rounded-full', style.dot)} aria-hidden />
      {style.label}
    </span>
  )
}

const SNMP_STATES: Record<SnmpState, StatusStyle> = {
  CONNECTED: { label: '연결됨', dot: 'bg-ok', text: 'text-foreground' },
  NO_RESPONSE: { label: '응답 없음', dot: 'bg-critical', text: 'text-critical font-medium' },
  DISABLED: { label: '사용 안 함', dot: 'bg-neutral/50', text: 'text-muted-foreground' },
  NOT_CONFIGURED: { label: '미설정', dot: 'bg-neutral/40', text: 'text-subtle-foreground' },
}

export function SnmpStateDot({ state, className }: { state: SnmpState; className?: string }) {
  const style = SNMP_STATES[state]

  return (
    <span className={cn('inline-flex items-center gap-1.5 whitespace-nowrap', style.text, className)}>
      <span className={cn('size-1.5 shrink-0 rounded-full', style.dot)} aria-hidden />
      {style.label}
    </span>
  )
}

export function StatusCount({
  status,
  label,
  value,
}: {
  status: MonitoringStatus
  label: string
  value: number
}) {
  const style = STATUS_STYLES[status]

  return (
    <div className="flex items-baseline gap-2">
      <span className={cn('size-1.5 shrink-0 translate-y-[-0.15rem] rounded-full', style.dot)} aria-hidden />
      <span className="text-[13px] text-muted-foreground">{label}</span>
      <span className="tabular text-[13px] font-medium">{value}</span>
    </div>
  )
}
