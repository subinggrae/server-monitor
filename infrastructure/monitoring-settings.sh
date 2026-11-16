#!/bin/sh
# 운영자가 모니터링 서버의 설정 파일(.env)에서 바꾸는 설정 값의 기본값, 허용 범위, 검사를 한곳에 둔다(요구사항 0013).
# 묶음 폴더의 monitoring-server.sh가 이 파일을 . 으로 읽어, 띄우기와 설정 펼치기(render)에서 같은 규칙으로 검사한다.
# 빌드 PC의 설정 검사(scripts/build-monitoring-bundle.mjs)는 monitoring_settings_ranges의 출력으로 허용 범위를 읽는다.
#
# 모니터링 서버와 빌드 PC의 검사에 쓰는 sh가 BusyBox일 수 있어 POSIX sh로 쓴다. 읽는 쪽의 변수와 겹치지 않게 변수 이름 앞에 spec_, ms_, monitoring_를 붙인다.
# 값은 셸로 해석하지 않고 printf '%s'로만 내보낸다. 검사를 통과한 값은 앞에 0이 없는 10진 정수, 또는 켜고 끄는 값의 on, off뿐이다.

MONITORING_SETTING_NAMES="MONITORING_RETENTION_DAYS MONITORING_RETENTION_SIZE_GB MONITORING_PROBE_TIMEOUT_SECONDS MONITORING_PROMETHEUS_PORT MONITORING_ALERTMANAGER_PORT MONITORING_BLACKBOX_PORT"

# 상한이 없는 값(보관 용량)도 이 값(9자리)까지만 받는다. 셸의 수 비교와 Prometheus의 바이트 계산이 넘치지 않게 한다.
MONITORING_SETTING_UNBOUNDED_MAX=999999999

# 값 하나의 뜻을 spec_* 변수에 둔다. 모르는 이름이면 1을 돌려준다.
# spec_default가 비면 제한 없음, spec_max가 비면 자릿수 상한만 있다.
# 확인 제한 시간의 상한 9초는 Prometheus scrape_timeout(10초)에서 Blackbox의 --timeout-offset(0.5초)을 뺀 값 안의 가장 큰 정수다.
# 보관 기간의 하한 1일은 규칙이 읽는 가장 긴 범위(10분)보다 길다. 두 약속은 빌드 PC의 설정 검사가 다시 확인한다.
monitoring_setting_spec() {
  case "$1" in
    MONITORING_RETENTION_DAYS)
      spec_label='기록 보관 기간' spec_unit='일' spec_default=15 spec_min=1 spec_max=365
      spec_reason='1일보다 짧으면 볼 기록이 거의 남지 않고, 365일보다 길면 디스크를 많이 씁니다.' ;;
    MONITORING_RETENTION_SIZE_GB)
      spec_label='기록 보관 용량' spec_unit='GB' spec_default='' spec_min=1 spec_max=''
      spec_reason='0GB로는 기록을 남길 수 없습니다. 제한하지 않으려면 값을 비워 주세요.' ;;
    MONITORING_PROBE_TIMEOUT_SECONDS)
      spec_label='주소 확인 제한 시간' spec_unit='초' spec_default=5 spec_min=1 spec_max=9
      spec_reason='0초로는 기다릴 수 없고, 모니터링 시스템이 결과를 10초 안에 받아야 해서 9초가 최대입니다.' ;;
    MONITORING_PROMETHEUS_PORT)
      spec_label='Prometheus 포트' spec_unit='' spec_default=9090 spec_min=1024 spec_max=65535 ;;
    MONITORING_ALERTMANAGER_PORT)
      spec_label='Alertmanager 포트' spec_unit='' spec_default=9093 spec_min=1024 spec_max=65535 ;;
    MONITORING_BLACKBOX_PORT)
      spec_label='Blackbox Exporter 포트' spec_unit='' spec_default=9115 spec_min=1024 spec_max=65535 ;;
    *) return 1 ;;
  esac
  case "$1" in
    *_PORT) spec_reason='1024보다 작은 포트는 시스템이 쓰는 포트이고, 65535가 가장 큰 포트 번호입니다.' ;;
  esac
}

