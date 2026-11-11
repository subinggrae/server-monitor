#!/bin/sh
# 내부망 코어 서버에서 모니터링 코어를 설치하고 띄우는 운영 명령이다. 묶음 폴더 안에 있고, 묶음 폴더에서 실행한다.
#
#   sh core-server.sh install   token과 설정 파일(.env)이 없으면 만든다
#   sh core-server.sh render    설정 값을 검사해 설정을 rendered/에 펼친다. 컨테이너는 바꾸지 않는다
#   sh core-server.sh start     확인하고 설정을 펼친 뒤 이미지를 불러와 띄우고 정상 상태까지 기다린다
#   sh core-server.sh status    떠 있는 버전, 컨테이너 상태와 준비 여부, 기본값과 다른 설정 값을 보인다
#
# 최소 기능만 둔다. token 바꾸기, 이전 방식의 .env를 설치 폴더로 처음 옮기기, 오래된 버전 정리는 운영 가이드의 절차로 한다(요구사항 0015).
#
# 묶음 폴더들은 한 설치 폴더에 나란히 두고, token은 설치 폴더의 secrets/에, 설정 파일은 설치 폴더의 .env에 둔다(요구사항 0011, 0017).
# .env에서 바꾸는 코어 값(보관 기간·용량, 확인 제한 시간, 호스트 포트)의 규칙은 같은 폴더의 core-settings.sh에 있다(요구사항 0013).
# 코어 이미지에는 셸이 없어 컨테이너 안에서 설정을 펼칠 수 없다. 그래서 이 명령이 값을 검사해 묶음의 templates/에서
# rendered/로 펼치고, compose 파일이 펼친 설정을 읽기 전용으로 붙인다. 정상 확인도 코어 서버에서 한다(요구사항 0015, ADR 0012).
# Blackbox Exporter가 쓸 CA 인증서 파일은 운영자가 운영 가이드대로 설치 폴더의 certs/에 복사하고, 이 명령은 확인만 한다.
# 저장소의 infrastructure/에서도 render를 실행할 수 있다. 그때는 저장소의 템플릿, ./secrets, ./certs를 쓴다(core-dev.bat start).
# 코어 서버에는 Node와 bash가 없을 수 있어 POSIX sh로 쓰고, GNU coreutils와 BusyBox가 함께 지원하는 옵션만 쓴다.
# 설치 방법은 docs/OPERATIONS.md에 있다.
set -eu

BUNDLE_DIR=$(cd "$(dirname "$0")" && pwd -P)
INSTALL_DIR=$(dirname "$BUNDLE_DIR")
RENDER_DIR="$BUNDLE_DIR/rendered"
TOKEN_NAME=alertmanager_webhook_token
CONTAINERS="prometheus alertmanager blackbox-exporter"
TEMPLATES="prometheus.yml alertmanager.yml blackbox.yml"
# core.env의 CORE_CONFIG_SHA256이 이 파일들과 CA 인증서 파일의 내용을 나타낸다.
CONFIG_FILES="$TEMPLATES"
# 시험(tests/core-server.test.sh)에서만 쓰는 값이다. 기다리는 시간을 줄이고 준비 확인 도구를 고른다.
# 운영 문서에는 적지 않고, 운영 서버에서는 설정하지 않는다.
WAIT_SECONDS=${CORE_WAIT_SECONDS:-180}
WAIT_INTERVAL=${CORE_WAIT_INTERVAL:-5}
READY_TOOLS=${CORE_READY_TOOLS-curl wget}

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

say() {
  printf '%s\n' "$*"
}

fail() {
  printf '실패: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'EOF'
사용법: sh core-server.sh <작업>

  install   token과 설정 파일(.env)을 준비합니다
  render    설정 값을 검사해 설정을 펼칩니다(docker compose로 직접 띄울 때만 씁니다)
  start     설정 값을 검사하고 코어를 띄웁니다
  status    떠 있는 버전, 상태, 기본값과 다른 설정 값을 보입니다
EOF
}

