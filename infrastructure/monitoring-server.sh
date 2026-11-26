#!/bin/sh
# 내부망 모니터링 서버에서 모니터링 시스템을 설치하고 띄우는 운영 명령이다. 묶음 폴더 안에 있고, 묶음 폴더에서 실행한다.
#
#   sh monitoring-server.sh install   token과 설정 파일(.env)이 없으면 만든다
#   sh monitoring-server.sh render    설정 값을 검사해 설정을 rendered/에 펼친다. 컨테이너는 바꾸지 않는다
#   sh monitoring-server.sh start     확인하고 설정을 펼친 뒤 이미지를 불러와 띄우고 정상 상태까지 기다린다. 성공하면 오래된 버전을 지운다
#   sh monitoring-server.sh rollback  직전 버전의 묶음 폴더에서 start를 실행한다
#   sh monitoring-server.sh status    떠 있는 버전, 컨테이너 상태와 준비 여부, 기본값과 다른 설정 값을 보인다
#
# 최소 기능만 둔다. token 바꾸기는 운영 가이드의 절차로 한다(요구사항 0015).
# start가 성공하면 설치 폴더의 monitoring-versions에 지금 버전과 직전 버전을 적고, 두 버전 밖의 묶음 폴더와 이미지를 지운다(요구사항 0023).
#
# 묶음 폴더들은 한 설치 폴더에 나란히 두고, token은 설치 폴더의 secrets/에, 설정 파일은 설치 폴더의 .env에 둔다(요구사항 0011, 0017).
# .env에서 바꾸는 설정 값(보관 기간·용량, 확인 제한 시간, 호스트 포트)의 규칙은 같은 폴더의 monitoring-settings.sh에 있다(요구사항 0013).
# 모니터링 시스템 이미지에는 셸이 없어 컨테이너 안에서 설정을 펼칠 수 없다. 그래서 이 명령이 값을 검사해 묶음의 templates/에서
# rendered/로 펼치고, compose 파일이 펼친 설정을 읽기 전용으로 붙인다. 정상 확인도 모니터링 서버에서 한다(요구사항 0015, ADR 0012).
# Blackbox Exporter가 쓸 CA 인증서 파일은 운영자가 .env의 MONITORING_CA_FILE에 OS의 파일 경로를 적거나, 비워 두고 설치 폴더의
# certs/에 복사해 둔다(요구사항 0027). 이 명령은 OS의 파일을 찾거나 옮기지 않고 확인만 한다.
# 두 경로 값(token 폴더, CA 인증서 파일)은 검사한 값을 Compose에 넘기고, .env에 이 명령이 읽지 않는 형식으로 적은 줄이 있으면
# Compose가 다른 파일을 붙일 수 있어 멈춘다(요구사항 0028).
# 저장소의 infrastructure/에서도 render를 실행할 수 있다. 그때는 저장소의 템플릿, ./secrets, ./certs를 쓴다(monitoring-dev.bat start).
# 모니터링 서버에는 Node와 bash가 없을 수 있어 POSIX sh로 쓰고, GNU coreutils와 BusyBox가 함께 지원하는 옵션만 쓴다.
# 설치 방법은 docs/OPERATIONS.md에 있다.
set -eu

BUNDLE_DIR=$(cd "$(dirname "$0")" && pwd -P)
INSTALL_DIR=$(dirname "$BUNDLE_DIR")
RENDER_DIR="$BUNDLE_DIR/rendered"
TOKEN_NAME=alertmanager_webhook_token
CONTAINERS="prometheus alertmanager blackbox-exporter"
TEMPLATES="prometheus.yml alertmanager.yml blackbox.yml"
# monitoring.env의 MONITORING_CONFIG_SHA256이 이 파일들과 CA 인증서 파일의 내용, token 파일의 실제 경로를 나타낸다.
CONFIG_FILES="$TEMPLATES"
# prometheus.yml 템플릿에서 서버 사용량 수집(manager-os) 부분의 앞뒤 줄이다. MONITORING_OS_EXPORTER가 off면 이 사이를 빼고 펼친다.
OS_EXPORTER_BEGIN='  # BEGIN manager-os'
OS_EXPORTER_END='  # END manager-os'
# 시험(tests/monitoring-server.test.sh)에서만 쓰는 값이다. 기다리는 시간을 줄이고 준비 확인 도구를 고른다.
# 운영 문서에는 적지 않고, 운영 서버에서는 설정하지 않는다.
WAIT_SECONDS=${MONITORING_WAIT_SECONDS:-180}
WAIT_INTERVAL=${MONITORING_WAIT_INTERVAL:-5}
READY_TOOLS=${MONITORING_READY_TOOLS-curl wget}

# 묶음에는 templates/가 있다. 저장소의 infrastructure/에는 구성 요소마다 폴더에 템플릿이 있고, token 폴더 기본값이 compose.yml처럼 ./secrets다.
if [ ! -d "$BUNDLE_DIR/templates" ] && [ -f "$BUNDLE_DIR/prometheus/prometheus.yml" ]; then
  LAYOUT=repository
  SECRETS_DEFAULT=./secrets
  CA_FILE="$BUNDLE_DIR/certs/ca-certificates.crt"
  ENV_FILE="$BUNDLE_DIR/.env"
  ENV_LINK=""
else
  LAYOUT=bundle
  SECRETS_DEFAULT=../secrets
  # token처럼 설치 폴더에 두어, 묶음 버전이 바뀌어도 다시 복사하지 않는다. compose 파일의 ../certs/와 같은 곳이다.
  CA_FILE="$INSTALL_DIR/certs/ca-certificates.crt"
  # 설정 파일도 설치 폴더에 하나만 두어 모든 버전이 함께 읽는다(요구사항 0017).
  # 묶음 폴더에는 ../.env 링크를 두어, 운영자가 묶음 폴더에서 실행하는 docker compose도 같은 값을 읽게 한다.
  ENV_FILE="$INSTALL_DIR/.env"
  ENV_LINK="$BUNDLE_DIR/.env"
fi
# 버전 기록 파일이다. 묶음 폴더를 지워도 남도록 설치 폴더에 둔다.
VERSIONS_FILE="$INSTALL_DIR/monitoring-versions"

say() {
  printf '%s\n' "$*"
}

fail() {
  printf '실패: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'EOF'
사용법: sh monitoring-server.sh <작업>

  install   token과 설정 파일(.env)을 준비합니다
  render    설정 값을 검사해 설정을 펼칩니다(docker compose로 직접 띄울 때만 씁니다)
  start     설정 값을 검사하고 모니터링 시스템을 띄웁니다. 지금 버전과 직전 버전 밖의 오래된 버전은 지웁니다
  rollback  직전 버전으로 되돌립니다
  status    떠 있는 버전, 상태, 기본값과 다른 설정 값을 보입니다
EOF
}

