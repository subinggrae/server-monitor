#!/bin/sh
# 내부망 코어 서버에서 모니터링 코어를 설치하고 띄우는 운영 명령이다. 묶음 폴더 안에 있고, 묶음 폴더에서 실행한다.
#
#   sh core-server.sh install        token과 설정 파일(.env)을 만들거나 떠 있는 버전의 폴더에서 가져온다
#   sh core-server.sh start          확인한 뒤 이미지를 불러와 띄우고 정상 상태까지 기다린다
#   sh core-server.sh rotate-token   token을 새 값으로 바꾸고 Alertmanager를 다시 만든다
#   sh core-server.sh status         떠 있는 버전과 컨테이너 상태를 보인다
#
# 묶음 폴더들은 한 설치 폴더에 나란히 두고, token은 설치 폴더의 secrets/에 둔다(요구사항 0011).
# 코어 서버에는 Node와 bash가 없을 수 있어 POSIX sh로 쓰고, GNU coreutils와 BusyBox가 함께 지원하는 옵션만 쓴다.
# 설치 방법은 docs/OPERATIONS.md에 있다.
set -eu

BUNDLE_DIR=$(cd "$(dirname "$0")" && pwd -P)
INSTALL_DIR=$(dirname "$BUNDLE_DIR")
ENV_FILE="$BUNDLE_DIR/.env"
TOKEN_NAME=alertmanager_webhook_token
CONTAINERS="prometheus alertmanager blackbox-exporter"
# 시험에서는 기다리는 시간을 줄인다.
WAIT_SECONDS=${CORE_WAIT_SECONDS:-180}
WAIT_INTERVAL=${CORE_WAIT_INTERVAL:-5}

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

  install        token과 설정 파일(.env)을 준비합니다
  start          확인한 뒤 코어를 띄웁니다
  rotate-token   token을 새 값으로 바꿉니다
  status         떠 있는 버전과 상태를 보입니다
EOF
}

# .env를 셸로 실행하면 값에 든 명령이 실행되므로 KEY= 줄의 값만 읽는다. 같은 키가 여럿이면 Compose처럼 마지막 값을 쓴다.
env_value() {
  if [ -f "$ENV_FILE" ]; then
    sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r' | sed 's/^"\(.*\)"$/\1/'
  fi
}

valid_address() {
  printf '%s' "$1" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9_.-]*:[0-9]{1,5}$' || return 1
  port=${1##*:}
  [ "$port" -ge 1 ] && [ "$port" -le 65535 ]
}