# Compose와 같은 값을 보도록, 셸 환경 변수가 있으면 그 값을 쓰고 없으면 .env의 값을 쓴다.
# .env를 셸로 실행하면 값에 든 명령이 실행되므로 실행하지 않고 읽는다. export, 따옴표, 줄 끝 주석은 Compose처럼 다루고,
# 같은 키가 여럿이면 마지막 값을 쓴다.
env_value() {
  if value=$(printenv "$1"); then
    printf '%s' "$value"
    return 0
  fi
  if [ ! -f "$ENV_FILE" ]; then
    return 0
  fi
  raw=$(sed -n -E "s/^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*=[[:space:]]*//p" "$ENV_FILE" | tail -n 1 | tr -d '\r')
  case "$raw" in
    \"*) printf '%s' "$raw" | sed 's/^"\([^"]*\)".*$/\1/' ;;
    \'*) printf '%s' "$raw" | sed "s/^'\([^']*\)'.*\$/\1/" ;;
    *) printf '%s' "$raw" | sed 's/[[:space:]][[:space:]]*#.*$//; s/[[:space:]]*$//' ;;
  esac
}

# Compose와 같이 상대 경로는 이 묶음 폴더 기준으로 푼다.
token_file() {
  dir=$(env_value CORE_SECRETS_DIR)
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
write_token() {
  tmp=$(umask 077 && mktemp "$1.XXXXXX")
  od -An -tx1 -N32 /dev/urandom | tr -d ' \n' > "$tmp"
  printf '\n' >> "$tmp"
  # 컨테이너 사용자(nobody)가 읽어야 한다. 다른 사용자는 0700인 폴더에 막힌다.
  chmod 0644 "$tmp"
  mv -f "$tmp" "$1"
}

running_label() {
  docker inspect -f "{{index .Config.Labels \"$1\"}}" prometheus 2>/dev/null
}

# 멈춘 컨테이너도 있다고 본다. 멈춘 컨테이너도 다시 시작하면 지금 값으로 돈다.
core_exists() {
  docker inspect -f '{{.State.Running}}' prometheus > /dev/null 2>&1
}

core_running() {
  [ "$(docker inspect -f '{{.State.Running}}' prometheus 2>/dev/null)" = true ]
}

bundle_version() {
  sed -n 's/^ *image: monitoring-core-prometheus:\(.*\)$/\1/p' "$BUNDLE_DIR/compose.yml" | head -n 1 | tr -d '\r'
}

check_docker() {
  docker info > /dev/null 2>&1 \
    || fail "Docker에 연결하지 못했습니다. Docker가 실행 중인지, 이 사용자가 docker 명령을 쓸 수 있는지 확인해 주세요."
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

# 이전 방식으로 묶음 폴더에 둔 설정 파일이 있으면, 어느 값을 쓰는지 헷갈리지 않게 멈추고 옮기거나 지우라고 안내한다.
# 링크는 따라가지 않는다. 설치 폴더의 .env가 링크면 다른 곳의 파일을 읽거나 install이 그 파일에 쓰게 된다(CWE-59).
check_env_location() {
  [ "$LAYOUT" = bundle ] || return 0
  if [ -L "$ENV_FILE" ]; then
    fail "설치 폴더의 설정 파일이 링크입니다: $ENV_FILE. 링크를 지우고 실제 파일을 둔 뒤 다시 실행해 주세요."
  fi
  env_link_ok && return 0
  if [ -L "$ENV_LINK" ] || [ -e "$ENV_FILE" ]; then
    fail "묶음 폴더의 설정 파일은 쓰지 않습니다: $ENV_LINK. 고친 값이 있으면 $ENV_FILE 에 옮긴 뒤, sudo rm $ENV_LINK 로 지우고 다시 실행해 주세요."
  fi
  fail "설정 파일(.env)을 설치 폴더로 옮겨 주세요: sudo mv $ENV_LINK $ENV_FILE"
}

# 이전 방식으로 쓰던 설정 파일을 찾는다. 떠 있거나 멈춘 코어의 묶음 폴더를 먼저 보고, 없으면 설치 폴더의 다른 코어
# 묶음 폴더 가운데 가장 최근에 고친 파일을 고른다. 관리자 서버처럼 코어가 아닌 폴더의 .env는 보지 않는다.
previous_env() {
  dir=$(running_label com.docker.compose.project.working_dir) || dir=""
  if [ -n "$dir" ] && [ -f "$dir/.env" ] && [ ! -L "$dir/.env" ]; then
    printf '%s' "$dir/.env"
    return 0
  fi
  newest=""
  for old in "$INSTALL_DIR"/monitoring-core-*/.env; do
    if [ -f "$old" ] && [ ! -L "$old" ] && { [ -z "$newest" ] || [ "$old" -nt "$newest" ]; }; then
      newest=$old
    fi
  done
  [ -n "$newest" ] || return 1
  printf '%s' "$newest"
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
    # 운영 명령이 직접 복사하지 않는다. 쓰던 파일을 찾으면 복사하는 명령만 보인다(요구사항 0017의 S5).
    if [ "$LAYOUT" = bundle ] && old=$(previous_env); then
      fail "설치 폴더에 설정 파일(.env)이 없습니다. 쓰던 설정을 이어 쓰려면 sudo cp $old $ENV_FILE 를 실행하고 다시 실행해 주세요."
    fi
    # 갱신하며 install로 새 .env를 만들면 바꿔 둔 값이 조용히 기본값으로 돌아간다.
    if core_exists; then
      fail "설정 파일(.env)이 없습니다. 새 버전으로 갱신하는 중이면 install 대신 쓰던 .env를 $ENV_FILE 로 복사해 주세요. 처음 설치하면 sh core-server.sh install을 실행해 주세요."
    fi
    fail "설정 파일(.env)이 없습니다. 먼저 sh core-server.sh install을 실행해 주세요."
  fi
  load_settings
  address=$(env_value MONITORING_APP_ADDRESS)
  core_valid_address "$address" \
    || fail "관리자 서버 주소가 올바르지 않습니다: '$address'. .env의 MONITORING_APP_ADDRESS를 컨테이너 이름:포트 형식(예: manager-api:8080)으로 고쳐 주세요."
  # 펼칠 때 .env나 셸 환경 변수를 다시 읽지 않고 검사한 값을 그대로 쓴다.
  app_address=$address
}

load_settings() {
  [ -f "$BUNDLE_DIR/core-settings.sh" ] \
    || fail "묶음 파일 core-settings.sh가 없습니다. 묶음 폴더를 다시 옮겨 주세요."
  . "$BUNDLE_DIR/core-settings.sh"
}

# .env에서 바꾸는 코어 값을 모두 검사하고, 틀린 값은 한 번에 모두 보인 뒤 아무것도 바꾸지 않고 멈춘다.
# 검사한 값은 export해 펼치기와 Compose(호스트 포트)가 .env를 다시 읽지 않고 그 값을 그대로 쓰게 한다.
# env_value의 .env 읽기가 Compose와 조금이라도 다르면 검사한 값과 띄우는 값이 어긋날 수 있기 때문이다.
check_settings() {
  load_settings
  settings_ok=yes
  for setting in $CORE_SETTING_NAMES; do
    if printenv "$setting" > /dev/null; then
      hint="이 값은 셸 환경 변수 $setting에서 왔고, 셸 환경 변수는 .env의 $setting 값보다 먼저 쓰입니다. 셸에서 unset $setting 명령으로 지우거나 값을 고친 뒤 다시 실행해 주세요."
    else
      hint=".env의 $setting 값을 고친 뒤 다시 실행해 주세요."
    fi
    if core_setting_check "$setting" "$(env_value "$setting")" "$hint"; then
      export "$setting=$core_value"
    else
      settings_ok=no
    fi
  done
  [ "$settings_ok" = yes ] || fail "설정 값이 틀려 아무것도 바꾸지 않았습니다. 떠 있던 코어는 그대로 돕니다."
}

# 컨테이너(첫 번째 인자)가 받은 환경 변수(두 번째 인자)의 값. 없으면 빈 값이다.
container_env() {
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" 2>/dev/null | sed -n "s/^$2=//p" | head -n 1
}

# 컨테이너가 받은 값을 검사해 쓸 값으로 돌려준다. 변수가 없거나 틀리면 기본값을 돌려준다.
# 이 기능 전의 컨테이너에는 변수가 없고, 그 코어는 늘 기본값(15일, 제한 없음, 5초)으로 돌았다.
# 틀린 값을 받은 컨테이너는 시작하지 못하므로, 지난 기록은 기본값이나 그 전의 값으로 남아 있다.
running_setting() {
  raw=$(container_env "$1" "$2")
  if core_setting_check "$2" "$raw" "" 2> /dev/null; then
    printf '%s' "$core_value"
  else
    core_setting_spec "$2"
    printf '%s' "$spec_default"
  fi
}

# 컨테이너 안 포트(두 번째 인자)에 묶인 호스트 주소:포트. 멈춘 컨테이너도 읽을 수 있게 HostConfig에서 읽는다.
host_binding() {
  docker inspect -f '{{json .HostConfig.PortBindings}}' "$1" 2>/dev/null \
    | sed -n "s/.*\"$2\/tcp\":\[{\"HostIp\":\"\([^\"]*\)\",\"HostPort\":\"\([0-9]*\)\".*/\1:\2/p"
}

# 떠 있는(멈춘 것도 포함) 코어보다 보관 기간이나 용량을 줄이면 오래된 기록이 지워져 되돌릴 수 없으므로 확인을 받는다.
# 제한이 없던 용량을 처음 정하는 것도 줄임으로 본다(요구사항 0013의 S12). 터미널에서만 묻고, 터미널이 아니면 띄우지 않는다.
confirm_retention() {
  core_exists || return 0
  current_days=$(running_setting prometheus CORE_RETENTION_DAYS)
  current_size=$(running_setting prometheus CORE_RETENTION_SIZE_GB)
  changes=""
  if [ "$CORE_RETENTION_DAYS" -lt "$current_days" ]; then
    changes="보관 기간 ${current_days}일 → ${CORE_RETENTION_DAYS}일"
  fi
  if [ -n "$CORE_RETENTION_SIZE_GB" ]; then
    if [ -z "$current_size" ]; then
      changes="${changes:+$changes, }보관 용량 제한 없음 → ${CORE_RETENTION_SIZE_GB}GB"
    elif [ "$CORE_RETENTION_SIZE_GB" -lt "$current_size" ]; then
      changes="${changes:+$changes, }보관 용량 ${current_size}GB → ${CORE_RETENTION_SIZE_GB}GB"
    fi
  fi
  [ -n "$changes" ] || return 0
  say "기록 보관 값을 줄입니다: $changes"
  say "띄우면 오래된 기록이 지워지고, 되돌릴 수 없습니다."
  if [ ! -t 0 ]; then
    fail "기록 보관 값을 줄이려면 터미널에서 직접 실행해 y를 입력해 주세요. 코어는 바꾸지 않았습니다."
  fi
  printf '계속할까요? [y/N] '
  read -r answer || answer=""
  case "$answer" in
    y|Y|yes) ;;
    *) say "코어는 바꾸지 않았습니다."; exit 0 ;;
  esac
}

# 컨테이너가 실제로 받은 값에서 기본값과 다른 것만 보인다. 셸 환경 변수가 .env를 덮을 수 있어 .env가 아니라 컨테이너에서 읽는다.
# 틀린 값(운영 명령 없이 띄운 경우)은 기본값으로 바꾸지 않고 그대로 올바르지 않다고 보인다.
show_settings() {
  differs=""
  for setting in $CORE_SETTING_NAMES; do
    case "$setting" in
      CORE_RETENTION_*) container=prometheus ;;
      CORE_PROBE_TIMEOUT_SECONDS) container=blackbox-exporter ;;
      *) container="" ;;
    esac
    if [ -n "$container" ] && ! core_setting_check "$setting" "$(container_env "$container" "$setting")" "" 2> /dev/null; then
      core_setting_spec "$setting"
      differs="${differs:+$differs, }$spec_label '$(container_env "$container" "$setting")'(올바르지 않음)"
      continue
    fi
    case "$setting" in
      CORE_RETENTION_*) shown=$(running_setting prometheus "$setting") ;;
      CORE_PROBE_TIMEOUT_SECONDS) shown=$(running_setting blackbox-exporter "$setting") ;;
      CORE_PROMETHEUS_PORT) shown=$(host_binding prometheus 9090) ;;
      CORE_ALERTMANAGER_PORT) shown=$(host_binding alertmanager 9093) ;;
      CORE_BLACKBOX_PORT) shown=$(host_binding blackbox-exporter 9115) ;;
    esac
    core_setting_spec "$setting"
    if [ -n "$shown" ] && [ "${shown##*:}" != "$spec_default" ]; then
      differs="${differs:+$differs, }$spec_label $shown$spec_unit"
    fi
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
    fail "token 자리에 폴더가 있습니다: $file. 그 폴더를 지운 뒤 sh core-server.sh install로 token을 만들어 주세요."
  fi
  if [ ! -e "$file" ]; then
    # 코어가 이미 있으면 관리자 서버가 그 코어의 token을 쓰고 있다. 새 token을 만들면 장애 알림이 모두 거부된다.
    if core_exists; then
      fail "token 파일이 없습니다: $file. 쓰던 token 파일을 이 위치로 옮기거나, .env의 CORE_SECRETS_DIR를 그 파일이 있는 폴더로 고쳐 주세요. 새로 만들면 관리자 서버의 token과 어긋납니다."
    fi
    fail "token 파일이 없습니다: $file. sh core-server.sh install로 만들어 주세요."
  fi
  if [ ! -s "$file" ]; then
    fail "token 파일이 비어 있습니다: $file. 파일을 지운 뒤 sh core-server.sh install로 다시 만들어 주세요."
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
}