# Compose와 같은 값을 보도록, 셸 환경 변수가 있으면 그 값을 쓰고 없으면 .env의 값을 쓴다.
# .env를 셸로 실행하면 값에 든 명령이 실행되므로 실행하지 않고 읽는다. export, 따옴표, 줄 끝 주석은 Compose처럼 다루고,
# 같은 키가 여럿이면 마지막 값을 쓴다. BusyBox, GNU, 로캘과 관계없이 같게 읽도록 바이트로 비교한다(LC_ALL=C).
env_value() {
  if value=$(printenv "$1"); then
    printf '%s' "$value"
    return 0
  fi
  if [ ! -f "$ENV_FILE" ]; then
    return 0
  fi
  raw=$(LC_ALL=C sed -n -E "s/^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*=[[:space:]]*//p" "$ENV_FILE" | LC_ALL=C tail -n 1 | LC_ALL=C tr -d '\r')
  case "$raw" in
    \"*) printf '%s' "$raw" | LC_ALL=C sed 's/^"\([^"]*\)".*$/\1/' ;;
    \'*) printf '%s' "$raw" | LC_ALL=C sed "s/^'\([^']*\)'.*\$/\1/" ;;
    *) printf '%s' "$raw" | LC_ALL=C sed 's/[[:space:]][[:space:]]*#.*$//; s/[[:space:]]*$//' ;;
  esac
}

# .env에서 변수(첫 번째 인자)를 Compose가 운영 명령과 다르게 읽을 수 있는 첫 줄의 번호. 없으면 빈 값이다.
# 줄 앞 공백 뒤가 #인 주석 줄을 빼고, 변수 이름이 낱말로(앞뒤가 이름에 쓰는 글자가 아니게) 들어 있는 줄 가운데
# env_value가 읽는 형식(줄 앞 공백, export, 이름, 공백, =)이 아니거나, 그 형식이어도 값에 이름이 다시 나오는 줄이다.
# Compose가 받는 표기를 하나씩 따라가지 않고 넓게 고른다. 그래서 다른 변수의 값에 이름이 들어 있어도 멈추지만,
# 드물고 멈추는 쪽이 안전하다(요구사항 0028). 주석으로 건너뛰는 줄은 Compose도 주석으로 읽는다.
# 바이트로 비교하도록 LC_ALL=C로 실행하고, NUL이나 잘못된 UTF-8이 있어도 GNU grep이 줄을 숨기지 않게 -a를 준다.
# .env를 셸로 실행하지 않고, 줄 내용은 내보내지 않는다.
env_unread_line() {
  if [ ! -f "$ENV_FILE" ]; then
    return 0
  fi
  LC_ALL=C tr -d '\r' < "$ENV_FILE" \
    | LC_ALL=C sed -E "s/^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*=/=/" \
    | LC_ALL=C grep -a -n -E "(^|[^A-Za-z0-9_])$1([^A-Za-z0-9_]|\$)" \
    | LC_ALL=C grep -a -v -E '^[0-9]+:[[:space:]]*#' \
    | head -n 1 | cut -d: -f1
}

# env_unread_line이 찾은 줄의 안내. 변수 이름, 줄 번호, 고칠 형식에 넣을 값의 이름(폴더, 경로)을 받는다.
env_unread_message() {
  printf '설정 값을 읽지 못했습니다: %s의 %s번째 줄(%s)\n' "$ENV_FILE" "$2" "$1" >&2
  printf '  고치는 방법: 그 줄을 %s=%s 형식으로 고친 뒤 다시 실행해 주세요.\n' "$1" "$3" >&2
  printf '  형식이 맞아 보이면 줄에 보이지 않는 글자가 들어 있을 수 있습니다. 그 줄을 지우고 다시 입력해 주세요.\n' >&2
}

# Compose와 같이 상대 경로는 이 묶음 폴더 기준으로 푼다.
token_file() {
  dir=$(env_value MONITORING_SECRETS_DIR)
  if [ -z "$dir" ]; then
    dir=$SECRETS_DEFAULT
  fi
  case "$dir" in
    /*) ;;
    *) dir="$BUNDLE_DIR/$dir" ;;
  esac
  # 안내에 ../가 남지 않도록, token 폴더가 아직 없어도 상위 폴더가 있으면 실제 경로로 푼다.
  parent=$(dirname "$dir")
  if [ -d "$parent" ]; then
    dir="$(cd "$parent" && pwd -P)/$(basename "$dir")"
  fi
  printf '%s/%s' "$dir" "$TOKEN_NAME"
}

real_path() {
  printf '%s/%s' "$(cd "$(dirname "$1")" && pwd -P)" "$(basename "$1")"
}

# 같은 폴더의 임시 파일에 쓴 뒤 이름을 옮겨, 쓰는 도중의 파일을 컨테이너가 읽지 않게 한다. 값은 출력하지 않는다.
# mv -T는 그 자리가 폴더나 폴더를 가리키는 링크로 바뀌어 있어도 그 안으로 옮기지 않는다(CWE-59).
write_token() {
  tmp=$(umask 077 && mktemp "$1.XXXXXX")
  od -An -tx1 -N32 /dev/urandom | tr -d ' \n' > "$tmp"
  printf '\n' >> "$tmp"
  # 컨테이너 사용자(nobody)가 읽어야 한다. 다른 사용자는 0700인 폴더에 막힌다.
  chmod 0644 "$tmp"
  mv -f -T "$tmp" "$1"
}

running_label() {
  docker inspect -f "{{index .Config.Labels \"$1\"}}" prometheus 2>/dev/null
}

# 멈춘 컨테이너도 있다고 본다. 멈춘 컨테이너도 다시 시작하면 지금 값으로 돈다.
monitoring_exists() {
  docker inspect -f '{{.State.Running}}' prometheus > /dev/null 2>&1
}

monitoring_running() {
  [ "$(docker inspect -f '{{.State.Running}}' prometheus 2>/dev/null)" = true ]
}

compose_version() {
  sed -n 's/^ *image: monitoring-system-prometheus:\(.*\)$/\1/p' "$1" | head -n 1 | tr -d '\r'
}

bundle_version() {
  compose_version "$BUNDLE_DIR/compose.yml"
}

check_docker() {
  docker info > /dev/null 2>&1 \
    || fail "Docker에 연결하지 못했습니다. Docker가 실행 중인지, 이 사용자가 docker 명령을 쓸 수 있는지 확인해 주세요."
}

# 모니터링 시스템과 관리자 서버는 운영자가 미리 만든 monitoring 네트워크에 붙는다(요구사항 0019). 운영 명령은 만들지 않고 확인만 한다.
check_network() {
  docker network inspect monitoring > /dev/null 2>&1 \
    || fail "Docker 네트워크 monitoring이 없습니다. sudo docker network create monitoring 으로 만든 뒤 다시 실행해 주세요. 아무것도 바꾸지 않았습니다."
}

check_checksums() {
  say "묶음 파일의 체크섬을 확인합니다."
  if result=$(cd "$BUNDLE_DIR" && sha256sum -c SHA256SUMS 2>&1); then
    return 0
  fi
  printf '%s\n' "$result" | grep -v ': OK$' >&2
  fail "체크섬이 맞지 않는 파일이 있습니다. 묶음 폴더를 다시 옮겨 주세요."
}

# 묶음 폴더의 .env가 없거나 운영 명령이 만든 ../.env 링크면 0을 돌려준다.
env_link_ok() {
  if [ ! -e "$ENV_LINK" ] && [ ! -L "$ENV_LINK" ]; then
    return 0
  fi
  [ -L "$ENV_LINK" ] && [ "$(readlink "$ENV_LINK")" = ../.env ]
}

# 묶음 폴더의 .env가 운영 명령이 만든 링크가 아니면(편집기가 링크를 파일로 바꿔 저장한 경우 등) 어느 값을 쓰는지 헷갈리지 않게
# 멈추고, 설치 폴더의 .env에 옮겨 적은 뒤 지우라고 안내한다. 운영 명령은 옮기거나 지우지 않는다(요구사항 0021의 S4).
# 링크는 따라가지 않는다. 설치 폴더의 .env가 링크면 다른 곳의 파일을 읽거나 install이 그 파일에 쓰게 된다(CWE-59).
check_env_location() {
  [ "$LAYOUT" = bundle ] || return 0
  if [ -L "$ENV_FILE" ]; then
    fail "설치 폴더의 설정 파일이 링크입니다: $ENV_FILE. 링크를 지우고 실제 파일을 둔 뒤 다시 실행해 주세요."
  fi
  env_link_ok && return 0
  fail "묶음 폴더의 설정 파일은 쓰지 않습니다: $ENV_LINK. 설정은 $ENV_FILE 에 둡니다. 고친 값이 있으면 그 파일에 옮겨 적은 뒤, sudo rm $ENV_LINK 로 지우고 다시 실행해 주세요."
}

# 묶음 폴더에 설치 폴더의 설정 파일을 가리키는 링크를 둔다. 확인을 기다리는 동안 바뀌었을 수 있어 쓰기 직전에 다시 본다.
link_env() {
  [ "$LAYOUT" = bundle ] || return 0
  check_env_location
  [ -L "$ENV_LINK" ] || ln -s ../.env "$ENV_LINK"
}

check_env() {
  check_env_location
  if [ ! -f "$ENV_FILE" ]; then
    # 갱신하며 install로 새 .env를 만들면 바꿔 둔 값이 조용히 기본값으로 돌아간다.
    if monitoring_exists; then
      fail "설정 파일(.env)이 없습니다. 새 버전으로 갱신하는 중이면 install 대신 쓰던 .env를 $ENV_FILE 로 복사해 주세요. 처음 설치하면 sh monitoring-server.sh install을 실행해 주세요."
    fi
    fail "설정 파일(.env)이 없습니다. 먼저 sh monitoring-server.sh install을 실행해 주세요."
  fi
  load_settings
  address=$(env_value MONITORING_APP_ADDRESS)
  monitoring_valid_address "$address" \
    || fail "관리자 서버 주소가 올바르지 않습니다: '$address'. .env의 MONITORING_APP_ADDRESS를 컨테이너 이름:포트 형식(예: manager-api:8080)으로 고쳐 주세요."
  # 펼칠 때 .env나 셸 환경 변수를 다시 읽지 않고 검사한 값을 그대로 쓴다.
  app_address=$address
}

load_settings() {
  [ -f "$BUNDLE_DIR/monitoring-settings.sh" ] \
    || fail "묶음 파일 monitoring-settings.sh가 없습니다. 묶음 폴더를 다시 옮겨 주세요."
  . "$BUNDLE_DIR/monitoring-settings.sh"
}

# .env에서 바꾸는 설정 값을 모두 검사하고, 틀린 값은 한 번에 모두 보인 뒤 아무것도 바꾸지 않고 멈춘다.
# 검사한 값은 export해 펼치기와 Compose(호스트 포트)가 .env를 다시 읽지 않고 그 값을 그대로 쓰게 한다.
# env_value의 .env 읽기가 Compose와 조금이라도 다르면 검사한 값과 띄우는 값이 어긋날 수 있기 때문이다.
check_settings() {
  load_settings
  settings_ok=yes
  for setting in $MONITORING_SETTING_NAMES; do
    if printenv "$setting" > /dev/null; then
      hint="이 값은 셸 환경 변수 $setting에서 왔고, 셸 환경 변수는 .env의 $setting 값보다 먼저 쓰입니다. 셸에서 unset $setting 명령으로 지우거나 값을 고친 뒤 다시 실행해 주세요."
    else
      hint=".env의 $setting 값을 고친 뒤 다시 실행해 주세요."
    fi
    if monitoring_setting_check "$setting" "$(env_value "$setting")" "$hint"; then
      export "$setting=$monitoring_value"
    else
      settings_ok=no
    fi
  done
  for setting in $MONITORING_SWITCH_NAMES; do
    if printenv "$setting" > /dev/null; then
      hint="이 값은 셸 환경 변수 $setting에서 왔고, 셸 환경 변수는 .env의 $setting 값보다 먼저 쓰입니다. 셸에서 unset $setting 명령으로 지우거나 값을 고친 뒤 다시 실행해 주세요."
    else
      hint=".env의 $setting 값을 고친 뒤 다시 실행해 주세요."
    fi
    if monitoring_switch_check "$setting" "$(env_value "$setting")" "$hint"; then
      export "$setting=$monitoring_value"
    else
      settings_ok=no
    fi
  done
  check_ca_setting
  check_secrets_setting || settings_ok=no
  [ "$settings_ok" = yes ] || fail "설정 값이 틀려 아무것도 바꾸지 않았습니다. 떠 있던 모니터링 시스템은 그대로 돕니다."
}

# token 폴더(MONITORING_SECRETS_DIR)를 읽어 export해, Compose가 .env를 다시 읽지 않고 token_file이 검사한 폴더를 쓰게 한다.
# 값이 없다고 본 경우에도 빈 값을 export한다. env_value가 읽지 않는 형식의 줄이 있으면 Compose가 다른 폴더를 쓸 수 있으므로
# 안내를 보이고 1을 돌려준다. 띄우기 밖의 docker compose 명령도 그 줄을 읽으므로 셸 환경 변수가 있어도 멈춘다(요구사항 0028).
check_secrets_setting() {
  unread_line=$(env_unread_line MONITORING_SECRETS_DIR)
  if [ -n "$unread_line" ]; then
    env_unread_message MONITORING_SECRETS_DIR "$unread_line" 폴더
    return 1
  fi
  secrets_value=$(env_value MONITORING_SECRETS_DIR)
  # 저장소에서는 이 값을 compose.env에 작은따옴표로 감싸 Compose에 넘긴다. Compose는 작은따옴표 안에서도 \와 '를 풀고,
  # 줄바꿈은 다른 설정 값의 줄을 만들 수 있어 그런 값은 넘기지 않는다(요구사항 0028의 S14). 값은 안내에 쓰지 않는다.
  if [ "$LAYOUT" = repository ]; then
    case "$secrets_value" in
      *"'"*|*\\*|*"
"*)
        if printenv MONITORING_SECRETS_DIR > /dev/null; then
          secrets_from="셸 환경 변수 MONITORING_SECRETS_DIR"
        else
          secrets_from="$ENV_FILE 의 MONITORING_SECRETS_DIR"
        fi
        printf '설정 값이 올바르지 않습니다: %s (token 폴더)\n' "$secrets_from" >&2
        printf '  문제: 작은따옴표, 백슬래시, 줄바꿈이 있어 저장소에서 띄우는 Compose에 그대로 넘길 수 없습니다.\n' >&2
        printf '  고치는 방법: 이 글자 없이 infrastructure/ 기준 상대 경로로 적은 뒤 다시 실행해 주세요.\n' >&2
        return 1 ;;
    esac
  fi
  export MONITORING_SECRETS_DIR="$secrets_value"
}

# CA 인증서 파일 경로(MONITORING_CA_FILE)를 검사한다. 값이 있으면 CA_FILE을 그 경로로 바꾸고, 비어 있으면 복사본 자리를 그대로 쓴다.
# 검사한 값은 export해 Compose가 볼륨 원본으로 같은 값을 쓰게 한다. 값이 없다고 본 경우에도 빈 값을 export한다.
# env_value가 읽지 않는 형식의 줄이 있으면 운영자가 적은 파일 대신 복사본을 조용히 쓰게 되므로 멈춘다(요구사항 0028).
# 틀리면 settings_ok를 no로 둔다.
check_ca_setting() {
  CA_FROM_SETTING=no
  unread_line=$(env_unread_line MONITORING_CA_FILE)
  if [ -n "$unread_line" ]; then
    env_unread_message MONITORING_CA_FILE "$unread_line" 경로
    settings_ok=no
    return 0
  fi
  if printenv MONITORING_CA_FILE > /dev/null; then
    ca_fix="이 값은 셸 환경 변수 MONITORING_CA_FILE에서 왔고, 셸 환경 변수는 .env의 값보다 먼저 쓰입니다. 셸에서 unset MONITORING_CA_FILE 명령으로 지우거나 값을 고친 뒤 다시 실행해 주세요."
  else
    ca_fix=".env의 MONITORING_CA_FILE 값을 고친 뒤 다시 실행해 주세요."
  fi
  ca_value=$(env_value MONITORING_CA_FILE)
  if [ -z "$ca_value" ]; then
    export MONITORING_CA_FILE=
    return 0
  fi
  if ! monitoring_valid_ca_file "$ca_value"; then
    printf "설정 값이 올바르지 않습니다: MONITORING_CA_FILE='%s' (CA 인증서 파일 경로)\n" "$ca_value" >&2
    printf '  문제: /로 시작하는 절대 경로에 영문자, 숫자, ., _, /, -만 쓸 수 있습니다(예: /etc/ssl/certs/ca-certificates.crt).\n' >&2
    printf '  고치는 방법: %s 값을 지우거나 비워 두면 설치 폴더의 certs/ca-certificates.crt를 씁니다.\n' "$ca_fix" >&2
    settings_ok=no
    return 0
  fi
  export MONITORING_CA_FILE="$ca_value"
  CA_FILE=$ca_value
  CA_FROM_SETTING=yes
}

# 컨테이너(첫 번째 인자)가 받은 환경 변수(두 번째 인자)의 값. 없으면 빈 값이다.
container_env() {
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" 2>/dev/null | sed -n "s/^$2=//p" | head -n 1
}

# 컨테이너가 받은 값을 검사해 쓸 값으로 돌려준다. 변수가 없거나 틀리면 기본값을 돌려준다.
# 틀린 값을 받은 컨테이너는 시작하지 못하므로, 지난 기록은 기본값이나 그 전의 값으로 남아 있다.
running_setting() {
  raw=$(container_env "$1" "$2")
  if monitoring_setting_check "$2" "$raw" "" 2> /dev/null; then
    printf '%s' "$monitoring_value"
  else
    monitoring_setting_spec "$2"
    printf '%s' "$spec_default"
  fi
}

# 컨테이너 안 포트(두 번째 인자)에 묶인 호스트 주소:포트. 멈춘 컨테이너도 읽을 수 있게 HostConfig에서 읽는다.
host_binding() {
  docker inspect -f '{{json .HostConfig.PortBindings}}' "$1" 2>/dev/null \
    | sed -n "s/.*\"$2\/tcp\":\[{\"HostIp\":\"\([^\"]*\)\",\"HostPort\":\"\([0-9]*\)\".*/\1:\2/p"
}

# 떠 있는(멈춘 것도 포함) 모니터링 시스템보다 보관 기간이나 용량을 줄이면 오래된 기록이 지워져 되돌릴 수 없으므로 확인을 받는다.
# 제한이 없던 용량을 처음 정하는 것도 줄임으로 본다(요구사항 0013의 S12). 터미널에서만 묻고, 터미널이 아니면 띄우지 않는다.
confirm_retention() {
  monitoring_exists || return 0
  current_days=$(running_setting prometheus MONITORING_RETENTION_DAYS)
  current_size=$(running_setting prometheus MONITORING_RETENTION_SIZE_GB)
  changes=""
  if [ "$MONITORING_RETENTION_DAYS" -lt "$current_days" ]; then
    changes="보관 기간 ${current_days}일 → ${MONITORING_RETENTION_DAYS}일"
  fi
  if [ -n "$MONITORING_RETENTION_SIZE_GB" ]; then
    if [ -z "$current_size" ]; then
      changes="${changes:+$changes, }보관 용량 제한 없음 → ${MONITORING_RETENTION_SIZE_GB}GB"
    elif [ "$MONITORING_RETENTION_SIZE_GB" -lt "$current_size" ]; then
      changes="${changes:+$changes, }보관 용량 ${current_size}GB → ${MONITORING_RETENTION_SIZE_GB}GB"
    fi
  fi
  [ -n "$changes" ] || return 0
  say "기록 보관 값을 줄입니다: $changes"
  say "띄우면 오래된 기록이 지워지고, 되돌릴 수 없습니다."
  if [ ! -t 0 ]; then
    fail "기록 보관 값을 줄이려면 터미널에서 직접 실행해 y를 입력해 주세요. 모니터링 시스템은 바꾸지 않았습니다."
  fi
  printf '계속할까요? [y/N] '
  read -r answer || answer=""
  case "$answer" in
    y|Y|yes) ;;
    *) say "모니터링 시스템은 바꾸지 않았습니다."; exit 0 ;;
  esac
}

# 컨테이너가 실제로 받은 값에서 기본값과 다른 것만 보인다. 셸 환경 변수가 .env를 덮을 수 있어 .env가 아니라 컨테이너에서 읽는다.
# 틀린 값(운영 명령 없이 띄운 경우)은 기본값으로 바꾸지 않고 그대로 올바르지 않다고 보인다.
show_settings() {
  differs=""
  for setting in $MONITORING_SETTING_NAMES; do
    case "$setting" in
      MONITORING_RETENTION_*) container=prometheus ;;
      MONITORING_PROBE_TIMEOUT_SECONDS) container=blackbox-exporter ;;
      *) container="" ;;
    esac
    if [ -n "$container" ] && ! monitoring_setting_check "$setting" "$(container_env "$container" "$setting")" "" 2> /dev/null; then
      monitoring_setting_spec "$setting"
      differs="${differs:+$differs, }$spec_label '$(container_env "$container" "$setting")'(올바르지 않음)"
      continue
    fi
    case "$setting" in
      MONITORING_RETENTION_*) shown=$(running_setting prometheus "$setting") ;;
      MONITORING_PROBE_TIMEOUT_SECONDS) shown=$(running_setting blackbox-exporter "$setting") ;;
      MONITORING_PROMETHEUS_PORT) shown=$(host_binding prometheus 9090) ;;
      MONITORING_ALERTMANAGER_PORT) shown=$(host_binding alertmanager 9093) ;;
      MONITORING_BLACKBOX_PORT) shown=$(host_binding blackbox-exporter 9115) ;;
    esac
    monitoring_setting_spec "$setting"
    if [ -n "$shown" ] && [ "${shown##*:}" != "$spec_default" ]; then
      differs="${differs:+$differs, }$spec_label $shown$spec_unit"
    fi
  done
  # 켜고 끄는 값은 prometheus 컨테이너가 받은 값으로 본다. 변수가 없으면 기본값(켜짐)으로 돈 컨테이너다.
  for setting in $MONITORING_SWITCH_NAMES; do
    monitoring_switch_spec "$setting"
    raw=$(container_env prometheus "$setting")
    case "$raw" in
      ''|"$spec_default") ;;
      off) differs="${differs:+$differs, }$spec_label 꺼짐" ;;
      *) differs="${differs:+$differs, }$spec_label '$raw'(올바르지 않음)" ;;
    esac
  done
  if [ -n "$differs" ]; then
    say "기본값과 다른 설정 값: $differs"
  else
    say "설정 값: 모두 기본값입니다."
  fi
}