# Compose와 같이 상대 경로는 묶음 폴더 기준으로 푼다.
token_file() {
  dir=$(env_value CORE_SECRETS_DIR)
  if [ -z "$dir" ]; then
    dir=../secrets
  fi
  case "$dir" in
    /*) ;;
    *) dir="$BUNDLE_DIR/$dir" ;;
  esac
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

check_env() {
  [ -f "$ENV_FILE" ] || fail "설정 파일(.env)이 없습니다. 먼저 sh core-server.sh install을 실행해 주세요."
  address=$(env_value MONITORING_APP_ADDRESS)
  valid_address "$address" \
    || fail "관리자 서버 주소가 올바르지 않습니다: '$address'. .env의 MONITORING_APP_ADDRESS를 컨테이너 이름:포트 형식(예: manager-api:8080)으로 고쳐 주세요."
}

# Docker는 token 파일이 없으면 그 자리에 빈 폴더를 만들고 컨테이너를 띄운다. 그래서 띄우기 전에 확인한다.
check_token() {
  file=$(token_file)
  if [ -d "$file" ]; then
    fail "token 자리에 폴더가 있습니다: $file. 그 폴더를 지운 뒤 sh core-server.sh install로 token을 만들어 주세요."
  fi
  if [ ! -e "$file" ]; then
    hint=""
    if [ -f "$BUNDLE_DIR/secrets/$TOKEN_NAME" ]; then
      hint=" 묶음 폴더 안에 token이 있습니다. $INSTALL_DIR/secrets/로 옮기고 권한을 0644로 두면 관리자 서버와 같은 token을 계속 씁니다."
    fi
    fail "token 파일이 없습니다: $file. sh core-server.sh install로 만들어 주세요.$hint"
  fi
  if [ ! -s "$file" ]; then
    fail "token 파일이 비어 있습니다: $file. 파일을 지운 뒤 sh core-server.sh install로 다시 만들어 주세요."
  fi
  if [ -z "$(find "$file" -prune -perm -0004)" ]; then
    fail "컨테이너가 token 파일을 읽을 수 없습니다: $file. chmod 0644로 권한을 바꿔 주세요."
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

# 버전 태그는 git describe 값이라 크기를 비교할 수 없고, 이미지의 Created는 빌드 캐시 때문에 버전마다 같을 수 있다.
# 그래서 빌드할 때 넣은 커밋 시각 label로 앞뒤를 판단한다. label이 없는 예전 이미지는 0으로 본다.
commit_time() {
  value=$(docker image inspect -f '{{index .Config.Labels "monitoring-core.commit-time"}}' "$1" 2>/dev/null) || value=""
  case "$value" in
    ''|*[!0-9]*) echo 0 ;;
    *) echo "$value" ;;
  esac
}

confirm_version() {
  new=$(bundle_version)
  current=$(running_label org.opencontainers.image.version) || current=""
  if [ -z "$current" ]; then
    say "띄울 버전: $new"
    return 0
  fi
  if [ "$current" = "$new" ]; then
    say "떠 있는 버전과 같은 버전($new)을 다시 띄웁니다."
    return 0
  fi
  say "떠 있는 버전: $current → 띄울 버전: $new"
  current_time=$(commit_time "$(docker inspect -f '{{.Image}}' prometheus)")
  new_time=$(commit_time "monitoring-core-prometheus:$new")
  if [ "$new_time" -ge "$current_time" ]; then
    return 0
  fi
  if [ ! -t 0 ]; then
    fail "지금($current)보다 이전 버전($new)으로 바꾸려면 터미널에서 실행해 확인해 주세요. 아무것도 바꾸지 않았습니다."
  fi
  printf '지금(%s)보다 이전 버전(%s)으로 바꿉니다. 계속할까요? [y/N] ' "$current" "$new"
  read -r answer || answer=""
  case "$answer" in
    y|Y|yes) ;;
    *) say "아무것도 바꾸지 않았습니다."; exit 0 ;;
  esac
}

wait_healthy() {
  say "정상 상태가 될 때까지 기다립니다(최대 ${WAIT_SECONDS}초): $*"
  waited=0
  while :; do
    unhealthy=""
    for name in "$@"; do
      status=$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null) || status="없음"
      if [ "$status" != healthy ]; then
        unhealthy="$unhealthy $name"
      fi
    done
    if [ -z "$unhealthy" ]; then
      return 0
    fi
    if [ "$waited" -ge "$WAIT_SECONDS" ]; then
      break
    fi
    sleep "$WAIT_INTERVAL"
    waited=$((waited + WAIT_INTERVAL))
  done
  {
    say "정상 상태가 되지 않았습니다:$unhealthy. 컨테이너는 그대로 두었습니다. 다음 명령으로 로그를 보세요."
    for name in $unhealthy; do
      say "  docker logs $name"
    done
  } >&2
  exit 1
}

# 재시작 정책(unless-stopped)은 Docker가 켜져 있어야 동작한다. docker.socket만 켜져 있으면 서버가 켜질 때 Docker가 뜨지 않는다.
check_autostart() {
  if ! command -v systemctl > /dev/null 2>&1; then
    say "참고: 서버가 켜질 때 Docker가 자동으로 켜지는지 확인하지 못했습니다(systemctl 없음). 서버 시작 때 Docker가 켜지도록 설정해 주세요."
    return 0
  fi
  state=$(systemctl is-enabled docker.service 2>/dev/null) || true
  if [ "$state" != enabled ]; then
    say "경고: 서버가 켜질 때 Docker가 자동으로 켜지지 않습니다. 이대로면 서버가 재시작된 뒤 코어가 뜨지 않습니다. 켜려면: sudo systemctl enable docker"
  fi
}

# Compose가 컨테이너에 남긴 작업 폴더로 떠 있는 버전의 묶음 폴더를 찾는다. 같은 설치 폴더 안의 폴더만 믿는다.
previous_bundle() {
  workdir=$(running_label com.docker.compose.project.working_dir) || return 0
  if [ -z "$workdir" ] || [ ! -d "$workdir" ]; then
    return 0
  fi
  real=$(cd "$workdir" && pwd -P) || return 0
  if [ "$(dirname "$real")" = "$INSTALL_DIR" ] && [ "$real" != "$BUNDLE_DIR" ] && [ -f "$real/.env" ]; then
    printf '%s' "$real"
  fi
}

cmd_install() {
  file=$(token_file)
  token_existed=no
  if [ -e "$file" ]; then
    token_existed=yes
  fi

  env_from=""
  address=""
  if [ -f "$ENV_FILE" ]; then
    say "설정 파일(.env)이 이미 있습니다. 그대로 씁니다."
  else
    env_from=$(previous_bundle) || env_from=""
    if [ -z "$env_from" ]; then
      if [ "$token_existed" = yes ]; then
        say "설정을 가져올 폴더를 찾지 못했습니다. 떠 있는 코어가 없거나, 떠 있는 버전의 폴더가 $INSTALL_DIR 안에 없습니다."
      fi
      printf '관리자 서버 주소(컨테이너 이름:포트, 예: manager-api:8080): '
      read -r address || address=""
      valid_address "$address" \
        || fail "관리자 서버 주소가 올바르지 않습니다: '$address'. 컨테이너 이름:포트 형식(예: manager-api:8080)으로 다시 실행해 주세요."
    fi
  fi

  if [ "$token_existed" = yes ]; then
    say "token 파일이 이미 있습니다. 그대로 씁니다: $(real_path "$file")"
  else
    mkdir -p "$(dirname "$file")"
    chmod 0700 "$(dirname "$file")"
    write_token "$file"
    say "token 파일을 만들었습니다: $(real_path "$file")"
  fi

  if [ -n "$env_from" ]; then
    cp "$env_from/.env" "$ENV_FILE"
    say "설정을 가져왔습니다: $env_from/.env"
  elif [ -n "$address" ]; then
    sed "s/^MONITORING_APP_ADDRESS=.*/MONITORING_APP_ADDRESS=$address/" "$BUNDLE_DIR/.env.example" > "$ENV_FILE"
    say "설정 파일(.env)을 만들었습니다."
  fi
  say "다음: sh core-server.sh start"
}

