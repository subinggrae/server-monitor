#!/bin/sh
# 운영자가 코어 서버의 설정 파일(.env)에서 바꾸는 코어 값의 기본값, 허용 범위, 검사를 한곳에 둔다(요구사항 0013).
# 묶음 폴더의 core-server.sh가 이 파일을 . 으로 읽어, 띄우기와 설정 펼치기(render)에서 같은 규칙으로 검사한다.
# 빌드 PC의 설정 검사(scripts/build-core-bundle.mjs)는 core_settings_ranges의 출력으로 허용 범위를 읽는다.
#
# 코어 서버와 빌드 PC의 검사에 쓰는 sh가 BusyBox일 수 있어 POSIX sh로 쓴다. 읽는 쪽의 변수와 겹치지 않게 변수 이름 앞에 spec_, cs_, core_를 붙인다.
# 값은 셸로 해석하지 않고 printf '%s'로만 내보낸다. 검사를 통과한 값은 앞에 0이 없는 10진 정수뿐이다.

CORE_SETTING_NAMES="CORE_RETENTION_DAYS CORE_RETENTION_SIZE_GB CORE_PROBE_TIMEOUT_SECONDS CORE_PROMETHEUS_PORT CORE_ALERTMANAGER_PORT CORE_BLACKBOX_PORT"

# 상한이 없는 값(보관 용량)도 이 값(9자리)까지만 받는다. 셸의 수 비교와 Prometheus의 바이트 계산이 넘치지 않게 한다.
CORE_SETTING_UNBOUNDED_MAX=999999999

# 값 하나의 뜻을 spec_* 변수에 둔다. 모르는 이름이면 1을 돌려준다.
# spec_default가 비면 제한 없음, spec_max가 비면 자릿수 상한만 있다.
# 확인 제한 시간의 상한 9초는 Prometheus scrape_timeout(10초)에서 Blackbox의 --timeout-offset(0.5초)을 뺀 값 안의 가장 큰 정수다.
# 보관 기간의 하한 1일은 규칙이 읽는 가장 긴 범위(10분)보다 길다. 두 약속은 빌드 PC의 설정 검사가 다시 확인한다.
core_setting_spec() {
  case "$1" in
    CORE_RETENTION_DAYS)
      spec_label='기록 보관 기간' spec_unit='일' spec_default=15 spec_min=1 spec_max=365
      spec_reason='1일보다 짧으면 볼 기록이 거의 남지 않고, 365일보다 길면 디스크를 많이 씁니다.' ;;
    CORE_RETENTION_SIZE_GB)
      spec_label='기록 보관 용량' spec_unit='GB' spec_default='' spec_min=1 spec_max=''
      spec_reason='0GB로는 기록을 남길 수 없습니다. 제한하지 않으려면 값을 비워 주세요.' ;;
    CORE_PROBE_TIMEOUT_SECONDS)
      spec_label='주소 확인 제한 시간' spec_unit='초' spec_default=5 spec_min=1 spec_max=9
      spec_reason='0초로는 기다릴 수 없고, 코어가 결과를 10초 안에 받아야 해서 9초가 최대입니다.' ;;
    CORE_PROMETHEUS_PORT)
      spec_label='Prometheus 포트' spec_unit='' spec_default=9090 spec_min=1024 spec_max=65535 ;;
    CORE_ALERTMANAGER_PORT)
      spec_label='Alertmanager 포트' spec_unit='' spec_default=9093 spec_min=1024 spec_max=65535 ;;
    CORE_BLACKBOX_PORT)
      spec_label='Blackbox Exporter 포트' spec_unit='' spec_default=9115 spec_min=1024 spec_max=65535 ;;
    *) return 1 ;;
  esac
  case "$1" in
    *_PORT) spec_reason='1024보다 작은 포트는 시스템이 쓰는 포트이고, 65535가 가장 큰 포트 번호입니다.' ;;
  esac
}

# core_setting_spec 다음에 부른다. 허용 범위를 사람이 읽는 글로 쓴다.
core_setting_range() {
  if [ -n "$spec_max" ]; then
    printf '%s~%s' "$spec_min" "$spec_max"
  else
    printf '%s 이상 %s 이하' "$spec_min" "$CORE_SETTING_UNBOUNDED_MAX"
  fi
  if [ -n "$spec_unit" ]; then
    printf '(%s)' "$spec_unit"
  fi
}

# 이름, 적힌 값, 문제, 이유(없어도 됨)를 받아 안내를 표준 오류에 쓴다. 고치는 방법은 core_setting_check가 정한다.
core_setting_error() {
  printf "설정 값이 올바르지 않습니다: %s='%s' (%s)\n" "$1" "$2" "$spec_label" >&2
  printf '  문제: %s\n' "$3" >&2
  if [ -n "${4:-}" ]; then
    printf '  이유: %s\n' "$4" >&2
  fi
  printf '  허용 범위: %s\n' "$(core_setting_range)" >&2
  printf '  고치는 방법: %s %s\n' "$cs_fix" "$cs_empty_hint" >&2
}