# monitoring_setting_spec 다음에 부른다. 허용 범위를 사람이 읽는 글로 쓴다.
monitoring_setting_range() {
  if [ -n "$spec_max" ]; then
    printf '%s~%s' "$spec_min" "$spec_max"
  else
    printf '%s 이상 %s 이하' "$spec_min" "$MONITORING_SETTING_UNBOUNDED_MAX"
  fi
  if [ -n "$spec_unit" ]; then
    printf '(%s)' "$spec_unit"
  fi
}

# 이름, 적힌 값, 문제, 이유(없어도 됨)를 받아 안내를 표준 오류에 쓴다. 고치는 방법은 monitoring_setting_check가 정한다.
monitoring_setting_error() {
  printf "설정 값이 올바르지 않습니다: %s='%s' (%s)\n" "$1" "$2" "$spec_label" >&2
  printf '  문제: %s\n' "$3" >&2
  if [ -n "${4:-}" ]; then
    printf '  이유: %s\n' "$4" >&2
  fi
  printf '  허용 범위: %s\n' "$(monitoring_setting_range)" >&2
  printf '  고치는 방법: %s %s\n' "$ms_fix" "$ms_empty_hint" >&2
}

# 값 하나를 검사한다. 맞으면 0을 돌려주고 monitoring_value에 쓸 값을 둔다(비어 있으면 기본값, 보관 용량은 빈 값이 제한 없음).
# 틀리면 이름, 적힌 값, 문제, 허용 범위, 고치는 방법을 표준 오류에 쓰고 1을 돌려준다.
# 세 번째 인자는 고치는 방법의 첫 문장이다. 비우면 .env의 값을 고쳐 다시 띄우라고 안내한다.
monitoring_setting_check() {
  if ! monitoring_setting_spec "$1"; then
    printf '알 수 없는 설정 이름입니다: %s\n' "$1" >&2
    return 1
  fi
  ms_fix=${3:-".env의 $1 값을 고친 뒤 모니터링 시스템을 다시 띄워 주세요."}
  if [ -n "$spec_default" ]; then
    ms_empty_hint="값을 지우거나 비워 두면 기본값을 씁니다(기본값: $spec_default)."
  else
    ms_empty_hint='값을 지우거나 비워 두면 용량을 제한하지 않습니다.'
  fi
  # 앞뒤 공백만 지운다. 가운데의 공백과 줄바꿈은 아래 형식 검사에서 거부한다.
  monitoring_value=$(printf '%s' "$2" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
  if [ -z "$monitoring_value" ]; then
    monitoring_value=$spec_default
    return 0
  fi
  # [0-9] 같은 범위는 locale에 따라 전각 숫자에도 맞으므로 글자를 나열한다.
  case "$monitoring_value" in
    *[!0123456789]*|0?*)
      monitoring_setting_error "$1" "$2" "단위나 앞의 0 없이 숫자만 적을 수 있습니다(예: ${spec_default:-50})."
      return 1 ;;
  esac
  if [ -n "$spec_max" ]; then
    ms_digits=${#spec_max}
  else
    ms_digits=${#MONITORING_SETTING_UNBOUNDED_MAX}
  fi
  if [ "${#monitoring_value}" -gt "$ms_digits" ]; then
    if [ -n "$spec_max" ]; then
      monitoring_setting_error "$1" "$2" '허용 범위를 벗어났습니다.' "$spec_reason"
    else
      monitoring_setting_error "$1" "$2" "${#MONITORING_SETTING_UNBOUNDED_MAX}자리까지만 적을 수 있습니다."
    fi
    return 1
  fi
  # 비교가 오류로 끝나도 받아들이지 않도록, 범위 안임을 확인했을 때만 통과시킨다.
  if { [ "$monitoring_value" -ge "$spec_min" ] && { [ -z "$spec_max" ] || [ "$monitoring_value" -le "$spec_max" ]; }; } 2> /dev/null; then
    return 0
  fi
  monitoring_setting_error "$1" "$2" '허용 범위를 벗어났습니다.' "$spec_reason"
  return 1
}

# 관리자 서버 주소(컨테이너 이름:포트)의 형식이 맞으면 0을 돌려준다. 운영 명령이 펼치기 전에 쓴다(요구사항 0013의 S16).
# 이름은 영문자나 숫자로 시작하고 영문자, 숫자, _, ., -만 쓰며, 포트는 1~65535다.
# 줄마다 맞춰 보는 grep 대신 case로 값 전체를 보고, locale에 따라 범위가 달라지지 않게 글자를 나열한다.
monitoring_valid_address() {
  ms_host=${1%:*}
  ms_port=${1##*:}
  [ "$ms_host" != "$1" ] || return 1
  case "$ms_host" in
    ''|*[!ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-]*) return 1 ;;
    [!ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789]*) return 1 ;;
  esac
  case "$ms_port" in
    ''|*[!0123456789]*) return 1 ;;
  esac
  [ "${#ms_port}" -le 5 ] || return 1
  { [ "$ms_port" -ge 1 ] && [ "$ms_port" -le 65535 ]; } 2> /dev/null
}

