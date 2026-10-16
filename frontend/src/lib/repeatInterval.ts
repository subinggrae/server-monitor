import { parseIntegerInRange } from '@/lib/utils'

/** Check 알림 설정의 재발송 주기 입력 규칙입니다. 범위는 Backend 요청 검증과 같습니다. */

export const REPEAT_INTERVAL_HINT =
  '5분에서 1440분(24시간) 사이로 정합니다. 장애가 이어지는 동안 이 주기로 다시 알립니다. 다시 보낼지는 5분마다 판단하므로, 실제 알림은 최대 5분 늦거나 1분 이르게 올 수 있습니다.'

export const REPEAT_INTERVAL_ERROR = '재발송 주기는 5분에서 1440분 사이 정수로 입력해 주세요.'

/** 입력값을 분으로 바꿉니다. 정수가 아니거나 범위 밖이면 null입니다. */
export function parseRepeatIntervalMinutes(value: string): number | null {
  return parseIntegerInRange(value, 5, 1440)
}
