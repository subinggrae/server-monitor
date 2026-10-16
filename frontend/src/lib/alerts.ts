import type { AlertEvent } from '@/lib/api'

/** 감시 항목마다 발생 중인 장애 가운데 가장 이른 시작 시각입니다. 장애 기록에 없으면 비어 있습니다. */
export function firingSince(alerts: AlertEvent[]) {
  const since = new Map<number, string>()
  for (const alert of alerts) {
    if (alert.status !== 'FIRING' || alert.checkId === null) {
      continue
    }
    const current = since.get(alert.checkId)
    if (!current || new Date(alert.startsAt) < new Date(current)) {
      since.set(alert.checkId, alert.startsAt)
    }
  }
  return since
}