# Blackbox Exporter의 HTTPS 확인이 쓸 CA 인증서 파일을 확인한다. 운영자가 운영 가이드대로 코어 서버 OS의 파일을 복사해 둔다.
# 이 명령은 OS의 파일을 읽거나 옮기지 않는다. 내용은 OS와 운영자가 관리하므로 검사하지 않는다.
# 파일이 없는데 Compose로 띄우면 Docker가 그 자리에 빈 폴더를 만들고, HTTPS 주소 확인이 모두 실패해 장애 알림이 한꺼번에 갈 수 있다.
check_ca() {
  ca_hint="코어 서버 OS의 CA 인증서 파일을 복사해 주세요(Ubuntu, Debian, Amazon Linux 예: sudo mkdir -p $(dirname "$CA_FILE") && sudo cp -L /etc/ssl/certs/ca-certificates.crt $CA_FILE && sudo chmod 0644 $CA_FILE). RHEL 계열은 /etc/pki/tls/certs/ca-bundle.crt를 복사합니다. 자세한 방법은 docs/OPERATIONS.md에 있습니다."
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
  for name in $CONFIG_FILES core.env; do
    target="$RENDER_DIR/$name"
    if [ -L "$target" ]; then
      fail "펼친 설정 파일이 심볼릭 링크입니다: $target. 링크를 지운 뒤 다시 실행해 주세요."
    fi
    if [ -d "$target" ]; then
      fail "펼친 설정 파일 자리에 폴더가 있습니다: $target. sudo rmdir $target 로 지운 뒤 다시 실행해 주세요."
    fi
  done
}