# Docker는 token 파일이 없으면 그 자리에 빈 폴더를 만들고 컨테이너를 띄운다. 그래서 띄우기 전에 확인한다.
check_token() {
  file=$(token_file)
  if [ -d "$file" ]; then
    fail "token 자리에 폴더가 있습니다: $file. 그 폴더를 지운 뒤 sh monitoring-server.sh install로 token을 만들어 주세요."
  fi
  if [ ! -e "$file" ]; then
    # 모니터링 시스템이 이미 있으면 관리자 서버가 그 모니터링 시스템의 token을 쓰고 있다. 새 token을 만들면 장애 알림이 모두 거부된다.
    if monitoring_exists; then
      fail "token 파일이 없습니다: $file. 쓰던 token 파일을 이 위치로 옮기거나, .env의 MONITORING_SECRETS_DIR를 그 파일이 있는 폴더로 고쳐 주세요. 새로 만들면 관리자 서버의 token과 어긋납니다."
    fi
    fail "token 파일이 없습니다: $file. sh monitoring-server.sh install로 만들어 주세요."
  fi
  if [ ! -s "$file" ]; then
    fail "token 파일이 비어 있습니다: $file. 파일을 지운 뒤 sh monitoring-server.sh install로 다시 만들어 주세요."
  fi
  if ! others_can_read "$file"; then
    fail "컨테이너가 token 파일을 읽을 수 없습니다: $file. chmod 0644로 권한을 바꿔 주세요."
  fi
}

