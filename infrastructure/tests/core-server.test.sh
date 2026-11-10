#!/bin/sh
# core-server.sh의 사전 검사, 설정 펼치기, 설치 준비를 가짜 docker, systemctl, curl, wget으로 상황을 만들어 확인한다.
# 실제 Docker는 쓰지 않는다. 컨테이너를 띄우는 흐름은 요구사항 0011, 0015의 수동 확인이 맡는다.
#
#   sh core-server.test.sh <core-server.sh 경로>
#
# 저장소의 infrastructure/에 있는 설정 템플릿을 묶음의 templates/에 넣어 펼친다.
# 종료 코드: 실패한 시험이 하나라도 있으면 1.
set -u

SCRIPT=$1
# 묶음에서 core-server.sh 옆에 있는 공용 검사 파일이다.
SOURCE_DIR=$(dirname "$SCRIPT")
SETTINGS="$SOURCE_DIR/core-settings.sh"
FAILED=0
PASSED=0

# 시험 하나마다 빈 설치 폴더, 묶음 폴더, 가짜 명령, 가짜 OS의 CA 인증서 파일을 새로 만든다.
setup() {
  T=$(mktemp -d)
  INSTALL="$T/install"
  BUNDLE="$INSTALL/monitoring-core-v2"
  FAKE="$T/fake"
  TOKEN="$INSTALL/secrets/alertmanager_webhook_token"
  RENDERED="$BUNDLE/rendered"
  CA_ROOT="$T/os"
  mkdir -p "$BUNDLE/templates" "$FAKE" "$T/bin" "$CA_ROOT/etc/ssl/certs"
  cp "$SCRIPT" "$BUNDLE/core-server.sh"
  cp "$SETTINGS" "$BUNDLE/core-settings.sh"
  cp "$SOURCE_DIR/prometheus/prometheus.yml" "$SOURCE_DIR/alertmanager/alertmanager.yml" "$SOURCE_DIR/blackbox/blackbox.yml" \
    "$BUNDLE/templates/"
  cat > "$BUNDLE/compose.yml" <<'EOF'
services:
  prometheus:
    image: monitoring-core-prometheus:v2
  alertmanager:
    image: monitoring-core-alertmanager:v2
  blackbox-exporter:
    image: monitoring-core-blackbox-exporter:v2
EOF
  printf '# 예시\nMONITORING_APP_ADDRESS=manager-api:8080\n' > "$BUNDLE/.env.example"
  printf 'images' > "$BUNDLE/core-images.tar"
  (cd "$BUNDLE" && sha256sum compose.yml .env.example core-images.tar core-server.sh core-settings.sh templates/* > SHA256SUMS)
  printf 'TEST CA 1\n' > "$CA_ROOT/etc/ssl/certs/ca-certificates.crt"
  printf 'enabled\n' > "$FAKE/systemctl"
  printf '1761600000\n' > "$FAKE/bundle_time"
  write_fake_docker
  write_fake_systemctl
  write_fake_http curl 22
  write_fake_http wget 1
  PATH="$T/bin:$ORIGINAL_PATH"
  unset CORE_READY_TOOLS
  export PATH FAKE CORE_WAIT_SECONDS=2 CORE_WAIT_INTERVAL=1 CORE_CA_ROOT="$CA_ROOT"
}

write_fake_docker() {
  cat > "$T/bin/docker" <<'EOF'
#!/bin/sh
echo "$*" >> "$FAKE/calls"
case "$1" in
  info) [ ! -f "$FAKE/docker_down" ] ;;
  load) touch "$FAKE/images_loaded" ;;
  compose)
    case " $* " in
      *" up "*)
        # Compose처럼 펼친 설정(env_file, required: true)이 없으면 컨테이너를 만들지 않는다.
        if [ ! -f "$PWD/rendered/core.env" ]; then
          echo "env file $PWD/rendered/core.env not found" >&2
          exit 1
        fi
        touch "$FAKE/running"; printf 'v2\n' > "$FAKE/running_version"; printf '%s\n' "$PWD" > "$FAKE/running_workdir"
        # 다른 프로그램이 쓰는 포트: Docker처럼 그 컨테이너만 만들어 두고 띄우지 못한다.
        if [ -f "$FAKE/port_taken" ]; then
          printf 'created\n' > "$FAKE/status.prometheus"
          echo 'Error response from daemon: Bind for 127.0.0.1:9090 failed: port is already allocated' >&2
          exit 1
        fi
        # Compose처럼 env_file의 값을 컨테이너 환경 변수로, 셸 환경 변수의 값을 호스트 포트로 남긴다. 이 가짜는 .env를 읽지 않는다.
        { printf 'PATH=/bin\n'; grep -v '^#' "$PWD/rendered/core.env"; } > "$FAKE/env.prometheus"
        cp "$FAKE/env.prometheus" "$FAKE/env.blackbox-exporter"
        printf '{"9090/tcp":[{"HostIp":"127.0.0.1","HostPort":"%s"}]}\n' "${CORE_PROMETHEUS_PORT:-9090}" > "$FAKE/ports.prometheus"
        printf '{"9093/tcp":[{"HostIp":"127.0.0.1","HostPort":"%s"}]}\n' "${CORE_ALERTMANAGER_PORT:-9093}" > "$FAKE/ports.alertmanager"
        printf '{"9115/tcp":[{"HostIp":"127.0.0.1","HostPort":"%s"}]}\n' "${CORE_BLACKBOX_PORT:-9115}" > "$FAKE/ports.blackbox-exporter"
        ;;
    esac ;;
  image)
    [ -f "$FAKE/images_loaded" ] || exit 1
    case "$*" in
      *commit-time*running-image*) cat "$FAKE/running_time" ;;
      *commit-time*) cat "$FAKE/bundle_time" ;;
    esac ;;
  inspect)
    [ -f "$FAKE/running" ] || { echo "Error: No such object" >&2; exit 1; }
    for container; do :; done
    case "$*" in
      *Config.Env*) cat "$FAKE/env.$container" 2>/dev/null ;;
      # 포트를 정하지 않은 시험의 컨테이너는 기본 호스트 포트에 묶여 있다.
      *PortBindings*)
        cat "$FAKE/ports.$container" 2>/dev/null || case "$container" in
          prometheus) echo '{"9090/tcp":[{"HostIp":"127.0.0.1","HostPort":"9090"}]}' ;;
          alertmanager) echo '{"9093/tcp":[{"HostIp":"127.0.0.1","HostPort":"9093"}]}' ;;
          *) echo '{"9115/tcp":[{"HostIp":"127.0.0.1","HostPort":"9115"}]}' ;;
        esac ;;
      *working_dir*) cat "$FAKE/running_workdir" ;;
      *image.version*) cat "$FAKE/running_version" ;;
      *image.revision*) echo "abc1234" ;;
      # 계속 다시 시작하는 컨테이너는 볼 때마다 횟수가 는다.
      *RestartCount*)
        count=$(cat "$FAKE/restarts.$container" 2>/dev/null || echo 0)
        if [ -f "$FAKE/crashing.$container" ]; then count=$((count + 1)); printf '%s\n' "$count" > "$FAKE/restarts.$container"; fi
        echo "$count" ;;
      *State.Status*) cat "$FAKE/status.$container" 2>/dev/null || if [ -f "$FAKE/stopped" ]; then echo exited; else echo running; fi ;;
      *.Image*) echo "running-image" ;;
      *State.Running*) if [ -f "$FAKE/stopped" ]; then echo false; else echo true; fi ;;
      *) echo "running" ;;
    esac ;;
esac
EOF
  chmod +x "$T/bin/docker"
}

# 준비 확인 주소를 부르는 가짜 curl, wget이다. 부른 명령을 남기고, not_ready_ports에 적은 호스트 포트는 실패로 답한다.
# 두 번째 인자는 실패할 때의 종료 코드다.
write_fake_http() {
  cat > "$T/bin/$1" <<EOF
#!/bin/sh
for url; do :; done
echo "$1 \$*" >> "\$FAKE/ready_calls"
printf '%s %s' "\${NO_PROXY:-}" "\${no_proxy:-}" > "\$FAKE/proxy_seen"
port=\$(printf '%s' "\$url" | sed 's|^http://[^:/]*:\([0-9]*\)/.*|\1|')
if [ -f "\$FAKE/not_ready_ports" ] && grep -qx "\$port" "\$FAKE/not_ready_ports"; then
  exit $2
fi
exit 0
EOF
  chmod +x "$T/bin/$1"
}

write_fake_systemctl() {
  cat > "$T/bin/systemctl" <<'EOF'
#!/bin/sh
cat "$FAKE/systemctl"
[ "$(cat "$FAKE/systemctl")" = enabled ]
EOF
  chmod +x "$T/bin/systemctl"
}

# 설치 준비를 마친 상태: token과 .env가 있다.
prepared() {
  mkdir -p "$INSTALL/secrets"
  chmod 0700 "$INSTALL/secrets"
  printf 'secret-value-for-test\n' > "$INSTALL/secrets/alertmanager_webhook_token"
  chmod 0644 "$INSTALL/secrets/alertmanager_webhook_token"
  printf 'MONITORING_APP_ADDRESS=manager-api:8080\n' > "$BUNDLE/.env"
}

# 이 묶음 폴더에서 start로 띄운 상태. 부른 명령 기록은 지운다.
started() {
  run start
  rm -f "$FAKE/calls" "$FAKE/ready_calls"
}

# 다른 버전의 코어가 떠 있는 상태. 두 번째 인자는 그 이미지의 커밋 시각 label 값이다.
running_other() {
  touch "$FAKE/running" "$FAKE/images_loaded"
  printf '%s\n' "$1" > "$FAKE/running_version"
  printf '%s\n' "$2" > "$FAKE/running_time"
  printf '%s\n' "$3" > "$FAKE/running_workdir"
}

run() {
  OUT=$(cd "$BUNDLE" && sh ./core-server.sh "$@" 2>&1 < "${STDIN:-/dev/null}")
  RC=$?
}

# 확인 질문은 터미널에서만 묻는다. BusyBox script로 가상 터미널을 만들어 답을 넣는다.
# script는 입력이 끝나면 안의 명령을 기다리지 않고 끝나므로, 답을 넣은 뒤 입력을 잠시 열어 둔다.
run_in_terminal() {
  answer=$1
  shift
  OUT=$(cd "$BUNDLE" && { printf '%s\n' "$answer"; sleep 3; } | script -q -c "sh ./core-server.sh $*" /dev/null 2>&1)
  RC=$?
}

check() {
  if eval "$2"; then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
    printf '실패: %s\n  조건: %s\n  종료 코드: %s\n  출력:\n%s\n' "$1" "$2" "$RC" "$OUT" | sed 's/^/    /'
  fi
}

contains() { case "$OUT" in *"$1"*) return 0 ;; esac; return 1; }
called() { [ -f "$FAKE/calls" ] && grep -q "$1" "$FAKE/calls"; }
mode() { ls -ld "$1" | cut -c1-10; }
ready_called() { [ -f "$FAKE/ready_calls" ] && grep -q "$1" "$FAKE/ready_calls"; }
# 펼친 Prometheus 설정이 HTTP SD를 부르는 주소
rendered_address() { sed -n 's|^ *- url: http://\(.*\)/api/prometheus/sd/blackbox$|\1|p' "$RENDERED/prometheus.yml"; }

ORIGINAL_PATH=$PATH

# --- 명령과 Docker ---

setup
run deploy
check '알 수 없는 작업이면 쓸 수 있는 작업을 보이고 실패한다' \
  '[ $RC -ne 0 ] && contains "install" && contains "rotate-token" && ! called "^compose"'

setup; prepared; touch "$FAKE/docker_down"
run start
check 'Docker가 꺼져 있으면 알리고 아무것도 하지 않는다' \
  '[ $RC -ne 0 ] && contains "Docker에 연결하지 못했습니다" && ! called load && ! called "^compose"'

# --- 체크섬 ---

setup; prepared; printf 'broken' > "$BUNDLE/core-images.tar"
run start
check '묶음 파일이 바뀌었으면 그 파일을 알리고 불러오거나 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "core-images.tar" && contains "체크섬" && ! called load && ! called "^compose"'

# --- 설정 파일 ---

setup; prepared; rm "$BUNDLE/.env"
run start
check '설정 파일이 없으면 설치 준비를 안내하고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "sh core-server.sh install" && ! called "^compose"'

for address in '' 'http://manager-api:8080' 'manager-api' 'a;touch${IFS}x:80' 'manager-api:70000'; do
  setup; prepared; printf 'MONITORING_APP_ADDRESS=%s\n' "$address" > "$BUNDLE/.env"
  run start
  check "관리자 서버 주소가 '$address'이면 거부한다" \
    '[ $RC -ne 0 ] && contains "관리자 서버 주소" && ! called "^compose" && [ ! -e "$BUNDLE/x" ]'
done

setup; prepared
OUT=$(cd "$BUNDLE" && env "MONITORING_APP_ADDRESS=x
manager-api:8080" sh ./core-server.sh start 2>&1 < /dev/null)
RC=$?
check '셸 환경 변수의 관리자 서버 주소가 여러 줄이면 한 줄이 맞아도 거부한다' \
  '[ $RC -ne 0 ] && contains "관리자 서버 주소" && ! called "^compose"'

setup; prepared
OUT=$(cd "$BUNDLE" && env "MONITORING_APP_ADDRESS=manager-api:8080
" sh ./core-server.sh start 2>&1 < /dev/null)
RC=$?
check '셸 환경 변수의 관리자 서버 주소 끝에 줄바꿈이 있으면 검사한 값 그대로 설정에 펼친다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && [ "$(rendered_address)" = "manager-api:8080" ]'

setup; prepared; printf "MONITORING_APP_ADDRESS='manager-api:8080' # 관리자 서버\n" > "$BUNDLE/.env"
run start
check '설정 파일의 관리자 서버 주소는 따옴표와 주석을 뺀 값으로 설정에 펼친다' \
  '[ $RC -eq 0 ] && [ "$(rendered_address)" = "manager-api:8080" ]'

setup; prepared; printf 'MONITORING_APP_ADDRESS=manager-api:8080\n$(touch %s/injected)\n' "$T" > "$BUNDLE/.env"
run start
check '설정 파일의 셸 명령은 실행하지 않는다' '[ ! -e "$T/injected" ]'

# --- token ---

setup; prepared; rm "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'token 파일이 없으면 알리고 그 자리에 폴더를 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "token 파일이 없습니다" && [ ! -e "$INSTALL/secrets/alertmanager_webhook_token" ] && ! called "^compose"'

setup; prepared; : > "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'token 파일이 비어 있으면 알리고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "비어 있습니다" && ! called "^compose"'

setup; prepared; rm "$INSTALL/secrets/alertmanager_webhook_token"; mkdir "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'token 자리에 폴더가 있으면 알리고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "폴더가 있습니다" && ! called "^compose"'

setup; prepared; chmod 0600 "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'token 파일을 다른 사용자가 읽을 수 없으면 알리고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "읽을 수 없습니다" && contains "0644" && ! called "^compose"'

# Amazon Linux 2023 컨테이너 이미지처럼 find가 없는 서버. 시험 환경의 find를 숨길 수 없어, 없을 때처럼 실패하는 가짜를 앞에 둔다.
no_find() {
  printf '#!/bin/sh\necho "find: command not found" >&2\nexit 127\n' > "$T/bin/find"
  chmod +x "$T/bin/find"
}

setup; prepared; no_find
run start
check 'find가 없는 서버에서도 컨테이너가 읽을 수 있는 token이면 띄운다' '[ $RC -eq 0 ] && called "^compose.* up"'

setup; prepared; no_find; chmod 0600 "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'find가 없는 서버에서도 다른 사용자가 읽을 수 없는 token이면 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "읽을 수 없습니다" && ! called "^compose"'

setup; prepared; rm -r "$INSTALL/secrets"; mkdir "$BUNDLE/secrets"; printf 'old\n' > "$BUNDLE/secrets/alertmanager_webhook_token"
run start
check '묶음 폴더 안에 token이 있으면 설치 폴더로 옮기라고 안내한다' \
  '[ $RC -ne 0 ] && contains "묶음 폴더 안에 token이 있습니다" && ! called "^compose"'

setup; prepared; mkdir "$T/custom"; mv "$INSTALL/secrets/alertmanager_webhook_token" "$T/custom/"
printf 'MONITORING_APP_ADDRESS=manager-api:8080\nCORE_SECRETS_DIR=%s\n' "$T/custom" > "$BUNDLE/.env"
run start
check '설정 파일에 token 폴더를 적었으면 그 폴더를 쓴다' '[ $RC -eq 0 ] && called "^compose"'

# --- 띄우기 ---

setup; prepared
run start
check '준비를 마쳤으면 불러오고 띄운 뒤 버전을 보인다' \
  '[ $RC -eq 0 ] && called load && called "^compose.* up" && contains "v2" && ! contains "secret-value-for-test"'

setup; prepared; touch "$FAKE/images_loaded"
run start
check '이미지가 이미 있으면 다시 불러오지 않는다' '[ $RC -eq 0 ] && ! called load'

setup; prepared; printf '9090\n' > "$FAKE/not_ready_ports"
run start
check '정해진 시간 안에 준비되지 않으면 그 컨테이너와 로그 명령만 보인다' \
  '[ $RC -ne 0 ] && contains "정상 상태가 되지 않았습니다" && contains "docker logs prometheus" && ! contains "docker logs alertmanager"'

setup; prepared; running_other v3 1761700000 "$INSTALL/monitoring-core-v3"
run start
check '이전 버전으로 바꾸는데 터미널이 아니면 바꾸지 않는다' \
  '[ $RC -ne 0 ] && contains "v3" && ! called "^compose.* up"'

setup; prepared; running_other v3 1761700000 "$INSTALL/monitoring-core-v3"
run_in_terminal n start
check '이전 버전으로 바꿀지 물었을 때 n이면 바꾸지 않는다' \
  'contains "계속할까요" && contains "코어는 바꾸지 않았습니다" && ! called "^compose.* up"'

setup; prepared; running_other v3 1761700000 "$INSTALL/monitoring-core-v3"
run_in_terminal y start
check '이전 버전으로 바꿀지 물었을 때 y이면 바꾼다' \
  '[ $RC -eq 0 ] && contains "계속할까요" && called "^compose.* up"'

setup; prepared; running_other v1 1761500000 "$INSTALL/monitoring-core-v1"
run start
check '새 버전으로 바꿀 때는 묻지 않고 떠 있는 버전과 새 버전을 보인다' \
  '[ $RC -eq 0 ] && contains "v1" && ! contains "계속할까요" && called "^compose.* up"'

setup; prepared; running_other v0 '<no value>' "$INSTALL/monitoring-core-v0"
run start
check '떠 있는 이미지에 커밋 시각이 없으면 예전 버전으로 보고 묻지 않는다' \
  '[ $RC -eq 0 ] && called "^compose.* up"'

setup; prepared; running_other v3 1761700000 "$INSTALL/monitoring-core-v3"; printf '<no value>\n' > "$FAKE/bundle_time"
run start
check '띄울 이미지에 커밋 시각이 없으면 이전 버전으로 보고 터미널이 아니면 바꾸지 않는다' \
  '[ $RC -ne 0 ] && ! called "^compose.* up"'

setup; prepared; printf 'disabled\n' > "$FAKE/systemctl"
run start
check '서버가 켜질 때 Docker가 켜지지 않으면 경고하고 띄우기는 마친다' \
  '[ $RC -eq 0 ] && contains "자동으로 켜지지 않습니다" && contains "systemctl enable docker"'

setup; prepared; rm "$T/bin/systemctl"
run start
check 'systemctl이 없으면 자동 시작을 확인하지 못했다고 알린다' \
  '[ $RC -eq 0 ] && contains "확인하지 못했습니다"'

# --- 설치 준비 ---

setup; STDIN="$T/answer"; printf 'manager-api:8080\n' > "$STDIN"
run install
token="$INSTALL/secrets/alertmanager_webhook_token"
check '처음 설치하면 token 폴더를 운영자만 열 수 있게 만든다' '[ $RC -eq 0 ] && [ "$(mode "$INSTALL/secrets")" = drwx------ ]'
check '처음 설치하면 token 파일을 컨테이너가 읽을 수 있게 만든다' '[ "$(mode "$token")" = -rw-r--r-- ]'
check '처음 설치하면 64자리 16진수 token을 만들고 출력하지 않는다' \
  '[ "$(wc -c < "$token" | tr -d " ")" -ge 64 ] && grep -Eq "^[0-9a-f]{64}$" "$token" && ! contains "$(cat "$token")"'
check '처음 설치하면 입력한 주소로 설정 파일을 만든다' 'grep -q "^MONITORING_APP_ADDRESS=manager-api:8080$" "$BUNDLE/.env"'
unset STDIN

setup; prepared; before=$(cat "$INSTALL/secrets/alertmanager_webhook_token" "$BUNDLE/.env")
run install
check '설치 준비를 다시 하면 token과 설정 파일을 덮어쓰지 않는다' \
  '[ $RC -eq 0 ] && contains "이미 있습니다" && [ "$(cat "$INSTALL/secrets/alertmanager_webhook_token" "$BUNDLE/.env")" = "$before" ]'

setup; prepared; rm "$BUNDLE/.env"; mkdir "$INSTALL/monitoring-core-v1"
printf 'MONITORING_APP_ADDRESS=old-admin:9000\n' > "$INSTALL/monitoring-core-v1/.env"
running_other v1 '2026-10-27T00:00:00Z' "$INSTALL/monitoring-core-v1"
run install
check '갱신할 때 떠 있는 버전의 폴더에서 설정을 가져온다' \
  '[ $RC -eq 0 ] && grep -q "old-admin:9000" "$BUNDLE/.env" && contains "monitoring-core-v1"'

setup; prepared; rm "$BUNDLE/.env"; mkdir -p "$T/elsewhere"
printf 'MONITORING_APP_ADDRESS=other:1\n' > "$T/elsewhere/.env"
running_other v1 '2026-10-27T00:00:00Z' "$T/elsewhere"
STDIN="$T/answer"; printf 'manager-api:8080\n' > "$STDIN"
run install
unset STDIN
check '떠 있는 폴더가 설치 폴더 밖이면 가져오지 않고 주소를 묻는다' \
  '[ $RC -eq 0 ] && contains "찾지 못했습니다" && grep -q "manager-api:8080" "$BUNDLE/.env"'

setup; prepared; rm "$BUNDLE/.env"; before=$(cat "$INSTALL/secrets/alertmanager_webhook_token")
STDIN="$T/answer"; printf 'manager-api:8080\n' > "$STDIN"
run install
unset STDIN
check '떠 있는 코어가 없으면 주소를 묻고 token은 새로 만들지 않는다' \
  '[ $RC -eq 0 ] && contains "찾지 못했습니다" && [ "$(cat "$INSTALL/secrets/alertmanager_webhook_token")" = "$before" ]'

setup; STDIN="$T/answer"; printf 'bad address\n' > "$STDIN"
run install
unset STDIN
check '설치 준비에서 잘못된 주소를 넣으면 설정 파일을 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "관리자 서버 주소" && [ ! -e "$BUNDLE/.env" ]'

# --- 설정 파일 읽기 ---

setup; prepared; printf "export MONITORING_APP_ADDRESS='manager-api:8080' # 관리자 서버\n" > "$BUNDLE/.env"
run start
check 'export, 작은따옴표, 줄 끝 주석이 있는 설정 파일을 Compose처럼 읽는다' '[ $RC -eq 0 ] && called "^compose.* up"'

setup; prepared; mkdir "$T/fromshell"; mv "$INSTALL/secrets/alertmanager_webhook_token" "$T/fromshell/"
OUT=$(cd "$BUNDLE" && CORE_SECRETS_DIR="$T/fromshell" sh ./core-server.sh start 2>&1 < /dev/null)
RC=$?
check '셸 환경 변수에 token 폴더가 있으면 Compose처럼 그 값을 쓴다' '[ $RC -eq 0 ] && called "^compose.* up"'

# --- 예전 방식(token이 묶음 폴더 안)으로 설치한 서버에서 갱신 ---

old_bundle() {
  mkdir -p "$INSTALL/monitoring-core-v1"
  printf 'MONITORING_APP_ADDRESS=old-admin:9000\n%s' "${1:-}" > "$INSTALL/monitoring-core-v1/.env"
  running_other v1 1761500000 "$INSTALL/monitoring-core-v1"
}

setup; prepared; rm -r "$INSTALL/secrets"; rm "$BUNDLE/.env"; old_bundle
mkdir "$INSTALL/monitoring-core-v1/secrets"; printf 'old\n' > "$INSTALL/monitoring-core-v1/secrets/alertmanager_webhook_token"
run install
check '이전 묶음 폴더 안에 token이 있으면 새 token을 만들지 않고 옮기라고 안내한다' \
  '[ $RC -ne 0 ] && contains "monitoring-core-v1/secrets/alertmanager_webhook_token" && [ ! -e "$INSTALL/secrets" ] && [ ! -e "$BUNDLE/.env" ]'
check 'token을 옮길 곳은 ../ 없이 설치 폴더의 실제 경로로 보인다' \
  'contains "$(cd "$INSTALL" && pwd -P)/secrets/로 옮기고" && ! contains "/../"'

setup; prepared; mkdir "$T/fixed"; mv "$INSTALL/secrets/alertmanager_webhook_token" "$T/fixed/"; rm "$BUNDLE/.env"
old_bundle "CORE_SECRETS_DIR=$T/fixed
"
run install
check '가져온 설정에 token 폴더가 적혀 있으면 그 token을 쓰고 새로 만들지 않는다' \
  '[ $RC -eq 0 ] && [ ! -e "$INSTALL/secrets/alertmanager_webhook_token" ] && grep -q "old-admin" "$BUNDLE/.env"'

setup; prepared; rm "$INSTALL/secrets/alertmanager_webhook_token" "$BUNDLE/.env"
running_other v1 1761500000 "$T/elsewhere"; touch "$FAKE/stopped"
STDIN="$T/answer"; printf 'manager-api:8080\n' > "$STDIN"
run install
unset STDIN
check '멈춘 코어가 있어도 token이 없으면 새로 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "만들지 않았습니다" && [ ! -e "$INSTALL/secrets/alertmanager_webhook_token" ]'

setup; touch "$FAKE/docker_down"; STDIN="$T/answer"; printf 'manager-api:8080\n' > "$STDIN"
run install
unset STDIN
check 'Docker가 꺼져 있으면 설치 준비에서 아무것도 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "Docker에 연결하지 못했습니다" && [ ! -e "$INSTALL/secrets" ] && [ ! -e "$BUNDLE/.env" ]'

setup; mkdir "$T/shared"; chmod 0755 "$T/shared"
printf 'MONITORING_APP_ADDRESS=manager-api:8080\nCORE_SECRETS_DIR=%s\n' "$T/shared" > "$BUNDLE/.env"
run install
check 'token을 이미 있는 폴더에 만들면 그 폴더의 권한을 바꾸지 않는다' \
  '[ $RC -eq 0 ] && [ "$(mode "$T/shared")" = drwxr-xr-x ] && [ -s "$T/shared/alertmanager_webhook_token" ]'

# --- token 바꾸기 ---

setup; prepared; started; before=$(cat "$TOKEN")
run rotate-token
check 'token을 바꾸면 새 값을 쓰고 출력하지 않는다' \
  '[ $RC -eq 0 ] && [ "$(cat "$TOKEN")" != "$before" ] && grep -Eq "^[0-9a-f]{64}$" "$TOKEN" && ! contains "$(cat "$TOKEN")"'
check 'token을 바꾸면 Alertmanager를 다시 만든다' 'called "^compose.*--force-recreate alertmanager"'

setup; prepared; mkdir "$INSTALL/monitoring-core-v1"; running_other v1 1761500000 "$INSTALL/monitoring-core-v1"; before=$(cat "$TOKEN")
run rotate-token
check '코어를 띄운 폴더가 아니면 token을 바꾸지 않는다' \
  '[ $RC -ne 0 ] && contains "monitoring-core-v1" && [ "$(cat "$TOKEN")" = "$before" ] && ! called "^compose"'

setup; prepared; rm "$BUNDLE/.env"; before=$(cat "$TOKEN")
run rotate-token
check '사전 검사에 실패하면 token을 바꾸지 않는다' '[ $RC -ne 0 ] && [ "$(cat "$TOKEN")" = "$before" ]'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; touch "$FAKE/stopped"; before=$(cat "$TOKEN")
run rotate-token
check '코어가 멈춰 있으면 token만 바꾸고 컨테이너를 띄우지 않는다' \
  '[ $RC -eq 0 ] && [ "$(cat "$TOKEN")" != "$before" ] && ! called "^compose" && contains "다음 start"'

setup; prepared; started; printf '9093\n' > "$FAKE/not_ready_ports"
run rotate-token
check 'token을 바꾼 뒤 Alertmanager가 준비되지 않으면 로그 명령을 보인다' \
  '[ $RC -ne 0 ] && contains "docker logs alertmanager"'

setup; prepared; started
run rotate-token
check 'token을 바꾸면 Alertmanager의 준비 확인 주소만 부른다' \
  '[ $RC -eq 0 ] && ready_called "127.0.0.1:9093/-/ready" && ! ready_called ":9090/"'

# Alertmanager만 다시 만드는데 다른 설정까지 바꾸면 Prometheus와 Blackbox Exporter가 다음 다시 시작 때 확인 없이 새 설정을 읽는다.
setup; prepared; started; before=$(cat "$TOKEN")
printf 'CORE_RETENTION_DAYS=30\n' >> "$BUNDLE/.env"; expected=$(cat "$RENDERED/prometheus.yml")
run rotate-token
check '지난 띄우기 뒤 설정 값을 바꿨으면 token을 바꾸지 않고 start를 먼저 안내한다' \
  '[ $RC -ne 0 ] && contains "sh core-server.sh start" && [ "$(cat "$TOKEN")" = "$before" ] && [ "$(cat "$RENDERED/prometheus.yml")" = "$expected" ] && ! called "^compose"'

setup; prepared; printf 'x' >> "$BUNDLE/templates/alertmanager.yml"; running_other v2 1761600000 "$BUNDLE"; before=$(cat "$TOKEN")
run rotate-token
check '묶음의 템플릿이 바뀌었으면 token을 바꾸지 않는다' \
  '[ $RC -ne 0 ] && contains "체크섬" && [ "$(cat "$TOKEN")" = "$before" ]'

setup; prepared
OUT=$(cd "$BUNDLE" && http_proxy=http://proxy.invalid:3128 HTTP_PROXY=http://proxy.invalid:3128 sh ./core-server.sh start 2>&1 < /dev/null)
RC=$?
check '셸에 프록시가 있어도 준비 확인은 127.0.0.1을 프록시 없이 부른다' \
  '[ $RC -eq 0 ] && [ "$(cat "$FAKE/proxy_seen")" = "127.0.0.1 127.0.0.1" ]'

# --- 서버에서 바꾸는 코어 설정 값(요구사항 0013) ---

# 설정 파일에 줄을 더한다. 인자 하나가 한 줄이다.
with_settings() {
  for line in "$@"; do
    printf '%s\n' "$line" >> "$BUNDLE/.env"
  done
}

# 셸 환경 변수 하나를 더해 실행한다. 값에 줄바꿈도 넣을 수 있다.
run_with_env() {
  assignment=$1
  shift
  OUT=$(cd "$BUNDLE" && env "$assignment" sh ./core-server.sh "$@" 2>&1 < /dev/null)
  RC=$?
}

# 떠 있는 코어의 prometheus 컨테이너가 받은 보관 값. 빈 인자는 그 변수가 없는 것이다(이 기능 전의 컨테이너).
running_retention() {
  printf 'PATH=/bin\n' > "$FAKE/env.prometheus"
  [ -z "$1" ] || printf 'CORE_RETENTION_DAYS=%s\n' "$1" >> "$FAKE/env.prometheus"
  [ -z "$2" ] || printf 'CORE_RETENTION_SIZE_GB=%s\n' "$2" >> "$FAKE/env.prometheus"
}

# 틀린 값이 보이고, 불러오거나 띄우지 않았는지 본다. 조건은 check가 그 자리에서 eval한다.
REJECTED='[ $RC -ne 0 ] && contains "$name" && contains "$value" && contains "$range" && contains ".env의 $name" && contains "$extra" && ! called load && ! called "^compose"'

# 이름, 값, 허용 범위, 함께 보여야 할 글
reject_in_env_file() {
  name=$1 value=$2 range=$3 extra=${4:-}
  setup; prepared; with_settings "$name=$value"
  run start
  check "설정 파일의 $name 값이 '$value'이면 이름, 값, 허용 범위, 고치는 방법을 보이고 띄우지 않는다" "$REJECTED"
}

for value in 15d abc -1 7.5 015 '1 5' '15 --web.enable-admin-api' '３０'; do
  reject_in_env_file CORE_RETENTION_DAYS "$value" '1~365' '앞에 0을 붙이지'
done
reject_in_env_file CORE_RETENTION_DAYS 0 '1~365'
reject_in_env_file CORE_RETENTION_DAYS 366 '1~365'
reject_in_env_file CORE_RETENTION_SIZE_GB 50GB '1 이상'
reject_in_env_file CORE_RETENTION_SIZE_GB 0 '1 이상'
reject_in_env_file CORE_RETENTION_SIZE_GB 1000000000 '999999999'
for value in 5s '5|g' '5&' 'prober:'; do
  reject_in_env_file CORE_PROBE_TIMEOUT_SECONDS "$value" '1~9'
done
reject_in_env_file CORE_PROBE_TIMEOUT_SECONDS 0 '1~9' '수집 제한 시간'
reject_in_env_file CORE_PROBE_TIMEOUT_SECONDS 10 '1~9' '수집 제한 시간'
reject_in_env_file CORE_PROMETHEUS_PORT 65536 '1024~65535'
reject_in_env_file CORE_ALERTMANAGER_PORT 80 '1024~65535'
reject_in_env_file CORE_BLACKBOX_PORT 1023 '1024~65535'
reject_in_env_file CORE_PROMETHEUS_PORT '0.0.0.0:9090' '1024~65535'
reject_in_env_file CORE_BLACKBOX_PORT '9115-9120' '1024~65535'

setup; prepared; with_settings 'CORE_RETENTION_DAYS=$(touch '"$T"'/injected)' 'CORE_PROBE_TIMEOUT_SECONDS=`touch '"$T"'/injected`'
run start
check '설정 값에 든 셸 명령은 실행하지 않고 형식 오류로 거부한다' \
  '[ $RC -ne 0 ] && [ ! -e "$T/injected" ] && contains "CORE_RETENTION_DAYS" && contains "CORE_PROBE_TIMEOUT_SECONDS" && ! called "^compose"'

name=CORE_PROBE_TIMEOUT_SECONDS value='prober: tcp' range='1~9' extra=''
setup; prepared
run_with_env "CORE_PROBE_TIMEOUT_SECONDS=5
prober: tcp" start
check '셸 환경 변수의 값에 줄바꿈과 YAML을 넣으면 거부한다' "$REJECTED"

setup; prepared; with_settings CORE_RETENTION_DAYS=30
name=CORE_RETENTION_DAYS value=abc range='1~365' extra=''
run_with_env CORE_RETENTION_DAYS=abc start
check '셸 환경 변수가 설정 파일 값을 덮으면 셸 환경 변수의 값을 검사한다' "$REJECTED"

setup; prepared; with_settings CORE_RETENTION_DAYS=abc
run_with_env CORE_RETENTION_DAYS=30 start
check '셸 환경 변수의 값이 맞으면 설정 파일의 틀린 값과 관계없이 그 값으로 띄운다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && grep -q "^CORE_RETENTION_DAYS=30$" "$FAKE/env.prometheus"'

setup; prepared; with_settings 'CORE_RETENTION_DAYS=abc' 'CORE_PROBE_TIMEOUT_SECONDS=10'
run start
check '틀린 값이 여럿이면 모두 한 번에 보인다' \
  '[ $RC -ne 0 ] && contains "CORE_RETENTION_DAYS" && contains "CORE_PROBE_TIMEOUT_SECONDS" && ! called "^compose"'

# 호스트 포트 겹침: 겹친 포트와 그 포트를 쓰는 구성 요소를 보인다.
for pair in 'CORE_ALERTMANAGER_PORT=9090 Prometheus Alertmanager 9090' \
            'CORE_BLACKBOX_PORT=9090 Prometheus Blackbox 9090' \
            'CORE_ALERTMANAGER_PORT=9115 Alertmanager Blackbox 9115'; do
  set -- $pair
  setup; prepared; with_settings "$1"
  run start
  first=$2 second=$3 port=$4
  check "호스트 포트가 겹치면($1) 겹친 포트와 두 구성 요소를 보이고 띄우지 않는다" \
    '[ $RC -ne 0 ] && contains "$port" && contains "$first" && contains "$second" && ! called load && ! called "^compose"'
done
setup; prepared; with_settings CORE_PROMETHEUS_PORT=19000 CORE_ALERTMANAGER_PORT=19000 CORE_BLACKBOX_PORT=19000
run start
check '세 호스트 포트가 모두 같으면 세 구성 요소를 보이고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "19000" && contains "Prometheus" && contains "Alertmanager" && contains "Blackbox" && ! called "^compose"'

setup; prepared; with_settings CORE_RETENTION_DAYS= CORE_RETENTION_SIZE_GB= CORE_PROBE_TIMEOUT_SECONDS= \
  CORE_PROMETHEUS_PORT= CORE_ALERTMANAGER_PORT= CORE_BLACKBOX_PORT=
run start
check '값이 모두 비어 있으면 기본값으로 띄우고 모두 기본값이라고 보인다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && contains "모두 기본값"'

setup; prepared
run start
check '이 기능 전의 설정 파일로 띄우면 모두 기본값이라고 보인다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && contains "모두 기본값" && ! contains "기본값과 다른"'

setup; prepared; with_settings 'CORE_RETENTION_DAYS=" 30 "' CORE_PROMETHEUS_PORT=19090
run start
check '허용 범위 안의 값이면 앞뒤 공백을 지운 값으로 띄우고 기본값과 다른 값만 보인다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && contains "30일" && contains "19090" && ! contains "확인 제한 시간" && ! contains "모두 기본값"'

setup; prepared; with_settings CORE_RETENTION_DAYS=365 CORE_RETENTION_SIZE_GB=999999999 CORE_PROBE_TIMEOUT_SECONDS=9 \
  CORE_PROMETHEUS_PORT=65535 CORE_ALERTMANAGER_PORT=1024 CORE_BLACKBOX_PORT=1025
run start
check '허용 범위 양 끝의 값이면 띄운다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && contains "365일" && contains "999999999GB" && contains "9초" && contains "65535"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; with_settings CORE_BLACKBOX_PORT=80; before=$(cat "$TOKEN")
run rotate-token
check 'token을 바꿀 때도 틀린 설정 값이면 token을 바꾸지 않고 Alertmanager를 다시 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "CORE_BLACKBOX_PORT" && [ "$(cat "$TOKEN")" = "$before" ] && ! called "^compose"'

setup; prepared; touch "$FAKE/port_taken"
run start
check '다른 프로그램이 쓰는 포트라 띄우지 못하면 포트를 바꾸는 방법, 정상이 아닌 컨테이너, 로그 명령을 보인다' \
  '[ $RC -ne 0 ] && contains "port is already allocated" && contains "CORE_PROMETHEUS_PORT" && contains "prometheus: created" && contains "docker logs prometheus" && ! contains "docker logs alertmanager"'

# --- 기록 보관 값을 줄일 때의 확인 ---

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 100; with_settings CORE_RETENTION_DAYS=15 CORE_RETENTION_SIZE_GB=100
run start
check '보관 기간을 줄이는데 터미널이 아니면 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "30일" && contains "15일" && contains "되돌릴 수 없습니다" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 100; with_settings CORE_RETENTION_DAYS=30 CORE_RETENTION_SIZE_GB=50
run start
check '보관 용량을 줄이는데 터미널이 아니면 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "100GB" && contains "50GB" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''; with_settings CORE_RETENTION_DAYS=30 CORE_RETENTION_SIZE_GB=50
run start
check '제한이 없던 보관 용량을 처음 정하면 줄임으로 보고 터미널이 아니면 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "제한 없음" && contains "50GB" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention '' ''; with_settings CORE_RETENTION_DAYS=10
run start
check '보관 값이 없는 예전 컨테이너는 15일로 보고, 그보다 줄이면 터미널이 아니면 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "15일" && contains "10일" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention '' ''; with_settings CORE_RETENTION_DAYS=30
run start
check '보관 값이 없는 예전 컨테이너에서 보관 기간을 늘리면 묻지 않고 띄운다' \
  '[ $RC -eq 0 ] && ! contains "계속할까요" && called "^compose.* up"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 100; with_settings CORE_RETENTION_DAYS=30 CORE_RETENTION_SIZE_GB=100
run start
check '보관 값이 떠 있는 코어와 같으면 묻지 않고 띄운다' \
  '[ $RC -eq 0 ] && ! contains "계속할까요" && called "^compose.* up"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 50; with_settings CORE_RETENTION_DAYS=30
run start
check '보관 용량 제한을 없애면 줄임이 아니므로 묻지 않고 띄운다' \
  '[ $RC -eq 0 ] && ! contains "계속할까요" && called "^compose.* up"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''; touch "$FAKE/stopped"
run start
check '멈춘 코어의 보관 기간보다 줄여도 확인을 묻는다' '[ $RC -ne 0 ] && contains "되돌릴 수 없습니다" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
run_in_terminal n start
check '보관 기간을 줄일지 물었을 때 n이면 띄우지 않고 정상 종료한다' \
  '[ $RC -eq 0 ] && contains "계속할까요" && contains "코어는 바꾸지 않았습니다" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
run_in_terminal y start
check '보관 기간을 줄일지 물었을 때 y이면 띄운다' \
  '[ $RC -eq 0 ] && contains "계속할까요" && called "^compose.* up"'

# --- 상태 보기 ---

setup; running_other v1 1761500000 "$INSTALL/monitoring-core-v1"
run status
check '상태를 보면 코어의 버전, 커밋, 컨테이너 상태와 준비 여부, 묶음과의 버전 차이를 보인다' \
  '[ $RC -eq 0 ] && contains "떠 있는 버전: v1" && contains "abc1234" && contains "prometheus: running, 준비됨" && contains "버전(v2)"'

setup; running_other v2 1761600000 "$BUNDLE"; printf '9093\n' > "$FAKE/not_ready_ports"
run status
check '상태를 보면 준비되지 않은 컨테이너를 준비되지 않았다고 보인다' \
  '[ $RC -eq 0 ] && contains "alertmanager: running, 준비되지 않음" && contains "prometheus: running, 준비됨"'

setup; running_other v2 1761600000 "$BUNDLE"
printf '{"9115/tcp":[{"HostIp":"127.0.0.1","HostPort":"19115"}]}\n' > "$FAKE/ports.blackbox-exporter"
run status
check '상태를 보면 바꾼 호스트 포트로 준비 확인 주소를 부른다' \
  '[ $RC -eq 0 ] && ready_called "http://127.0.0.1:19115/-/healthy" && ready_called "http://127.0.0.1:9090/-/ready"'

setup; running_other v2 1761600000 "$BUNDLE"; touch "$FAKE/stopped"
run status
check '코어가 멈춰 있으면 떠 있다고 보이지 않고 준비 확인 주소를 부르지 않는다' \
  'contains "멈춰 있습니다" && ! contains "떠 있는 버전" && contains "prometheus: exited" && [ ! -f "$FAKE/ready_calls" ]'

setup; running_other v2 1761600000 "$BUNDLE"
OUT=$(cd "$BUNDLE" && CORE_READY_TOOLS="" sh ./core-server.sh status 2>&1 < /dev/null)
RC=$?
check 'curl과 wget이 없으면 상태에서 준비 여부를 확인하지 못했다고 보인다' \
  '[ $RC -eq 0 ] && contains "prometheus: running, 준비 확인 못 함"'

setup
run status
check '코어가 없으면 없다고 보인다' '[ $RC -eq 0 ] && contains "코어가 없습니다"'

setup; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
printf '{"9090/tcp":[{"HostIp":"127.0.0.1","HostPort":"19090"}]}\n' > "$FAKE/ports.prometheus"
run status
check '상태를 보면 컨테이너가 받은 값 가운데 기본값과 다른 것만 보인다' \
  '[ $RC -eq 0 ] && contains "기본값과 다른 설정 값" && contains "30일" && contains "127.0.0.1:19090" && ! contains "확인 제한 시간"'

setup; running_other v2 1761600000 "$BUNDLE"; touch "$FAKE/stopped"
printf '{"9115/tcp":[{"HostIp":"127.0.0.1","HostPort":"19115"}]}\n' > "$FAKE/ports.blackbox-exporter"
run status
check '코어가 멈춰 있어도 바꾼 포트를 보인다' '[ $RC -eq 0 ] && contains "멈춰 있습니다" && contains "127.0.0.1:19115"'

setup; running_other v2 1761600000 "$BUNDLE"
run status
check '상태를 보면 값이 모두 기본값일 때 기본값이라고 보인다' '[ $RC -eq 0 ] && contains "모두 기본값"'

setup; running_other v2 1761600000 "$BUNDLE"; printf 'CORE_PROBE_TIMEOUT_SECONDS=10\n' > "$FAKE/env.blackbox-exporter"
run status
check '컨테이너가 받은 값이 틀리면 기본값이라고 하지 않고 그 값을 올바르지 않다고 보인다' \
  '[ $RC -eq 0 ] && contains "확인 제한 시간 '"'"'10'"'"'(올바르지 않음)" && ! contains "모두 기본값"'

# --- 설정 펼치기(요구사항 0015) ---

# 이전에 펼친 설정이 있는 상태와, 그 설정이 그대로인지 보는 조건
previously_rendered() {
  mkdir -p "$RENDERED"
  for name in prometheus.yml alertmanager.yml blackbox.yml ca-certificates.crt core.env; do
    printf 'previous\n' > "$RENDERED/$name"
  done
}
unchanged() {
  [ "$(ls -A "$RENDERED" | wc -l | tr -d ' ')" -eq 5 ] && [ "$(cat "$RENDERED"/* | sort -u)" = previous ]
}
config_hash() { sed -n 's/^CORE_CONFIG_SHA256=//p' "$RENDERED/core.env"; }

setup; prepared
run render
check '설정을 펼치면 검사한 주소와 기본값으로 세 설정을 rendered/에 쓰고 이미지와 컨테이너는 바꾸지 않는다' \
  '[ $RC -eq 0 ] && [ "$(rendered_address)" = "manager-api:8080" ] && grep -q "^      time: 15d$" "$RENDERED/prometheus.yml" && grep -q "^      size: 0B$" "$RENDERED/prometheus.yml" && [ "$(grep -c "^    timeout: 5s$" "$RENDERED/blackbox.yml")" -eq 3 ] && grep -q "http://manager-api:8080/api/integrations/alertmanager/webhook" "$RENDERED/alertmanager.yml" && ! called "^compose" && ! called load'
check '설정을 펼치면 Compose로 띄우는 방법과 .env를 바꾸면 다시 펼쳐야 한다는 것을 보인다' \
  'contains "docker compose up -d" && contains "다시 실행"'
check '펼친 설정에 자리표시와 token 값이 남지 않는다' \
  '! grep -rq "@[A-Z][A-Z_]*@" "$RENDERED" && ! grep -rq "secret-value-for-test" "$RENDERED" && ! contains "secret-value-for-test"'
check '펼친 설정은 운영자만 고칠 수 있고 컨테이너 사용자가 읽을 수 있다' \
  '[ "$(mode "$RENDERED")" = drwxr-xr-x ] && [ "$(mode "$RENDERED/prometheus.yml")" = -rw-r--r-- ] && [ "$(mode "$RENDERED/alertmanager.yml")" = -rw-r--r-- ] && [ "$(mode "$RENDERED/blackbox.yml")" = -rw-r--r-- ] && [ "$(mode "$RENDERED/ca-certificates.crt")" = -rw-r--r-- ] && [ "$(mode "$RENDERED/core.env")" = -rw-r--r-- ]'
check '설정을 펼치면 OS의 CA 인증서 파일을 복사하고 컨테이너에 넘길 값을 core.env에 쓴다' \
  '[ "$(cat "$RENDERED/ca-certificates.crt")" = "TEST CA 1" ] && grep -q "^CORE_RETENTION_DAYS=15$" "$RENDERED/core.env" && grep -q "^CORE_RETENTION_SIZE_GB=$" "$RENDERED/core.env" && grep -q "^CORE_PROBE_TIMEOUT_SECONDS=5$" "$RENDERED/core.env" && [ "$(config_hash | grep -c "^[0-9a-f]\{64\}$")" -eq 1 ]'
check '설정을 펼친 뒤 임시 파일을 남기지 않는다' \
  '[ "$(ls -A "$RENDERED" | sort | tr "\n" " ")" = "alertmanager.yml blackbox.yml ca-certificates.crt core.env prometheus.yml " ]'

# umask가 좁은 서버에서도 컨테이너 사용자(nobody)가 읽을 수 있어야 한다.
setup; prepared
umask 077
run render
umask 022
check 'umask가 077이어도 펼친 설정은 컨테이너 사용자가 읽을 수 있다' \
  '[ $RC -eq 0 ] && [ "$(mode "$RENDERED")" = drwxr-xr-x ] && [ "$(mode "$RENDERED/prometheus.yml")" = -rw-r--r-- ] && [ "$(mode "$RENDERED/core.env")" = -rw-r--r-- ]'

# 펼친 설정은 떠 있는 컨테이너가 다시 시작할 때 읽을 수 있어, 보관 값을 줄이면 설정 펼치기도 확인을 묻는다.
setup; prepared; previously_rendered; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
run render
check '떠 있는 코어보다 보관 기간을 줄여 펼치는데 터미널이 아니면 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "되돌릴 수 없습니다" && unchanged'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
run_in_terminal y render
check '떠 있는 코어보다 보관 기간을 줄여 펼칠지 물었을 때 y이면 펼친다' \
  '[ $RC -eq 0 ] && contains "계속할까요" && grep -q "^      time: 15d$" "$RENDERED/prometheus.yml"'

setup; prepared; with_settings CORE_RETENTION_DAYS=30 CORE_RETENTION_SIZE_GB=50 CORE_PROBE_TIMEOUT_SECONDS=9
run render
check '바꾼 코어 값으로 보관 항목과 확인 제한 시간을 펼치고 core.env에도 쓴다' \
  '[ $RC -eq 0 ] && grep -q "^      time: 30d$" "$RENDERED/prometheus.yml" && grep -q "^      size: 50GB$" "$RENDERED/prometheus.yml" && [ "$(grep -c "^    timeout: 9s$" "$RENDERED/blackbox.yml")" -eq 3 ] && grep -q "^CORE_RETENTION_SIZE_GB=50$" "$RENDERED/core.env"'

setup; prepared; rm "$BUNDLE/.env"
run render
check '설정 파일이 없으면 설정 펼치기도 설치 준비를 안내하고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "sh core-server.sh install" && [ ! -e "$RENDERED" ]'

setup; prepared; printf 'x' >> "$BUNDLE/templates/prometheus.yml"
run render
check '묶음의 템플릿이 바뀌었으면 체크섬 오류를 보이고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "templates/prometheus.yml" && contains "체크섬" && [ ! -e "$RENDERED" ]'

# 형식이 틀린 관리자 서버 주소와 코어 값: 빌드 PC의 설정 검사가 시작 스크립트로 확인하던 목록이다.
for address in '' 'manager-api' 'http://manager-api:8080' 'manager-api:0' 'manager-api:70000' 'a:b:8080' '-manager:8080' \
  'manager api:8080' 'manager|api:8080' 'manager&api:8080' 'manager;api:8080' '$(touch${IFS}injected):8080'; do
  setup; prepared; previously_rendered; printf 'MONITORING_APP_ADDRESS=%s\n' "$address" > "$BUNDLE/.env"
  run render
  check "설정 펼치기는 관리자 서버 주소가 '$address'이면 거부하고 이전에 펼친 설정을 바꾸지 않는다" \
    '[ $RC -ne 0 ] && contains "관리자 서버 주소" && unchanged && [ ! -e "$BUNDLE/injected" ]'
done
for address in 'manager-api:8080
x' 'manager-api:8080
      - url: http://x'; do
  setup; prepared; previously_rendered
  run_with_env "MONITORING_APP_ADDRESS=$address" render
  check '설정 펼치기는 셸 환경 변수의 관리자 서버 주소에 줄바꿈과 YAML이 있으면 거부한다' \
    '[ $RC -ne 0 ] && contains "관리자 서버 주소" && unchanged'
done
for pair in 'CORE_RETENTION_DAYS=0' 'CORE_RETENTION_DAYS=15 --web.enable-admin-api' 'CORE_RETENTION_SIZE_GB=abc' \
  'CORE_PROBE_TIMEOUT_SECONDS=10' 'CORE_PROBE_TIMEOUT_SECONDS=5|g' 'CORE_PROBE_TIMEOUT_SECONDS=5&'; do
  setup; prepared; previously_rendered; with_settings "$pair"
  run render
  check "설정 펼치기는 $pair 값을 거부하고 이전에 펼친 설정을 바꾸지 않는다" \
    '[ $RC -ne 0 ] && contains "${pair%%=*}" && contains "허용 범위" && unchanged'
done
setup; prepared; previously_rendered
run_with_env "CORE_PROBE_TIMEOUT_SECONDS=5
prober: tcp" render
check '설정 펼치기는 셸 환경 변수의 값에 줄바꿈과 YAML이 있으면 거부한다' \
  '[ $RC -ne 0 ] && contains "CORE_PROBE_TIMEOUT_SECONDS" && unchanged'
setup; prepared; previously_rendered; with_settings CORE_ALERTMANAGER_PORT=9090
run render
check '설정 펼치기는 호스트 포트가 겹치면 거부한다' '[ $RC -ne 0 ] && contains "호스트 포트가 겹칩니다" && unchanged'

# token: 운영 명령 없이 Compose로 띄울 때 token 없이 Alertmanager가 뜨지 않게 펼치기 전에 확인한다(요구사항 0015의 S14).
setup; prepared; rm "$TOKEN"
run render
check '설정 펼치기는 token 파일이 없으면 알리고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "token 파일이 없습니다" && [ ! -e "$RENDERED" ] && [ ! -e "$TOKEN" ]'
setup; prepared; : > "$TOKEN"
run render
check '설정 펼치기는 token 파일이 비어 있으면 알리고 펼치지 않는다' '[ $RC -ne 0 ] && contains "비어 있습니다" && [ ! -e "$RENDERED" ]'
setup; prepared; rm "$TOKEN"; mkdir "$TOKEN"
run render
check '설정 펼치기는 token 자리에 폴더가 있으면 알리고 펼치지 않는다' '[ $RC -ne 0 ] && contains "폴더가 있습니다" && [ ! -e "$RENDERED" ]'
setup; prepared; chmod 0600 "$TOKEN"
run render
check '설정 펼치기는 컨테이너가 token 파일을 읽을 수 없으면 알리고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "읽을 수 없습니다" && [ ! -e "$RENDERED" ]'

# --- CA 인증서 파일 찾기(요구사항 0015의 S3, S4, S13) ---

ALL_CA_LOCATIONS='contains "/etc/ssl/certs/ca-certificates.crt" && contains "/etc/pki/tls/certs/ca-bundle.crt" && contains "/etc/ssl/ca-bundle.pem" && contains "/etc/pki/tls/cacert.pem" && contains "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem" && contains "/etc/ssl/cert.pem"'

setup; prepared; rm "$CA_ROOT/etc/ssl/certs/ca-certificates.crt"
run render
check 'CA 인증서 파일을 찾지 못하면 찾아본 위치와 설치할 패키지를 보이고 펼치지 않는다' \
  '[ $RC -ne 0 ] && '"$ALL_CA_LOCATIONS"' && contains "ca-certificates 패키지" && [ ! -e "$RENDERED" ]'

setup; prepared; rm "$CA_ROOT/etc/ssl/certs/ca-certificates.crt"
run start
check 'CA 인증서 파일을 찾지 못하면 띄우기도 이미지를 불러오거나 컨테이너를 바꾸지 않는다' \
  '[ $RC -ne 0 ] && contains "ca-certificates 패키지" && ! called load && ! called "^compose"'

setup; prepared; rm "$CA_ROOT/etc/ssl/certs/ca-certificates.crt"
mkdir -p "$CA_ROOT/etc/pki/ca-trust/extracted/pem" "$CA_ROOT/etc/pki/tls/certs"
printf 'RHEL CA\n' > "$CA_ROOT/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem"
ln -s ../../ca-trust/extracted/pem/tls-ca-bundle.pem "$CA_ROOT/etc/pki/tls/certs/ca-bundle.crt"
run render
check 'RHEL 계열처럼 CA 인증서 파일이 링크면 링크가 가리키는 파일의 내용을 복사한다' \
  '[ $RC -eq 0 ] && [ "$(cat "$RENDERED/ca-certificates.crt")" = "RHEL CA" ] && [ ! -L "$RENDERED/ca-certificates.crt" ]'

setup; prepared; : > "$CA_ROOT/etc/ssl/certs/ca-certificates.crt"
mkdir -p "$CA_ROOT/etc/pki/tls/certs"; ln -s "$T/no-such-file" "$CA_ROOT/etc/pki/tls/certs/ca-bundle.crt"
printf 'LAST CA\n' > "$CA_ROOT/etc/ssl/cert.pem"
run render
check '비어 있거나 끊긴 링크인 CA 인증서 파일은 건너뛰고 다음 위치의 파일을 쓴다' \
  '[ $RC -eq 0 ] && [ "$(cat "$RENDERED/ca-certificates.crt")" = "LAST CA" ]'

setup; prepared
run render
first=$(config_hash)
printf 'TEST CA 2\n' > "$CA_ROOT/etc/ssl/certs/ca-certificates.crt"
run render
check 'OS의 CA 인증서 파일이 바뀌면 다시 펼칠 때 복사본과 core.env의 설정 값이 바뀐다' \
  '[ $RC -eq 0 ] && [ "$(cat "$RENDERED/ca-certificates.crt")" = "TEST CA 2" ] && [ "$(config_hash)" != "$first" ]'

setup; prepared
run render
first=$(config_hash)
printf 'MONITORING_APP_ADDRESS=other-api:9000\n' > "$BUNDLE/.env"
run render
check '관리자 서버 주소를 바꿔 다시 펼치면 Compose가 컨테이너를 다시 만들도록 core.env의 설정 값이 바뀐다' \
  '[ $RC -eq 0 ] && [ "$(rendered_address)" = "other-api:9000" ] && [ "$(config_hash)" != "$first" ]'

# --- 펼친 설정 폴더(요구사항 0015의 S16, S17) ---

setup; prepared; mkdir "$T/elsewhere-dir"; ln -s "$T/elsewhere-dir" "$RENDERED"
run render
check '펼친 설정 폴더가 심볼릭 링크면 쓰지 않고 실패한다' \
  '[ $RC -ne 0 ] && contains "심볼릭 링크" && [ -z "$(ls -A "$T/elsewhere-dir")" ]'

setup; prepared; mkdir "$RENDERED"; printf 'keep\n' > "$T/victim"; ln -s "$T/victim" "$RENDERED/prometheus.yml"
run render
check '펼친 설정 파일이 심볼릭 링크면 링크가 가리키는 파일을 덮어쓰지 않고 실패한다' \
  '[ $RC -ne 0 ] && contains "심볼릭 링크" && [ "$(cat "$T/victim")" = keep ] && [ -L "$RENDERED/prometheus.yml" ]'

setup; prepared; mkdir -p "$RENDERED/blackbox.yml"
run render
check 'Docker가 만든 폴더가 펼친 설정 자리에 있으면 지우는 방법을 보이고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "rendered/blackbox.yml" && contains "rmdir" && [ -d "$RENDERED/blackbox.yml" ] && [ ! -e "$RENDERED/prometheus.yml" ]'

setup; prepared
run render
expected=$(cat "$RENDERED/prometheus.yml")
printf 'edited: true\n' >> "$RENDERED/prometheus.yml"
run render
check '펼친 설정 파일을 고친 뒤 다시 펼치면 템플릿에서 다시 만든 내용이 된다' \
  '[ $RC -eq 0 ] && [ "$(cat "$RENDERED/prometheus.yml")" = "$expected" ]'

setup; prepared
run start
rm "$FAKE/calls"; printf 'edited: true\n' >> "$RENDERED/prometheus.yml"
run start
check '펼친 설정 파일을 고친 뒤 띄우면 고친 파일을 붙잡은 컨테이너를 다시 만든다' \
  '[ $RC -eq 0 ] && called "^compose.* up -d --force-recreate" && ! grep -q "edited" "$RENDERED/prometheus.yml"'

setup; prepared
run start
rm "$FAKE/calls"
run start
check '펼친 설정이 그대로면 컨테이너를 강제로 다시 만들지 않는다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && ! called "force-recreate"'

setup; prepared; previously_rendered; running_other v3 1761700000 "$INSTALL/monitoring-core-v3"
run start
check '이전 버전으로 바꾸지 않고 멈추면 펼친 설정을 바꾸지 않는다' '[ $RC -ne 0 ] && unchanged'

setup; prepared; previously_rendered; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
run start
check '기록 보관 값 줄임을 확인하지 않고 멈추면 펼친 설정을 바꾸지 않는다' \
  '[ $RC -ne 0 ] && contains "되돌릴 수 없습니다" && unchanged'

setup; prepared
REPO="$T/repo"
mkdir -p "$REPO/prometheus" "$REPO/alertmanager" "$REPO/blackbox" "$REPO/secrets"
cp "$SCRIPT" "$SETTINGS" "$REPO/"
cp "$SOURCE_DIR/prometheus/prometheus.yml" "$REPO/prometheus/"
cp "$SOURCE_DIR/alertmanager/alertmanager.yml" "$REPO/alertmanager/"
cp "$SOURCE_DIR/blackbox/blackbox.yml" "$REPO/blackbox/"
printf 'repo-token-for-test\n' > "$REPO/secrets/alertmanager_webhook_token"
printf 'MONITORING_APP_ADDRESS=dev-api:8080\n' > "$REPO/.env"
OUT=$(cd "$REPO" && sh ./core-server.sh render 2>&1 < /dev/null)
RC=$?
check '저장소의 infrastructure/에서 펼치면 저장소의 템플릿과 ./secrets의 token으로 rendered/에 쓴다' \
  '[ $RC -eq 0 ] && grep -q "url: http://dev-api:8080/api/prometheus/sd/blackbox" "$REPO/rendered/prometheus.yml" && [ -f "$REPO/rendered/core.env" ]'

# --- 준비 확인(요구사항 0015의 S7, S15) ---

setup; prepared
run start
check '띄운 뒤 curl로 세 프로그램의 준비 확인 주소를 부른다' \
  '[ $RC -eq 0 ] && ready_called "^curl .*http://127.0.0.1:9090/-/ready" && ready_called "^curl .*http://127.0.0.1:9093/-/ready" && ready_called "^curl .*http://127.0.0.1:9115/-/healthy"'

setup; prepared; with_settings CORE_PROMETHEUS_PORT=19090 CORE_ALERTMANAGER_PORT=19093 CORE_BLACKBOX_PORT=19115
run start
check '호스트 포트를 바꿨으면 바꾼 포트로 준비 확인 주소를 부른다' \
  '[ $RC -eq 0 ] && ready_called "http://127.0.0.1:19090/-/ready" && ready_called "http://127.0.0.1:19093/-/ready" && ready_called "http://127.0.0.1:19115/-/healthy" && ! ready_called ":9090/"'

setup; prepared
OUT=$(cd "$BUNDLE" && CORE_READY_TOOLS=wget sh ./core-server.sh start 2>&1 < /dev/null)
RC=$?
check 'curl을 쓸 수 없으면 wget으로 준비 확인 주소를 부른다' \
  '[ $RC -eq 0 ] && ready_called "^wget .*http://127.0.0.1:9090/-/ready" && ! ready_called "^curl"'

setup; prepared; printf '9115\n' > "$FAKE/not_ready_ports"
OUT=$(cd "$BUNDLE" && CORE_READY_TOOLS=wget sh ./core-server.sh start 2>&1 < /dev/null)
RC=$?
check 'wget으로 부른 준비 확인 주소가 실패하면 그 컨테이너와 로그 명령을 보인다' \
  '[ $RC -ne 0 ] && contains "docker logs blackbox-exporter" && ! contains "docker logs prometheus"'

setup; prepared
OUT=$(cd "$BUNDLE" && CORE_READY_TOOLS="" sh ./core-server.sh start 2>&1 < /dev/null)
RC=$?
check 'curl과 wget이 없으면 경고하고, 컨테이너가 실행 중이고 다시 시작하지 않으면 띄우기를 마친다' \
  '[ $RC -eq 0 ] && contains "curl과 wget이 없어" && [ ! -f "$FAKE/ready_calls" ] && contains "코어가 떠 있습니다"'

setup; prepared; touch "$FAKE/crashing.blackbox-exporter"
OUT=$(cd "$BUNDLE" && CORE_READY_TOOLS="" sh ./core-server.sh start 2>&1 < /dev/null)
RC=$?
check 'curl과 wget이 없을 때 다시 시작하는 컨테이너가 있으면 그 컨테이너와 로그 명령을 보인다' \
  '[ $RC -ne 0 ] && contains "docker logs blackbox-exporter" && ! contains "docker logs prometheus"'

printf '\n통과 %s, 실패 %s\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