cmd_start() {
  check_docker
  check_checksums
  check_env
  check_token
  load_images
  confirm_version
  (cd "$BUNDLE_DIR" && docker compose up -d)
  wait_healthy $CONTAINERS
  check_autostart
  say "코어가 떠 있습니다: 버전 $(running_label org.opencontainers.image.version), 커밋 $(running_label org.opencontainers.image.revision)"
}

cmd_rotate_token() {
  check_docker
  check_env
  check_token
  file=$(token_file)
  write_token "$file"
  say "token을 새 값으로 바꿨습니다: $(real_path "$file")"
  current=$(running_label org.opencontainers.image.version) || current=""
  if [ -z "$current" ]; then
    say "코어가 떠 있지 않습니다. 다음 start부터 새 token을 씁니다."
  else
    # 파일만 바꾸면 Alertmanager가 붙잡은 옛 파일을 계속 읽을 수 있어 컨테이너를 다시 만든다.
    (cd "$BUNDLE_DIR" && docker compose up -d --force-recreate alertmanager)
    wait_healthy alertmanager
  fi
  say "관리자 서버도 이 파일을 다시 읽도록 다시 띄워 주세요. 다시 띄울 때까지 장애 알림이 거부됩니다."
}

cmd_status() {
  check_docker
  new=$(bundle_version)
  current=$(running_label org.opencontainers.image.version) || current=""
  if [ -z "$current" ]; then
    say "코어가 떠 있지 않습니다. 이 묶음의 버전: $new"
    return 0
  fi
  say "떠 있는 버전: $current (커밋 $(running_label org.opencontainers.image.revision))"
  for name in $CONTAINERS; do
    state=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$name" 2>/dev/null) || state="없음"
    say "  $name: $state"
  done
  if [ "$current" != "$new" ]; then
    say "이 묶음의 버전($new)은 떠 있는 버전과 다릅니다."
  fi
}

case "${1:-}" in
  install) cmd_install ;;
  start) cmd_start ;;
  rotate-token) cmd_rotate_token ;;
  status) cmd_status ;;
  *) usage; exit 1 ;;
esac