# 컨테이너 사용자(nobody)는 다른 사용자의 읽기 권한으로 읽는다. 권한 글자(-rw-r--r--)의 8번째 글자가 그 권한이다.
# find가 없는 서버(예: Amazon Linux 2023 컨테이너 이미지)도 있어 ls로 본다. 링크면 Docker가 붙이는 실제 파일을 본다.
others_can_read() {
  [ "$(ls -ldL "$1" | cut -c8)" = r ]
}

template_file() {
  if [ "$LAYOUT" = bundle ]; then
    printf '%s/templates/%s' "$BUNDLE_DIR" "$1"
  else
    printf '%s/%s/%s' "$BUNDLE_DIR" "${1%.yml}" "$1"
  fi
}

check_templates() {
  for name in $TEMPLATES; do
    [ -f "$(template_file "$name")" ] \
      || fail "설정 템플릿이 없습니다: $(template_file "$name"). 묶음 폴더를 다시 옮겨 주세요."
  done
  # 서버 사용량 수집을 끄면 펼칠 때 표시 줄 사이를 뺀다. 표시 줄이 한 쌍이 아니거나 순서가 바뀌었으면
  # 빼다가 다른 설정까지 지우므로 미리 멈춘다(요구사항 0022). 줄 번호가 여럿이면 줄바꿈이 끼어 숫자 검사에서 걸린다.
  if [ "$MONITORING_OS_EXPORTER" = off ]; then
    template=$(template_file prometheus.yml)
    begin=$(tr -d '\r' < "$template" | grep -n "^$OS_EXPORTER_BEGIN\$" | cut -d: -f1)
    end=$(tr -d '\r' < "$template" | grep -n "^$OS_EXPORTER_END\$" | cut -d: -f1)
    case "$begin:$end" in
      :*|*:|*[!0123456789:]*) markers_ok=no ;;
      *) if [ "$begin" -lt "$end" ]; then markers_ok=yes; else markers_ok=no; fi ;;
    esac
    [ "$markers_ok" = yes ] \
      || fail "설정 템플릿에서 서버 사용량 수집 부분을 찾지 못했습니다: $template. 묶음 폴더를 다시 옮겨 주세요. 아무것도 바꾸지 않았습니다."
  fi
}

# Blackbox Exporter의 HTTPS 확인이 쓸 CA 인증서 파일을 확인한다. check_ca_setting 다음에 부른다.
# 이 명령은 OS의 파일을 옮기거나 고치지 않는다. 내용은 OS와 운영자가 관리하므로 검사하지 않는다.
# 아래 검사와 others_can_read는 링크를 따라가므로, OS의 파일이 링크여도 Docker가 붙일 실제 파일을 본다.
# 파일이 없는데 Compose로 띄우면 Docker가 그 자리에 빈 폴더를 만들고, HTTPS 주소 확인이 모두 실패해 장애 알림이 한꺼번에 갈 수 있다.
check_ca() {
  os_paths="Ubuntu, Debian, Amazon Linux는 /etc/ssl/certs/ca-certificates.crt, RHEL 계열은 /etc/pki/tls/certs/ca-bundle.crt"
  # 운영자가 적은 경로는 OS의 파일이므로, 그 파일이나 폴더를 지우거나 권한을 바꾸라고 하지 않고 .env의 값을 고치게 한다.
  if [ "$CA_FROM_SETTING" = yes ]; then
    ca_problem=""
    if [ -d "$CA_FILE" ]; then
      ca_problem="파일이 아니라 폴더입니다"
    elif [ ! -e "$CA_FILE" ]; then
      ca_problem="파일이 없습니다"
    elif [ ! -s "$CA_FILE" ]; then
      ca_problem="파일이 비어 있습니다"
    elif ! others_can_read "$CA_FILE"; then
      ca_problem="컨테이너가 읽을 수 없습니다(다른 사용자의 읽기 권한이 없음)"
    fi
    if [ -n "$ca_problem" ]; then
      # 링크면 운영자가 ls로 링크만 보고 헷갈리지 않게 가리키는 파일도 보인다. 풀지 못하면 보이지 않는다.
      if [ -L "$CA_FILE" ] && ca_real=$(readlink -f "$CA_FILE" 2> /dev/null) && [ -n "$ca_real" ]; then
        ca_problem="$ca_problem, 링크가 가리키는 파일: $ca_real"
      fi
      fail "MONITORING_CA_FILE의 CA 인증서 파일을 쓸 수 없습니다: $CA_FILE ($ca_problem). 아무것도 바꾸지 않았습니다. 모니터링 서버 OS의 CA 인증서 파일 경로를 적어 주세요($os_paths). $ca_fix"
    fi
    return 0
  fi
  ca_hint=".env의 MONITORING_CA_FILE에 모니터링 서버 OS의 CA 인증서 파일 경로를 적어 주세요($os_paths). 자세한 방법은 docs/OPERATIONS.md에 있습니다."
  if [ -d "$CA_FILE" ]; then
    fail "CA 인증서 파일 자리에 폴더가 있습니다: $CA_FILE. 그 폴더를 지운 뒤 $ca_hint"
  fi
  if [ ! -e "$CA_FILE" ]; then
    fail "CA 인증서 파일이 없습니다: $CA_FILE. 이 파일이 없으면 HTTPS 주소 확인이 모두 실패합니다. 아무것도 바꾸지 않았습니다. $ca_hint"
  fi
  if [ ! -s "$CA_FILE" ]; then
    fail "CA 인증서 파일이 비어 있습니다: $CA_FILE. $ca_hint"
  fi
  if ! others_can_read "$CA_FILE"; then
    fail "컨테이너가 CA 인증서 파일을 읽을 수 없습니다: $CA_FILE. sudo chmod 0644 $CA_FILE 로 권한을 바꿔 주세요."
  fi
}

