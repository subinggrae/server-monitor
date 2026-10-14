/**
 * 서버는 모든 시각을 UTC로 내려줍니다. 화면에서는 보는 사람의 시간대로 바꿔 보여 줍니다.
 * 표 안에서 줄바꿈이 생기지 않도록 24시간 표기를 씁니다.
 */
export function formatDateTime(value: string) {
  const date = new Date(value)
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
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
  return `${value.toFixed(digits)} ${BYTE_UNITS[unit]}`
}

export function formatPercent(value: number) {
  return `${value.toFixed(value < 10 ? 1 : 0)}%`
}