# 펼치기 전의 검사다. 아무것도 쓰지 않으므로, 실패해도 이전에 펼친 설정과 떠 있는 코어가 그대로다.
check_render() {
  check_env
  check_settings
  check_token
  check_templates
  check_ca
  check_render_dir
}

# check_render를 통과한 값으로 템플릿을 펼쳐 rendered/에 쓴다. 같은 폴더 안의 임시 폴더(stage)에 모두 쓴 뒤 이름만 바꿔 옮긴다.
# 펼치는 글자는 검사를 통과한 주소와 core_setting_text의 결과뿐이라 sed와 YAML이 특별하게 읽는 글자가 없다. 값은 eval하지 않는다.
# config_hash는 새로 펼친 설정과 CA 인증서 파일, previous_hash는 지금 rendered/에 있는 설정과 지난번에 펼칠 때의 CA 인증서 파일의 해시다.
# CA 인증서 파일은 옮기지 않으므로, 지난번 내용은 core.env의 CORE_CA_SHA256으로 안다.
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
  for setting in $CORE_SETTING_NAMES; do
    script="$script
s|@$setting@|$(core_setting_text "$setting" "$(printenv "$setting")")|g"
  done
  # Windows에서 받은 저장소의 템플릿은 줄 끝이 CRLF일 수 있다. 어디서 펼쳐도 같은 파일이 되게 CR을 지운다.
  for name in $TEMPLATES; do
    tr -d '\r' < "$(template_file "$name")" | sed "$script" > "$stage/$name"
    if grep -q '@[A-Z][A-Z_]*@' "$stage/$name"; then
      fail "$(template_file "$name")에 채우지 못한 값이 있습니다. 묶음 폴더를 다시 옮겨 주세요. 아무것도 바꾸지 않았습니다."
    fi
  done
  ca_hash=$(sha256sum < "$CA_FILE" | cut -d ' ' -f 1)
  config_hash=$( { cd "$stage" && cat $CONFIG_FILES; printf '%s\n' "$ca_hash"; } | sha256sum | cut -d ' ' -f 1)
  previous_ca_hash=$(sed -n 's/^CORE_CA_SHA256=//p' "$RENDER_DIR/core.env" 2>/dev/null || true)
  previous_hash=$( { cd "$RENDER_DIR" && cat $CONFIG_FILES 2>/dev/null; printf '%s\n' "$previous_ca_hash"; } | sha256sum | cut -d ' ' -f 1)
  # compose 파일이 env_file로 세 컨테이너에 넘긴다. 프로그램은 읽지 않고, status와 보관 값 줄임 확인이 떠 있는 값을 여기서 읽는다.
  # CORE_CONFIG_SHA256이 바뀌면 운영 명령 없이 docker compose up -d로 띄워도 Compose가 컨테이너를 다시 만든다.
  {
    printf '# sh core-server.sh render가 .env로 만든 파일입니다. 펼칠 때마다 다시 만들므로, 바꿀 값은 .env에 적어 주세요.\n'
    for setting in CORE_RETENTION_DAYS CORE_RETENTION_SIZE_GB CORE_PROBE_TIMEOUT_SECONDS; do
      printf '%s=%s\n' "$setting" "$(printenv "$setting")"
    done
    printf 'CORE_CA_SHA256=%s\n' "$ca_hash"
    printf 'CORE_CONFIG_SHA256=%s\n' "$config_hash"
  } > "$stage/core.env"
  for name in $CONFIG_FILES core.env; do
    chmod 0644 "$stage/$name"
  done
  # 이전과 내용이 달라졌으면 render_changed=yes다. 파일 단위로 붙인 컨테이너는 이름을 바꿔 옮긴 새 파일을
  # 다시 시작할 때까지 보지 못하기 때문이다.
  for name in $CONFIG_FILES core.env; do
    mv -f "$stage/$name" "$RENDER_DIR/$name"
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
    if ! docker image inspect "monitoring-core-$name:$version" > /dev/null 2>&1; then
      say "코어 이미지를 불러옵니다(버전 $version)."
      docker load -i "$BUNDLE_DIR/core-images.tar"
      return 0
    fi
  done
}

