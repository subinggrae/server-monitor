import { parseIntegerInRange } from '@/lib/utils'

/** Check와 SNMP 설정이 함께 쓰는 수집 주기 입력 규칙입니다. 범위는 Backend 요청 검증과 같습니다. */

export const SCRAPE_INTERVAL_HINT = '10초에서 240초(4분) 사이로 정합니다. 길게 잡을수록 장애 알림도 늦게 옵니다.'

export const SCRAPE_INTERVAL_ERROR = '수집 주기는 10초에서 240초 사이 정수로 입력해 주세요.'

/** 입력값을 초로 바꿉니다. 정수가 아니거나 범위 밖이면 null입니다. */
export function parseScrapeIntervalSeconds(value: string): number | null {
  return parseIntegerInRange(value, 10, 240)
}