# 운영자 권한으로 쓰므로, rendered/나 그 안의 파일이 다른 곳을 가리키는 링크면 쓰지 않는다.
# 펼친 설정 없이 Compose로 띄우면 Docker가 파일 자리에 빈 폴더를 만들 수 있다. 그 폴더는 지우지 않고 지우는 방법을 보인다.
check_render_dir() {
  if [ -L "$RENDER_DIR" ]; then
    fail "펼친 설정 폴더가 심볼릭 링크입니다: $RENDER_DIR. 링크를 지운 뒤 다시 실행해 주세요."
  fi
  if [ -e "$RENDER_DIR" ] && [ ! -d "$RENDER_DIR" ]; then
    fail "펼친 설정 폴더 자리에 파일이 있습니다: $RENDER_DIR. 그 파일을 지운 뒤 다시 실행해 주세요."
  fi
  names="$CONFIG_FILES monitoring.env"
  if [ "$LAYOUT" = repository ]; then
    names="$names compose.env"
  fi
  for name in $names; do
    target="$RENDER_DIR/$name"
    if [ -L "$target" ]; then
      fail "펼친 설정 파일이 심볼릭 링크입니다: $target. 링크를 지운 뒤 다시 실행해 주세요."
    fi
    if [ -d "$target" ]; then
      fail "펼친 설정 파일 자리에 폴더가 있습니다: $target. sudo rmdir $target 로 지운 뒤 다시 실행해 주세요."
    fi
  done
}

# 펼치기 전의 검사다. 아무것도 쓰지 않으므로, 실패해도 이전에 펼친 설정과 떠 있는 모니터링 시스템이 그대로다.
check_render() {
  check_env
  check_settings
  check_token
  check_templates
  check_ca
  check_render_dir
}

# check_render를 통과한 값으로 템플릿을 펼쳐 rendered/에 쓴다. 같은 폴더 안의 임시 폴더(stage)에 모두 쓴 뒤 이름만 바꿔 옮긴다.
# 펼치는 글자는 검사를 통과한 주소와 monitoring_setting_text의 결과뿐이라 sed와 YAML이 특별하게 읽는 글자가 없다. 값은 eval하지 않는다.
# config_hash는 새로 펼친 설정, CA 인증서 파일, token 파일의 실제 경로의 해시이고, previous_hash는 지금 rendered/에 있는 설정과
# 지난번에 펼칠 때의 CA 인증서 파일과 token 파일 경로의 해시다. 두 파일은 옮기지 않으므로 지난번 값은 monitoring.env의
# MONITORING_CA_SHA256, MONITORING_TOKEN_PATH_SHA256으로 안다. Compose는 token 파일의 경로만 바뀌면 Alertmanager를 다시 만들지
# 않으므로 경로도 넣는다. Docker는 링크를 따라간 파일을 붙이므로 실제 경로를 쓴다(요구사항 0028의 S10, S11).
# 경로 글자는 monitoring.env에 쓰지 않는다. Compose가 env_file의 값을 다시 풀어 쓰고, token 폴더 값은 글자를 제한하지 않기 때문이다.
write_rendered() {
  # 확인을 기다리는 동안 링크로 바뀌었을 수 있어 쓰기 직전에 다시 본다.
  check_render_dir
  if [ ! -d "$RENDER_DIR" ]; then
    mkdir "$RENDER_DIR"
  fi
  # 컨테이너 사용자(nobody)가 읽어야 하고, 다른 사용자가 고치면 안 된다.
  chmod 0755 "$RENDER_DIR"
  stage=$(mktemp -d "$RENDER_DIR/.render.XXXXXX")
  trap 'rm -rf "$stage"' EXIT
  script="s|@MONITORING_APP_ADDRESS@|$app_address|g"
  for setting in $MONITORING_SETTING_NAMES; do
    script="$script
s|@$setting@|$(monitoring_setting_text "$setting" "$(printenv "$setting")")|g"
  done
  # Windows에서 받은 저장소의 템플릿은 줄 끝이 CRLF일 수 있다. 어디서 펼쳐도 같은 파일이 되게 CR을 지운다.
  for name in $TEMPLATES; do
    section=""
    if [ "$name" = prometheus.yml ] && [ "$MONITORING_OS_EXPORTER" = off ]; then
      section="/^$OS_EXPORTER_BEGIN\$/,/^$OS_EXPORTER_END\$/d"
    fi
    tr -d '\r' < "$(template_file "$name")" | sed "$section" | sed "$script" > "$stage/$name"
    if grep -q '@[A-Z][A-Z_]*@' "$stage/$name"; then
      fail "$(template_file "$name")에 채우지 못한 값이 있습니다. 묶음 폴더를 다시 옮겨 주세요. 아무것도 바꾸지 않았습니다."
    fi
  done
  ca_hash=$(sha256sum < "$CA_FILE" | cut -d ' ' -f 1)
  token_path=$(token_file)
  token_real=$(readlink -f "$token_path" 2> /dev/null) && [ -n "$token_real" ] || token_real=$token_path
  token_path_hash=$(printf '%s' "$token_real" | sha256sum | cut -d ' ' -f 1)
  config_hash=$( { cd "$stage" && cat $CONFIG_FILES; printf '%s\n%s\n' "$ca_hash" "$token_path_hash"; } | sha256sum | cut -d ' ' -f 1)
  previous_ca_hash=$(sed -n 's/^MONITORING_CA_SHA256=//p' "$RENDER_DIR/monitoring.env" 2>/dev/null || true)
  previous_token_path_hash=$(sed -n 's/^MONITORING_TOKEN_PATH_SHA256=//p' "$RENDER_DIR/monitoring.env" 2>/dev/null || true)
  previous_hash=$( { cd "$RENDER_DIR" && cat $CONFIG_FILES 2>/dev/null; printf '%s\n%s\n' "$previous_ca_hash" "$previous_token_path_hash"; } \
    | sha256sum | cut -d ' ' -f 1)
  # compose 파일이 env_file로 세 컨테이너에 넘긴다. 프로그램은 읽지 않고, status와 보관 값 줄임 확인이 떠 있는 값을 여기서 읽는다.
  # MONITORING_CONFIG_SHA256이 바뀌면 운영 명령 없이 docker compose up -d로 띄워도 Compose가 컨테이너를 다시 만든다.
  {
    printf '# sh monitoring-server.sh render가 .env로 만든 파일입니다. 펼칠 때마다 다시 만들므로, 바꿀 값은 .env에 적어 주세요.\n'
    for setting in MONITORING_RETENTION_DAYS MONITORING_RETENTION_SIZE_GB MONITORING_PROBE_TIMEOUT_SECONDS MONITORING_OS_EXPORTER; do
      printf '%s=%s\n' "$setting" "$(printenv "$setting")"
    done
    printf 'MONITORING_CA_SHA256=%s\n' "$ca_hash"
    printf 'MONITORING_TOKEN_PATH_SHA256=%s\n' "$token_path_hash"
    printf 'MONITORING_CONFIG_SHA256=%s\n' "$config_hash"
  } > "$stage/monitoring.env"
  rendered_files="$CONFIG_FILES monitoring.env"
  # 저장소에서는 render가 끝나면 사라지는 컨테이너 안에서 돌 수 있어(monitoring-dev.bat start), export한 값이 Compose에 닿지 않는다.
  # 그래서 Compose가 compose 파일에서 푸는 값을 검사한 값 그대로 남기고, Compose는 .env 대신 이 파일을 --env-file로 읽는다.
  # Compose는 작은따옴표 값을 다시 풀지 않는다. 풀리는 글자가 든 token 폴더 값은 check_secrets_setting이 막았다(요구사항 0028의 S13~S15).
  if [ "$LAYOUT" = repository ]; then
    {
      printf '# sh monitoring-server.sh render가 검사한 값입니다. 저장소에서 띄울 때 docker compose --env-file로 줍니다.\n'
      for setting in MONITORING_SECRETS_DIR MONITORING_CA_FILE MONITORING_PROMETHEUS_PORT MONITORING_ALERTMANAGER_PORT MONITORING_BLACKBOX_PORT; do
        printf "%s='%s'\n" "$setting" "$(printenv "$setting")"
      done
    } > "$stage/compose.env"
    rendered_files="$rendered_files compose.env"
  fi
  for name in $rendered_files; do
    chmod 0644 "$stage/$name"
  done
  # 이전과 내용이 달라졌으면 render_changed=yes다. 파일 단위로 붙인 컨테이너는 이름을 바꿔 옮긴 새 파일을
  # 다시 시작할 때까지 보지 못하기 때문이다.
  # mv -T는 검사 뒤 그 자리가 폴더나 폴더를 가리키는 링크로 바뀌어도 그 안으로 옮기지 않는다(CWE-59).
  for name in $rendered_files; do
    mv -f -T "$stage/$name" "$RENDER_DIR/$name"
  done
  rmdir "$stage"
  trap - EXIT
  render_changed=no
  if [ "$config_hash" != "$previous_hash" ]; then
    render_changed=yes
  fi
}

