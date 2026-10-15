/** 서버는 모든 시각을 UTC로 내려줍니다. 화면에서는 보는 사람의 시간대로 바꿔 보여 줍니다. */
const DATE_TIME_FORMAT = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })

/** `2026. 10. 7. 오전 10:20`처럼 한글 맞춤법의 날짜와 시각 형식으로 씁니다. */
export function formatDateTime(value: string) {
  return DATE_TIME_FORMAT.format(new Date(value))
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
