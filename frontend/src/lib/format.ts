/** 서버는 모든 시각을 UTC로 내려줍니다. 화면에서는 보는 사람의 시간대로 바꿔 보여 줍니다. */
const DATE_TIME_FORMAT = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })

/** `2026. 10. 7. 오전 10:20`처럼 한글 맞춤법의 날짜와 시각 형식으로 씁니다. */
export function formatDateTime(value: string) {
  return DATE_TIME_FORMAT.format(new Date(value))
}

const TIME_FORMAT = new Intl.DateTimeFormat('ko-KR', { timeStyle: 'short' })

/** `오후 2:41`처럼 시각만 씁니다. 오늘 일어난 일의 시각을 짧게 보일 때 씁니다. */
export function formatTime(value: string | number | Date) {
  return TIME_FORMAT.format(new Date(value))
}

/** 장애가 이어진 시간을 `12분째`, `1시간 38분째`, `2일 3시간째`처럼 씁니다. */
export function formatElapsed(since: string, now: number = Date.now()) {
  const minutes = Math.max(0, Math.floor((now - new Date(since).getTime()) / 60_000))
  if (minutes < 1) {
    return '1분 미만'
  }
  if (minutes < 60) {
    return `${minutes}분째`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return minutes % 60 === 0 ? `${hours}시간째` : `${hours}시간 ${minutes % 60}분째`
  }
  const days = Math.floor(hours / 24)
  return hours % 24 === 0 ? `${days}일째` : `${days}일 ${hours % 24}시간째`
}

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']

/** SNMP가 주는 값은 byte입니다. 화면에서는 운영자가 읽는 단위로 바꿉니다. */
export function formatBytes(bytes: number) {
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < BYTE_UNITS.length - 1) {
    value /= 1024
    unit += 1
  }
  const digits = value < 10 && unit > 0 ? 1 : 0
  return `${value.toFixed(digits)} ${BYTE_UNITS[unit]}`
}

export function formatPercent(value: number) {
  return `${value.toFixed(value < 10 ? 1 : 0)}%`
}