load_images() {
  version=$(bundle_version)
  for name in $CONTAINERS; do
    if ! docker image inspect "monitoring-system-$name:$version" > /dev/null 2>&1; then
      say "모니터링 시스템 이미지를 불러옵니다(버전 $version)."
      docker load -i "$BUNDLE_DIR/monitoring-system-images.tar"
      return 0
    fi
  done
}

# 모니터링 시스템 이미지에는 셸과 wget이 없어 컨테이너 healthcheck를 둘 수 없다. 그래서 모니터링 서버에서 호스트 포트로 준비 확인 주소를 부른다.
# 컨테이너(첫 번째 인자)의 컨테이너 안 포트와 준비 확인 경로를 ready_port, ready_path에 둔다.
ready_target() {
  case "$1" in
    prometheus) ready_port=9090 ready_path=/-/ready ;;
    alertmanager) ready_port=9093 ready_path=/-/ready ;;
    blackbox-exporter) ready_port=9115 ready_path=/-/healthy ;;
  esac
}

# 준비 확인 주소를 부를 도구. 없으면 빈 값이다.
ready_tool() {
  for tool in $READY_TOOLS; do
    case "$tool" in
      curl|wget) ;;
      *) continue ;;
    esac
    if command -v "$tool" > /dev/null 2>&1; then
      printf '%s' "$tool"
      return 0
    fi
  done
  return 0
}

# ready_tool로 고른 도구(ready_with)로 컨테이너의 준비 확인 주소를 한 번 부른다. 바꾼 호스트 포트도 실제로 묶인 값에서 읽는다.
# GNU와 BusyBox 구현이 함께 받는 옵션만 쓴다. 셸에 프록시가 설정돼 있어도 127.0.0.1은 프록시를 거치지 않게 한다.
is_ready() {
  ready_target "$1"
  binding=$(host_binding "$1" "$ready_port")
  [ -n "$binding" ] || return 1
  case "$ready_with" in
    curl) NO_PROXY=127.0.0.1 no_proxy=127.0.0.1 curl -fsS -m 5 -o /dev/null "http://$binding$ready_path" > /dev/null 2>&1 ;;
    wget) NO_PROXY=127.0.0.1 no_proxy=127.0.0.1 wget -q -T 5 -O /dev/null "http://$binding$ready_path" > /dev/null 2>&1 ;;
    *) return 1 ;;
  esac
}

not_ready() {
  {
    say "실패: 정상 상태가 되지 않았습니다:$1. 컨테이너는 그대로 두었습니다. 다음 명령으로 로그를 확인해 주세요."
    for name in $1; do
      say "  docker logs $name"
    done
  } >&2
  exit 1
}

# 띄우기 전에 본다. 띄운 뒤에 없다는 것을 알면 정상인지 모르는 모니터링 시스템이 남는다.
check_ready_tool() {
  ready_with=$(ready_tool)
  [ -n "$ready_with" ] \
    || fail "모니터링 서버에 curl과 wget이 없어 모니터링 시스템이 정상인지 확인할 수 없습니다. curl을 설치한 뒤 다시 실행해 주세요. 아무것도 바꾸지 않았습니다."
}

wait_ready() {
  say "정상 상태가 될 때까지 기다립니다(최대 ${WAIT_SECONDS}초): $*"
  waited=0
  while :; do
    unready=""
    for name in "$@"; do
      is_ready "$name" || unready="$unready $name"
    done
    if [ -z "$unready" ]; then
      return 0
    fi
    if [ "$waited" -ge "$WAIT_SECONDS" ]; then
      break
    fi
    sleep "$WAIT_INTERVAL"
    waited=$((waited + WAIT_INTERVAL))
  done
  not_ready "$unready"
}

# Docker가 컨테이너를 띄우지 못하면(예: 다른 프로그램이 그 호스트 포트를 씀) Compose의 오류 뒤에 정상이 아닌 컨테이너와 고치는 방법을 보인다.
# 모니터링 서버에 포트를 보는 도구가 있다고 보장할 수 없어, 띄우기 전에 포트가 쓰이는지 미리 보지 않는다(요구사항 0013의 S13).
compose_up() {
  if (cd "$BUNDLE_DIR" && docker compose up -d "$@"); then
    return 0
  fi
  {
    say "실패: Docker가 모니터링 시스템을 띄우지 못했습니다. 위 Docker 오류를 확인해 주세요."
    say "오류에 port is already allocated나 address already in use가 있으면 다른 프로그램이 그 포트를 씁니다. .env의 MONITORING_PROMETHEUS_PORT, MONITORING_ALERTMANAGER_PORT, MONITORING_BLACKBOX_PORT 가운데 그 포트를 빈 포트로 바꿔 주세요."
    for name in $CONTAINERS; do
      state=$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null) || state="없음"
      if [ "$state" != running ]; then
        say "  $name: $state. 로그: docker logs $name"
      fi
    done
  } >&2
  exit 1
}

# 재시작 정책(unless-stopped)은 Docker가 켜져 있어야 동작한다. docker.socket만 켜져 있으면 서버가 켜질 때 Docker가 뜨지 않는다.
check_autostart() {
  if ! command -v systemctl > /dev/null 2>&1; then
    say "경고: 서버가 켜질 때 Docker도 켜지는지 확인하지 못했습니다(systemctl 없음). Docker가 서버와 함께 켜지도록 설정해 주세요."
    return 0
  fi
  state=$(systemctl is-enabled docker.service 2>/dev/null) || true
  if [ "$state" != enabled ]; then
    say "경고: 서버가 켜질 때 Docker가 자동으로 켜지지 않습니다. 이대로면 서버를 재시작한 뒤 모니터링 시스템이 뜨지 않습니다. sudo systemctl enable docker를 실행해 주세요."
  fi
}

# --- 버전 기록과 오래된 버전 정리(요구사항 0023) ---