# 값 하나를 검사한다. 맞으면 0을 돌려주고 core_value에 쓸 값을 둔다(비어 있으면 기본값, 보관 용량은 빈 값이 제한 없음).
# 틀리면 이름, 적힌 값, 문제, 허용 범위, 고치는 방법을 표준 오류에 쓰고 1을 돌려준다.
# 세 번째 인자는 고치는 방법의 첫 문장이다. 비우면 .env의 값을 고쳐 다시 띄우라고 안내한다.
core_setting_check() {
  if ! core_setting_spec "$1"; then
    printf '알 수 없는 코어 설정 이름입니다: %s\n' "$1" >&2
    return 1
  fi
  cs_fix=${3:-".env의 $1 값을 고친 뒤 코어를 다시 띄워 주세요."}
  if [ -n "$spec_default" ]; then
    cs_empty_hint="값을 지우거나 비워 두면 기본값을 씁니다(기본값: $spec_default)."
  else
    cs_empty_hint='값을 지우거나 비워 두면 용량을 제한하지 않습니다.'
  fi
  # 앞뒤 공백만 지운다. 가운데의 공백과 줄바꿈은 아래 형식 검사에서 거부한다.
  core_value=$(printf '%s' "$2" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
  if [ -z "$core_value" ]; then
    core_value=$spec_default
    return 0
  fi
  # [0-9] 같은 범위는 locale에 따라 전각 숫자에도 맞으므로 글자를 나열한다.
  case "$core_value" in
    *[!0123456789]*|0?*)
      core_setting_error "$1" "$2" "단위나 앞의 0 없이 숫자만 적을 수 있습니다(예: ${spec_default:-50})."
      return 1 ;;
  esac
  if [ -n "$spec_max" ]; then
    cs_digits=${#spec_max}
  else
    cs_digits=${#CORE_SETTING_UNBOUNDED_MAX}
  fi
  if [ "${#core_value}" -gt "$cs_digits" ]; then
    if [ -n "$spec_max" ]; then
      core_setting_error "$1" "$2" '허용 범위를 벗어났습니다.' "$spec_reason"
    else
      core_setting_error "$1" "$2" "${#CORE_SETTING_UNBOUNDED_MAX}자리까지만 적을 수 있습니다."
    fi
    return 1
  fi
  # 비교가 오류로 끝나도 받아들이지 않도록, 범위 안임을 확인했을 때만 통과시킨다.
  if { [ "$core_value" -ge "$spec_min" ] && { [ -z "$spec_max" ] || [ "$core_value" -le "$spec_max" ]; }; } 2> /dev/null; then
    return 0
  fi
  core_setting_error "$1" "$2" '허용 범위를 벗어났습니다.' "$spec_reason"
  return 1
}

# 관리자 서버 주소(컨테이너 이름:포트)의 형식이 맞으면 0을 돌려준다. 운영 명령이 펼치기 전에 쓴다(요구사항 0013의 S16).
# 이름은 영문자나 숫자로 시작하고 영문자, 숫자, _, ., -만 쓰며, 포트는 1~65535다.
# 줄마다 맞춰 보는 grep 대신 case로 값 전체를 보고, locale에 따라 범위가 달라지지 않게 글자를 나열한다.
core_valid_address() {
  cs_host=${1%:*}
  cs_port=${1##*:}
  [ "$cs_host" != "$1" ] || return 1
  case "$cs_host" in
    ''|*[!ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-]*) return 1 ;;
    [!ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789]*) return 1 ;;
  esac
  case "$cs_port" in
    ''|*[!0123456789]*) return 1 ;;
  esac
  [ "${#cs_port}" -le 5 ] || return 1
  { [ "$cs_port" -ge 1 ] && [ "$cs_port" -le 65535 ]; } 2> /dev/null
}

# 검사를 통과한 값에 단위를 붙여 설정 템플릿의 @이름@ 자리에 넣을 글자를 쓴다. 포트는 템플릿에 펼치지 않는다.
# 보관 기간과 용량은 prometheus.yml의 storage.tsdb.retention에 들어간다. 용량이 비면 0B이고 Prometheus는 이를 제한 없음으로 읽는다.
# Prometheus의 GB는 1024 기준이다.
core_setting_text() {
  case "$1" in
    CORE_RETENTION_DAYS) printf '%sd' "$2" ;;
    CORE_RETENTION_SIZE_GB) if [ -n "$2" ]; then printf '%sGB' "$2"; else printf '0B'; fi ;;
    CORE_PROBE_TIMEOUT_SECONDS) printf '%ss' "$2" ;;
    *) printf '%s' "$2" ;;
  esac
}

# 빌드 PC의 설정 검사가 읽는 목록이다. 한 줄에 이름|기본값|하한|상한이다(기본값의 빈 칸은 제한 없음).
# 상한이 없는 값은 받는 가장 큰 값을 상한 칸에 쓴다.
core_settings_ranges() {
  for cs_name in $CORE_SETTING_NAMES; do
    core_setting_spec "$cs_name"
    printf '%s|%s|%s|%s\n' "$cs_name" "$spec_default" "$spec_min" "${spec_max:-$CORE_SETTING_UNBOUNDED_MAX}"
  done
}