# 코어 이미지에는 셸과 wget이 없어 컨테이너 healthcheck를 둘 수 없다. 그래서 코어 서버에서 호스트 포트로 준비 확인 주소를 부른다.
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

# 띄우기 전에 본다. 띄운 뒤에 없다는 것을 알면 정상인지 모르는 코어가 남는다.
check_ready_tool() {
  ready_with=$(ready_tool)
  [ -n "$ready_with" ] \
    || fail "코어 서버에 curl과 wget이 없어 코어가 정상인지 확인할 수 없습니다. curl을 설치한 뒤 다시 실행해 주세요. 아무것도 바꾸지 않았습니다."
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
# 코어 서버에 포트를 보는 도구가 있다고 보장할 수 없어, 띄우기 전에 포트가 쓰이는지 미리 보지 않는다(요구사항 0013의 S13).
compose_up() {
  if (cd "$BUNDLE_DIR" && docker compose up -d "$@"); then
    return 0
  fi
  {
    say "실패: Docker가 코어를 띄우지 못했습니다. 위 Docker 오류를 확인해 주세요."
    say "오류에 port is already allocated나 address already in use가 있으면 다른 프로그램이 그 포트를 씁니다. .env의 CORE_PROMETHEUS_PORT, CORE_ALERTMANAGER_PORT, CORE_BLACKBOX_PORT 가운데 그 포트를 빈 포트로 바꿔 주세요."
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
    say "경고: 서버가 켜질 때 Docker가 자동으로 켜지지 않습니다. 이대로면 서버를 재시작한 뒤 코어가 뜨지 않습니다. sudo systemctl enable docker를 실행해 주세요."
  fi
}

# 없는 것만 만들고, 있는 token과 .env는 그대로 둔다.
cmd_install() {
  check_docker
  load_settings
  check_env_location
  file=$(token_file)

  address=""
  if [ -f "$ENV_FILE" ]; then
    say "설정 파일(.env)이 이미 있습니다. 그대로 씁니다."
  else
    printf '관리자 서버 주소(컨테이너 이름:포트, 예: manager-api:8080): '
    read -r address || address=""
    core_valid_address "$address" \
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
    # 같은 폴더에 쓴 뒤 이름을 바꾼다. 주소를 묻는 사이 그 자리에 링크가 생겨도 링크를 따라 쓰지 않는다.
    tmp=$(mktemp "$ENV_FILE.XXXXXX")
    sed "s/^MONITORING_APP_ADDRESS=.*/MONITORING_APP_ADDRESS=$address/" "$BUNDLE_DIR/.env.example" > "$tmp"
    chmod 0644 "$tmp"
    mv -f "$tmp" "$ENV_FILE"
    say "설정 파일을 만들었습니다: $ENV_FILE"
  fi
  link_env
  say "필요하면 $ENV_FILE 의 값을 고친 뒤 sh core-server.sh start를 실행해 주세요."
}

cmd_render() {
  if [ "$LAYOUT" = bundle ]; then
    check_checksums
  fi
  check_render
  # 떠 있는 컨테이너는 다시 시작할 때 새로 펼친 파일을 읽을 수 있다. 그래서 보관 값을 줄이면 띄우기처럼 확인을 묻는다.
  # Docker가 없는 곳(빌드 PC의 검사용 컨테이너)에서는 떠 있는 코어가 없다고 본다. Docker가 있는데 닿지 못하면 알린다.
  if command -v docker > /dev/null 2>&1 && ! docker info > /dev/null 2>&1; then
    say "경고: Docker에 연결하지 못해 기록 보관 값을 줄였는지 확인하지 못했습니다. 줄였다면 띄우기 전에 .env를 확인해 주세요."
  fi
  confirm_retention
  link_env
  write_rendered
  say "설정을 펼쳤습니다: $RENDER_DIR (CA 인증서: $CA_FILE)"
  say "compose.yml이 있는 폴더에서 docker compose up -d로 띄워 주세요. .env나 CA 인증서 파일을 바꾸면 render를 다시 실행해 주세요."
  if core_exists; then
    say "떠 있는 코어에 새 설정을 쓰려면 docker compose up -d를 실행해 주세요. 그 전에 서버를 재시작해도 새 설정이 쓰입니다."
  fi
}

cmd_start() {
  check_docker
  check_checksums
  check_render
  check_ready_tool
  load_images
  confirm_retention
  # 확인을 기다리는 동안 token 파일이 바뀌었을 수 있어 쓰기 직전에 다시 본다. 운영자가 멈추면 펼친 설정은 그대로다.
  check_token
  link_env
  write_rendered
  if [ "$render_changed" = yes ]; then
    compose_up --force-recreate
  else
    compose_up
  fi
  wait_ready $CONTAINERS
  check_autostart
  show_settings
  say "코어가 떠 있습니다: 버전 $(running_label org.opencontainers.image.version), 커밋 $(running_label org.opencontainers.image.revision)"
}

cmd_status() {
  check_docker
  new=$(bundle_version)
  if ! core_exists; then
    say "코어가 없습니다. 이 묶음의 버전: $new"
    return 0
  fi
  current=$(running_label org.opencontainers.image.version) || current=""
  if core_running; then
    say "떠 있는 버전: $current (커밋 $(running_label org.opencontainers.image.revision))"
  else
    say "코어가 멈춰 있습니다. 마지막으로 띄운 버전: $current (커밋 $(running_label org.opencontainers.image.revision))"
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
    say "이 묶음의 버전($new)은 코어의 버전($current)과 다릅니다."
  fi
}

case "${1:-}" in
  install) cmd_install ;;
  render) cmd_render ;;
  start) cmd_start ;;
  status) cmd_status ;;
  *) usage; exit 1 ;;
esac