# Docker 태그 형식(128자 이하)이면 0이다. 경로 구분자, 공백, 셸이 특별하게 읽는 글자가 없어 경로와 인자에 그대로 쓸 수 있다.
# 글자 범위(a-z)는 locale에 따라 다른 글자를 포함할 수 있어 글자를 모두 적는다.
valid_version() {
  case "$1" in
    ''|[!abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_]*) return 1 ;;
    *[!abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-]*) return 1 ;;
  esac
  [ ${#1} -le 128 ]
}

# 버전 기록 파일의 값(첫 번째 인자: current나 previous). 파일이 없거나 링크이거나 값의 형식이 틀리면 빈 값이다.
recorded_version() {
  if [ ! -f "$VERSIONS_FILE" ] || [ -L "$VERSIONS_FILE" ]; then
    return 0
  fi
  recorded=$(sed -n "s/^$1=//p" "$VERSIONS_FILE" | tail -n 1 | tr -d '\r')
  if valid_version "$recorded"; then
    printf '%s' "$recorded"
  fi
}

# 버전(첫 번째 인자)으로 바꾸기 전에 마지막으로 start가 성공한 버전. 기록의 지금 버전이 그 버전과 다르면 기록의 지금 버전이고,
# 같으면 기록의 직전 버전이다. 띄우기에 실패한 버전은 기록되지 않으므로 직전 버전이 되지 않는다(요구사항 0023의 S5, S10).
previous_of() {
  recorded_current=$(recorded_version current)
  if [ -n "$recorded_current" ] && [ "$recorded_current" != "$1" ]; then
    printf '%s' "$recorded_current"
  else
    recorded_version previous
  fi
}

# 떠 있는(멈춘 것도 포함) 모니터링 시스템의 버전. 없거나 형식이 틀리면 빈 값이다.
running_version() {
  monitoring_exists || return 0
  label=$(running_label org.opencontainers.image.version) || return 0
  if valid_version "$label"; then
    printf '%s' "$label"
  fi
}

# 설치 폴더 바로 아래의 묶음 폴더(첫 번째 인자)의 버전. 링크, 버전 형식이 아닌 이름, compose.yml이 없거나 그 버전이 이름과 다른 폴더는
# 묶음으로 보지 않고 1을 돌려준다. 정리와 rollback은 이 검사를 통과한 폴더만 다룬다.
bundle_dir_version() {
  if [ ! -d "$1" ] || [ -L "$1" ] || [ ! -f "$1/compose.yml" ] || [ -L "$1/compose.yml" ]; then
    return 1
  fi
  dir_version=${1##*/}
  dir_version=${dir_version#monitoring-system-}
  valid_version "$dir_version" && [ "$(compose_version "$1/compose.yml")" = "$dir_version" ] || return 1
  printf '%s' "$dir_version"
}

# 같은 폴더의 임시 파일에 쓴 뒤 이름을 바꾼다. mv -T는 검사 뒤 그 자리가 폴더나 폴더를 가리키는 링크로 바뀌어도 그 안으로 옮기지 않는다.
# 모니터링 시스템은 이미 떠 있으므로 쓰지 못해도 실패로 끝내지 않고 경고한다.
write_versions() {
  if [ -L "$VERSIONS_FILE" ] || { [ -e "$VERSIONS_FILE" ] && [ ! -f "$VERSIONS_FILE" ]; }; then
    say "경고: 버전 기록 파일 자리에 링크나 폴더가 있어 기록하지 않았습니다: $VERSIONS_FILE. 그것을 지운 뒤 다시 띄우면 기록합니다. 기록이 없으면 rollback으로 되돌릴 수 없습니다."
    return 0
  fi
  if versions_tmp=$(mktemp "$VERSIONS_FILE.XXXXXX") \
    && printf '# sh monitoring-server.sh start가 쓰는 파일입니다. 고치지 마세요.\ncurrent=%s\nprevious=%s\n' "$1" "$2" > "$versions_tmp" \
    && chmod 0644 "$versions_tmp" && mv -f -T "$versions_tmp" "$VERSIONS_FILE"; then
    return 0
  fi
  rm -f "$versions_tmp"
  say "경고: 버전 기록 파일을 쓰지 못했습니다: $VERSIONS_FILE. 기록이 없으면 rollback으로 되돌릴 수 없습니다."
}

# 지금 폴더 안의 것을 지운다. 첫 번째 인자로 준 이름은 남긴다. 안쪽 폴더는 들어간 뒤 실제 위치가 들어가려던 위치와 같을 때만 지우고,
# 링크는 링크만 지운다. 지우지 못하면 그 이유를 표준 오류에 쓰고 1을 돌려준다.
clear_dir() {
  for entry in * .[!.]* ..?*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    [ "$entry" != "${1-}" ] || continue
    if [ -d "$entry" ] && [ ! -L "$entry" ]; then
      (
        expected="$(pwd -P)/$entry"
        cd -P -- "$entry" || exit 1
        if [ "$(pwd -P)" != "$expected" ]; then
          printf '실제 위치가 달라 지우지 않았습니다: %s\n' "$expected" >&2
          exit 1
        fi
        clear_dir
      ) || return 1
      rmdir -- "$entry" || return 1
    else
      rm -f -- "$entry" || return 1
    fi
  done
}

# 폴더(첫 번째 인자, 설치 폴더 아래의 실제 경로)를 지운다. rm -rf를 쓰지 않고 폴더마다 들어가 실제 위치를 확인한 뒤 그 안에서 이름으로만 지운다.
# 셸의 지금 폴더는 들어간 뒤 경로가 링크로 바뀌어도 그대로라 확인과 지우기 사이에 틈이 없고(CWE-367), 링크를 따라가지 않는다(CWE-59).
# compose.yml은 마지막에 지운다. 중간에 실패해도 남은 폴더를 다음 정리 때 묶음으로 알아보고 다시 지우게 하기 위해서다.
remove_bundle_dir() (
  cd -P -- "$1" || exit 1
  if [ "$(pwd -P)" != "$1" ]; then
    printf '실제 위치가 달라 지우지 않았습니다: %s\n' "$1" >&2
    exit 1
  fi
  clear_dir compose.yml || exit 1
  rm -f -- compose.yml && cd .. && rmdir -- "${1##*/}"
)

# 지금 버전(첫 번째 인자)과 직전 버전(두 번째 인자) 밖의 묶음 폴더와 모니터링 시스템 이미지를 지운다.
# 지우지 못한 것은 경고만 하고, 다음에 정리할 때 다시 지운다.
clean_old_versions() {
  cleaned=""
  not_cleaned=""
  # 묶음 폴더를 지우지 못한 버전은 이미지를 지워도 지웠다고 보이지 않는다.
  dir_failed=""
  for candidate in "${INSTALL_DIR%/}"/monitoring-system-*; do
    old=$(bundle_dir_version "$candidate") || continue
    if [ "$old" = "$1" ] || [ "$old" = "$2" ]; then
      continue
    fi
    if reason=$(remove_bundle_dir "$candidate" 2>&1 > /dev/null); then
      cleaned="${cleaned:+$cleaned }$old"
    else
      dir_failed="$dir_failed $old "
      not_cleaned="${not_cleaned:+$not_cleaned, }$candidate($(printf '%s' "$reason" | head -n 1))"
    fi
  done
  images=$(docker image ls --format '{{.Repository}}:{{.Tag}}' 2>/dev/null) || images=""
  while IFS= read -r image; do
    case "$image" in
      monitoring-system-prometheus:*|monitoring-system-alertmanager:*|monitoring-system-blackbox-exporter:*) ;;
      *) continue ;;
    esac
    old=${image#*:}
    if ! valid_version "$old" || [ "$old" = "$1" ] || [ "$old" = "$2" ]; then
      continue
    fi
    if reason=$(docker image rm "$image" 2>&1 > /dev/null); then
      case " $cleaned $dir_failed " in *" $old "*) ;; *) cleaned="${cleaned:+$cleaned }$old" ;; esac
    else
      not_cleaned="${not_cleaned:+$not_cleaned, }$image($(printf '%s' "$reason" | head -n 1))"
    fi
  done <<EOF
$images
EOF
  if [ -n "$cleaned" ]; then
    say "지금 버전($1)과 직전 버전($2)만 남기고 오래된 버전을 지웠습니다: $cleaned"
  fi
  if [ -n "$not_cleaned" ]; then
    say "경고: 오래된 버전 가운데 지우지 못했습니다: $not_cleaned. 다음에 띄울 때 다시 지웁니다."
  fi
}

# start가 성공한 뒤에 부른다. 첫 번째 인자는 띄우기 전에 떠 있던 버전이다.
# 직전 버전은 기록이 있으면 previous_of로 정한다. 떠 있던 버전은 띄우기에 실패한 버전일 수 있어 기록이 없을 때만 쓴다.
# 직전 버전을 모르면 어느 버전을 남길지 알 수 없으므로 지우지 않는다(요구사항 0023의 S6).
record_and_clean() {
  [ "$LAYOUT" = bundle ] || return 0
  now_version=$(bundle_version)
  if ! valid_version "$now_version"; then
    say "경고: 이 묶음의 버전을 읽지 못해 버전을 기록하거나 오래된 버전을 지우지 않았습니다. 묶음 폴더를 다시 옮겨 주세요."
    return 0
  fi
  if [ -z "$(recorded_version current)" ] && [ -n "$1" ] && [ "$1" != "$now_version" ]; then
    before_now=$1
  else
    before_now=$(previous_of "$now_version")
  fi
  write_versions "$now_version" "$before_now"
  if [ -n "$before_now" ]; then
    clean_old_versions "$now_version" "$before_now"
    return 0
  fi
  others=""
  for candidate in "${INSTALL_DIR%/}"/monitoring-system-*; do
    old=$(bundle_dir_version "$candidate") || continue
    [ "$old" = "$now_version" ] || others="${others:+$others }$old"
  done
  if [ -n "$others" ]; then
    say "직전 버전을 알 수 없어 오래된 버전을 지우지 않았습니다: $others. 필요 없으면 docs/OPERATIONS.md의 새 버전으로 갱신대로 지워 주세요. 다음에 새 버전을 띄우면 자동으로 지웁니다."
  fi
}