# 검사를 통과한 값에 단위를 붙여 설정 템플릿의 @이름@ 자리에 넣을 글자를 쓴다. 포트는 템플릿에 펼치지 않는다.
# 보관 기간과 용량은 prometheus.yml의 storage.tsdb.retention에 들어간다. 용량이 비면 0B이고 Prometheus는 이를 제한 없음으로 읽는다.
# Prometheus의 GB는 1024 기준이다.
monitoring_setting_text() {
  case "$1" in
    MONITORING_RETENTION_DAYS) printf '%sd' "$2" ;;
    MONITORING_RETENTION_SIZE_GB) if [ -n "$2" ]; then printf '%sGB' "$2"; else printf '0B'; fi ;;
    MONITORING_PROBE_TIMEOUT_SECONDS) printf '%ss' "$2" ;;
    *) printf '%s' "$2" ;;
  esac
}

# 켜고 끄는 값이다(요구사항 0022). 숫자 값과 따로 두어 빌드 PC가 읽는 허용 범위 목록(monitoring_settings_ranges)에 넣지 않는다.
# 값은 on, off만 받고, 설정 파일에 글자로 펼치지 않는다. 운영 명령이 이 값으로 설정의 한 부분을 넣거나 뺀다.
MONITORING_SWITCH_NAMES="MONITORING_OS_EXPORTER"

# 켜고 끄는 값 하나의 뜻을 spec_* 변수에 둔다. 모르는 이름이면 1을 돌려준다.
monitoring_switch_spec() {
  case "$1" in
    MONITORING_OS_EXPORTER) spec_label='서버 사용량 수집' spec_default=on ;;
    *) return 1 ;;
  esac
}

# 켜고 끄는 값 하나를 검사한다. 맞으면 0을 돌려주고 monitoring_value에 on이나 off를 둔다(비어 있으면 기본값).
# 틀리면 숫자 값과 같은 형식으로 이름, 적힌 값, 문제, 허용 값, 고치는 방법을 표준 오류에 쓰고 1을 돌려준다.
monitoring_switch_check() {
  if ! monitoring_switch_spec "$1"; then
    printf '알 수 없는 설정 이름입니다: %s\n' "$1" >&2
    return 1
  fi
  monitoring_value=$(printf '%s' "$2" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
  case "$monitoring_value" in
    '') monitoring_value=$spec_default; return 0 ;;
    on|off) return 0 ;;
  esac
  printf "설정 값이 올바르지 않습니다: %s='%s' (%s)\n" "$1" "$2" "$spec_label" >&2
  printf '  문제: 소문자 on이나 off만 적을 수 있습니다.\n' >&2
  printf '  허용 값: on, off\n' >&2
  printf '  고치는 방법: %s 값을 지우거나 비워 두면 기본값을 씁니다(기본값: %s).\n' \
    "${3:-".env의 $1 값을 고친 뒤 모니터링 시스템을 다시 띄워 주세요."}" "$spec_default" >&2
  return 1
}

# 빌드 PC의 설정 검사가 읽는 목록이다. 한 줄에 이름|기본값|하한|상한이다(기본값의 빈 칸은 제한 없음).
# 상한이 없는 값은 받는 가장 큰 값을 상한 칸에 쓴다.
monitoring_settings_ranges() {
  for ms_name in $MONITORING_SETTING_NAMES; do
    monitoring_setting_spec "$ms_name"
    printf '%s|%s|%s|%s\n' "$ms_name" "$spec_default" "$spec_min" "${spec_max:-$MONITORING_SETTING_UNBOUNDED_MAX}"
  done
}