# 없는 것만 만들고, 있는 token과 .env는 그대로 둔다.
cmd_install() {
  check_docker
  load_settings
  check_env_location
  check_secrets_setting || fail "설정 값을 읽지 못해 아무것도 만들지 않았습니다."
  file=$(token_file)

  address=""
  if [ -f "$ENV_FILE" ]; then
    say "설정 파일(.env)이 이미 있습니다. 그대로 씁니다."
  else
    printf '관리자 서버 주소(컨테이너 이름:포트, 예: manager-api:8080): '
    read -r address || address=""
    monitoring_valid_address "$address" \
      || fail "관리자 서버 주소가 올바르지 않습니다: '$address'. 컨테이너 이름:포트 형식(예: manager-api:8080)으로 다시 실행해 주세요."
  fi

  if [ -e "$file" ]; then
    say "token 파일이 이미 있습니다. 그대로 씁니다: $(real_path "$file")"
  else
    dir=$(dirname "$file")
    # 이미 있는 폴더는 다른 파일도 있을 수 있어 권한을 바꾸지 않는다.
    if [ ! -d "$dir" ]; then
      mkdir -p "$dir"
      chmod 0700 "$dir"
    fi
    write_token "$file"
    say "token 파일을 만들었습니다: $(real_path "$file")"
  fi

  if [ -n "$address" ]; then
    # 같은 폴더에 쓴 뒤 이름을 바꾼다. 주소를 묻는 사이 그 자리에 링크나 폴더가 생겨도 링크를 따라 쓰거나 그 안으로 옮기지 않는다(mv -T).
    tmp=$(mktemp "$ENV_FILE.XXXXXX")
    sed "s/^MONITORING_APP_ADDRESS=.*/MONITORING_APP_ADDRESS=$address/" "$BUNDLE_DIR/.env.example" > "$tmp"
    chmod 0644 "$tmp"
    mv -f -T "$tmp" "$ENV_FILE"
    say "설정 파일을 만들었습니다: $ENV_FILE"
  fi
  link_env
  say "필요하면 $ENV_FILE 의 값을 고친 뒤 sh monitoring-server.sh start를 실행해 주세요."
}

cmd_render() {
  if [ "$LAYOUT" = bundle ]; then
    check_checksums
  fi
  check_render
  # 떠 있는 컨테이너는 다시 시작할 때 새로 펼친 파일을 읽을 수 있다. 그래서 보관 값을 줄이면 띄우기처럼 확인을 묻는다.
  # Docker가 없는 곳(빌드 PC의 검사용 컨테이너)에서는 떠 있는 모니터링 시스템이 없다고 본다. Docker가 있는데 닿지 못하면 알린다.
  if command -v docker > /dev/null 2>&1 && ! docker info > /dev/null 2>&1; then
    say "경고: Docker에 연결하지 못해 기록 보관 값을 줄였는지 확인하지 못했습니다. 줄였다면 띄우기 전에 .env를 확인해 주세요."
  fi
  confirm_retention
  link_env
  write_rendered
  say "설정을 펼쳤습니다: $RENDER_DIR (CA 인증서: $CA_FILE)"
  if [ "$LAYOUT" = repository ]; then
    # Compose가 .env를 다시 읽지 않고 이 명령이 검사한 값(compose.env)을 쓰게 한다(요구사항 0028의 S15).
    say "저장소 폴더에서 docker compose --env-file infrastructure/rendered/compose.env -f infrastructure/compose.yml up -d --build로 띄워 주세요. .env나 CA 인증서 파일을 바꾸면 render를 다시 실행해 주세요."
  else
    say "compose.yml이 있는 폴더에서 docker compose up -d로 띄워 주세요. .env나 CA 인증서 파일을 바꾸면 render를 다시 실행해 주세요."
  fi
  if monitoring_exists; then
    say "떠 있는 모니터링 시스템에 새 설정을 쓰려면 docker compose up -d를 실행해 주세요. 그 전에 서버를 재시작해도 새 설정이 쓰입니다."
  fi
}

cmd_start() {
  check_docker
  check_network
  check_checksums
  check_render
  check_ready_tool
  load_images
  confirm_retention
  # 확인을 기다리는 동안 token 파일이 바뀌었을 수 있어 쓰기 직전에 다시 본다. 운영자가 멈추면 펼친 설정은 그대로다.
  check_token
  link_env
  write_rendered
  # 띄우면 컨테이너의 버전이 바뀌므로 직전 버전을 정할 값을 미리 읽어 둔다.
  version_before_start=$(running_version)
  if [ "$render_changed" = yes ]; then
    compose_up --force-recreate
  else
    compose_up
  fi
  wait_ready $CONTAINERS
  check_autostart
  show_settings
  record_and_clean "$version_before_start"
  say "모니터링 시스템이 떠 있습니다: 버전 $(running_label org.opencontainers.image.version), 커밋 $(running_label org.opencontainers.image.revision)"
}

# 직전 버전의 묶음 폴더에서 start를 실행한다. 검사, 띄우기, 버전 기록, 정리는 그 버전의 start가 한다(요구사항 0023).
# 지금 버전은 떠 있는(멈춘 것도 포함) 모니터링 시스템의 버전이고, 없으면 기록의 지금 버전이다.
cmd_rollback() {
  [ "$LAYOUT" = bundle ] \
    || fail "rollback은 모니터링 서버의 묶음 폴더에서만 실행합니다."
  check_docker
  rollback_from=$(running_version)
  if [ -z "$rollback_from" ]; then
    rollback_from=$(recorded_version current)
  fi
  rollback_to=""
  if [ -n "$rollback_from" ]; then
    rollback_to=$(previous_of "$rollback_from")
  fi
  [ -n "$rollback_to" ] \
    || fail "되돌릴 버전이 없습니다. 지금 버전 전에 띄운 버전의 기록이 없습니다($VERSIONS_FILE). 남아 있는 다른 묶음 폴더에서 sh monitoring-server.sh start를 실행하면 그 버전으로 바뀝니다. 아무것도 바꾸지 않았습니다."
  rollback_dir="${INSTALL_DIR%/}/monitoring-system-$rollback_to"
  if [ "$(bundle_dir_version "$rollback_dir")" != "$rollback_to" ] || [ ! -f "$rollback_dir/monitoring-server.sh" ]; then
    fail "직전 버전($rollback_to)의 묶음 폴더가 없습니다: $rollback_dir. 그 버전의 묶음을 이 위치로 다시 옮긴 뒤 실행해 주세요. 아무것도 바꾸지 않았습니다."
  fi
  say "직전 버전으로 되돌립니다: $rollback_from → $rollback_to"
  sh "$rollback_dir/monitoring-server.sh" start
  # 그 start가 기록 보관 값 줄임 확인에서 멈추면 아무것도 바꾸지 않고 성공으로 끝난다. 그래서 떠 있는 버전으로 확인한다.
  if [ "$(running_version)" != "$rollback_to" ]; then
    fail "되돌리지 않았습니다. 떠 있는 버전: $(running_version)"
  fi
  say "되돌렸습니다: $rollback_from → $rollback_to"
}

cmd_status() {
  check_docker
  new=$(bundle_version)
  if ! monitoring_exists; then
    say "모니터링 시스템이 없습니다. 이 묶음의 버전: $new"
    return 0
  fi
  current=$(running_label org.opencontainers.image.version) || current=""
  if monitoring_running; then
    say "떠 있는 버전: $current (커밋 $(running_label org.opencontainers.image.revision))"
  else
    say "모니터링 시스템이 멈춰 있습니다. 마지막으로 띄운 버전: $current (커밋 $(running_label org.opencontainers.image.revision))"
  fi
  # 실행 중인 컨테이너만 준비 확인 주소를 부른다.
  ready_with=$(ready_tool)
  for name in $CONTAINERS; do
    state=$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null) || state="없음"
    if [ "$state" != running ]; then
      say "  $name: $state"
    elif [ -z "$ready_with" ]; then
      say "  $name: running, 준비 확인 못 함(curl, wget 없음)"
    elif is_ready "$name"; then
      say "  $name: running, 준비됨"
    else
      say "  $name: running, 준비되지 않음"
    fi
  done
  load_settings
  show_settings
  if [ "$current" != "$new" ]; then
    say "이 묶음의 버전($new)은 모니터링 시스템의 버전($current)과 다릅니다."
  fi
}

case "${1:-}" in
  install) cmd_install ;;
  render) cmd_render ;;
  start) cmd_start ;;
  rollback) cmd_rollback ;;
  status) cmd_status ;;
  *) usage; exit 1 ;;
esac
