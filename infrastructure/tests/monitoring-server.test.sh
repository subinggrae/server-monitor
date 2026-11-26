#!/bin/sh
# monitoring-server.sh의 사전 검사, 설정 펼치기, 설치 준비를 가짜 docker, systemctl, curl, wget으로 상황을 만들어 확인한다.
# 실제 Docker는 쓰지 않는다. 컨테이너를 띄우는 흐름은 요구사항 0011, 0015의 수동 확인이 맡는다.
#
#   sh monitoring-server.test.sh <monitoring-server.sh 경로>
#
# 저장소의 infrastructure/에 있는 설정 템플릿을 묶음의 templates/에 넣어 펼친다.
# 종료 코드: 실패한 시험이 하나라도 있으면 1.
set -u

SCRIPT=$1
# 묶음에서 monitoring-server.sh 옆에 있는 공용 검사 파일이다.
SOURCE_DIR=$(dirname "$SCRIPT")
SETTINGS="$SOURCE_DIR/monitoring-settings.sh"
FAILED=0
PASSED=0

# 시험 하나마다 빈 설치 폴더, 묶음 폴더, 가짜 명령을 새로 만든다.
setup() {
  T=$(mktemp -d)
  INSTALL="$T/install"
  BUNDLE="$INSTALL/monitoring-system-v2"
  FAKE="$T/fake"
  TOKEN="$INSTALL/secrets/alertmanager_webhook_token"
  # 묶음에서 설정 파일은 설치 폴더의 .env 하나다(요구사항 0017). 묶음 폴더의 .env는 운영 명령이 만드는 ../.env 링크다.
  ENV="$INSTALL/.env"
  RENDERED="$BUNDLE/rendered"
  CA="$INSTALL/certs/ca-certificates.crt"
  mkdir -p "$BUNDLE/templates" "$FAKE" "$T/bin"
  cp "$SCRIPT" "$BUNDLE/monitoring-server.sh"
  cp "$SETTINGS" "$BUNDLE/monitoring-settings.sh"
  cp "$SOURCE_DIR/prometheus/prometheus.yml" "$SOURCE_DIR/alertmanager/alertmanager.yml" "$SOURCE_DIR/blackbox/blackbox.yml" \
    "$BUNDLE/templates/"
  cat > "$BUNDLE/compose.yml" <<'EOF'
services:
  prometheus:
    image: monitoring-system-prometheus:v2
  alertmanager:
    image: monitoring-system-alertmanager:v2
  blackbox-exporter:
    image: monitoring-system-blackbox-exporter:v2
EOF
  printf '# 예시\nMONITORING_APP_ADDRESS=manager-api:8080\n' > "$BUNDLE/.env.example"
  printf 'images' > "$BUNDLE/monitoring-system-images.tar"
  (cd "$BUNDLE" && sha256sum compose.yml .env.example monitoring-system-images.tar monitoring-server.sh monitoring-settings.sh templates/* > SHA256SUMS)
  printf 'enabled\n' > "$FAKE/systemctl"
  # 운영자가 운영 가이드대로 만들어 둔 monitoring 네트워크(요구사항 0019). 없는 상황은 시험에서 지운다.
  touch "$FAKE/network"
  printf '1761600000\n' > "$FAKE/bundle_time"
  write_fake_docker
  write_fake_systemctl
  write_fake_http curl 22
  write_fake_http wget 1
  PATH="$T/bin:$ORIGINAL_PATH"
  unset MONITORING_READY_TOOLS
  export PATH FAKE MONITORING_WAIT_SECONDS=2 MONITORING_WAIT_INTERVAL=1
}

write_fake_docker() {
  cat > "$T/bin/docker" <<'EOF'
#!/bin/sh
echo "$*" >> "$FAKE/calls"
case "$1" in
  info) [ ! -f "$FAKE/docker_down" ] ;;
  network)
    case "$2 $3" in
      'inspect monitoring') [ -f "$FAKE/network" ] || { echo 'Error response from daemon: network monitoring not found' >&2; exit 1; } ;;
      *) exit 1 ;;
    esac ;;
  # 불러온 묶음의 버전으로 이미지 세 개를 이미지 목록(images)에 더한다.
  load)
    version=$(sed -n 's/^ *image: monitoring-system-prometheus://p' "$(dirname "$3")/compose.yml")
    for name in prometheus alertmanager blackbox-exporter; do
      printf 'monitoring-system-%s:%s\n' "$name" "$version" >> "$FAKE/images"
    done ;;
  compose)
    case " $* " in
      *" up "*)
        # Compose처럼 펼친 설정(env_file, required: true)이 없으면 컨테이너를 만들지 않는다.
        if [ ! -f "$PWD/rendered/monitoring.env" ]; then
          echo "env file $PWD/rendered/monitoring.env not found" >&2
          exit 1
        fi
        touch "$FAKE/running"; printf '%s\n' "$PWD" > "$FAKE/running_workdir"
        # Compose가 볼륨 원본으로 쓸 CA 인증서 파일 경로 값. 없으면 unset이다.
        printf '%s\n' "${MONITORING_CA_FILE-unset}" > "$FAKE/compose_ca_file"
        # Compose가 token 파일 경로로 쓸 token 폴더 값. 없으면 unset이다.
        printf '%s\n' "${MONITORING_SECRETS_DIR-unset}" > "$FAKE/compose_secrets_dir"
        sed -n 's/^ *image: monitoring-system-prometheus://p' "$PWD/compose.yml" > "$FAKE/running_version"
        # 다른 프로그램이 쓰는 포트: Docker처럼 그 컨테이너만 만들어 두고 띄우지 못한다.
        if [ -f "$FAKE/port_taken" ]; then
          printf 'created\n' > "$FAKE/status.prometheus"
          echo 'Error response from daemon: Bind for 127.0.0.1:9090 failed: port is already allocated' >&2
          exit 1
        fi
        # Compose처럼 env_file의 값을 컨테이너 환경 변수로, 셸 환경 변수의 값을 호스트 포트로 남긴다. 이 가짜는 .env를 읽지 않는다.
        { printf 'PATH=/bin\n'; grep -v '^#' "$PWD/rendered/monitoring.env"; } > "$FAKE/env.prometheus"
        cp "$FAKE/env.prometheus" "$FAKE/env.blackbox-exporter"
        printf '{"9090/tcp":[{"HostIp":"127.0.0.1","HostPort":"%s"}]}\n' "${MONITORING_PROMETHEUS_PORT:-9090}" > "$FAKE/ports.prometheus"
        printf '{"9093/tcp":[{"HostIp":"127.0.0.1","HostPort":"%s"}]}\n' "${MONITORING_ALERTMANAGER_PORT:-9093}" > "$FAKE/ports.alertmanager"
        printf '{"9115/tcp":[{"HostIp":"127.0.0.1","HostPort":"%s"}]}\n' "${MONITORING_BLACKBOX_PORT:-9115}" > "$FAKE/ports.blackbox-exporter"
        ;;
    esac ;;
  # images_loaded가 있으면 어느 버전의 이미지든 있다고 본다. 없으면 이미지 목록(images)에 있는 것만 있다.
  # images_in_use에 적은 이미지는 Docker처럼 컨테이너가 쓰고 있어 지우지 못한다.
  image)
    case "$2" in
      ls) cat "$FAKE/images" 2>/dev/null; exit 0 ;;
      rm)
        shift 2
        for ref; do
          if grep -qx "$ref" "$FAKE/images_in_use" 2>/dev/null; then
            echo "Error response from daemon: conflict: unable to remove repository reference \"$ref\" (must force) - container 1a2b3c is using its referenced image" >&2
            exit 1
          fi
          grep -qx "$ref" "$FAKE/images" 2>/dev/null || { echo "Error response from daemon: No such image: $ref" >&2; exit 1; }
          grep -vx "$ref" "$FAKE/images" > "$FAKE/images.tmp"; mv "$FAKE/images.tmp" "$FAKE/images"
        done
        exit 0 ;;
    esac
    for ref; do :; done
    [ -f "$FAKE/images_loaded" ] || grep -qx "$ref" "$FAKE/images" 2>/dev/null || exit 1
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

# 설치 준비를 마친 상태: token, .env, 운영자가 가이드대로 복사한 CA 인증서 파일이 있다.
prepared() {
  mkdir -p "$INSTALL/certs"
  printf 'TEST CA 1\n' > "$CA"
  chmod 0644 "$CA"
  mkdir -p "$INSTALL/secrets"
  chmod 0700 "$INSTALL/secrets"
  printf 'secret-value-for-test\n' > "$INSTALL/secrets/alertmanager_webhook_token"
  chmod 0644 "$INSTALL/secrets/alertmanager_webhook_token"
  printf 'MONITORING_APP_ADDRESS=manager-api:8080\n' > "$ENV"
}

# 이 묶음 폴더에서 start로 띄운 상태. 부른 명령 기록은 지운다.
started() {
  run start
  rm -f "$FAKE/calls" "$FAKE/ready_calls"
}

# 다른 버전의 모니터링 시스템이 떠 있는 상태. 두 번째 인자는 그 이미지의 커밋 시각 label 값이다.
running_other() {
  touch "$FAKE/running" "$FAKE/images_loaded"
  printf '%s\n' "$1" > "$FAKE/running_version"
  printf '%s\n' "$2" > "$FAKE/running_time"
  printf '%s\n' "$3" > "$FAKE/running_workdir"
}

run() {
  OUT=$(cd "$BUNDLE" && sh ./monitoring-server.sh "$@" 2>&1 < "${STDIN:-/dev/null}")
  RC=$?
}

# 확인 질문은 터미널에서만 묻는다. BusyBox script로 가상 터미널을 만들어 답을 넣는다.
# script는 입력이 끝나면 안의 명령을 기다리지 않고 끝나므로, 답을 넣은 뒤 입력을 잠시 열어 둔다.
run_in_terminal() {
  answer=$1
  shift
  OUT=$(cd "$BUNDLE" && { printf '%s\n' "$answer"; sleep 3; } | script -q -c "sh ./monitoring-server.sh $*" /dev/null 2>&1)
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

# 설치 폴더에 다른 버전(첫 번째 인자)의 묶음 폴더를 만든다. 이 묶음(v2)의 파일을 쓰고 이미지 태그만 바꾼다.
add_bundle() {
  dir="$INSTALL/monitoring-system-$1"
  mkdir -p "$dir/templates"
  cp "$BUNDLE/monitoring-server.sh" "$BUNDLE/monitoring-settings.sh" "$BUNDLE/.env.example" "$BUNDLE/monitoring-system-images.tar" "$dir/"
  cp "$BUNDLE"/templates/* "$dir/templates/"
  sed "s/:v2\$/:$1/" "$BUNDLE/compose.yml" > "$dir/compose.yml"
  (cd "$dir" && sha256sum compose.yml .env.example monitoring-system-images.tar monitoring-server.sh monitoring-settings.sh templates/* > SHA256SUMS)
}

# 설치 폴더의 다른 묶음 폴더(첫 번째 인자는 버전)에서 운영 명령을 실행한다.
run_at() {
  dir="$INSTALL/monitoring-system-$1"
  shift
  OUT=$(cd "$dir" && sh ./monitoring-server.sh "$@" 2>&1 < "${STDIN:-/dev/null}")
  RC=$?
}

# 버전 기록 파일(설치 폴더의 monitoring-versions)에 적힌 current나 previous 값
recorded() { sed -n "s/^$1=//p" "$INSTALL/monitoring-versions" 2>/dev/null; }
has_image() { [ -f "$FAKE/images" ] && grep -qx "$1" "$FAKE/images"; }
# 버전(첫 번째 인자)의 이미지 세 개가 모두 있으면 0, 하나도 없으면 1, 일부만 있으면 2
version_images() {
  count=0
  for name in prometheus alertmanager blackbox-exporter; do
    has_image "monitoring-system-$name:$1" && count=$((count + 1))
  done
  case "$count" in 3) return 0 ;; 0) return 1 ;; *) return 2 ;; esac
}
kept() { [ -d "$INSTALL/monitoring-system-$1" ] && version_images "$1"; }
removed() { [ ! -e "$INSTALL/monitoring-system-$1" ] && { version_images "$1"; [ $? -eq 1 ]; }; }

ORIGINAL_PATH=$PATH

# --- 명령과 Docker ---

setup
run deploy
check '알 수 없는 작업이면 쓸 수 있는 다섯 작업을 보이고 실패한다' \
  '[ $RC -ne 0 ] && contains "install" && contains "render" && contains "start" && contains "rollback" && contains "status" && ! contains "rotate-token" && ! called "^compose"'

setup; prepared; started; before=$(cat "$TOKEN")
run rotate-token
check 'token 바꾸기 작업은 없고, 운영 명령이 token을 바꾸거나 컨테이너를 다시 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "사용법" && [ "$(cat "$TOKEN")" = "$before" ] && ! called "^compose"'

setup; prepared; touch "$FAKE/docker_down"
run start
check 'Docker가 꺼져 있으면 알리고 아무것도 하지 않는다' \
  '[ $RC -ne 0 ] && contains "Docker에 연결하지 못했습니다" && ! called load && ! called "^compose"'

# 운영자가 만들 monitoring 네트워크(요구사항 0019): 모니터링 시스템은 네트워크를 만들지 않고 이미 있는 네트워크에 붙는다.
setup; prepared; rm "$FAKE/network"
run start
check 'monitoring 네트워크가 없으면 만드는 명령을 알리고 불러오거나 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "docker network create monitoring" && ! called load && ! called "^compose" && ! called "^network create"'

setup; prepared; started; rm "$FAKE/network"
run start
check 'monitoring 네트워크가 없으면 떠 있던 모니터링 시스템을 다시 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "docker network create monitoring" && ! called "^compose"'

setup; prepared
run start
check 'monitoring 네트워크가 있으면 확인한 뒤 띄운다' \
  '[ $RC -eq 0 ] && called "^network inspect monitoring" && called "^compose.* up"'

# --- 체크섬 ---

setup; prepared; printf 'broken' > "$BUNDLE/monitoring-system-images.tar"
run start
check '묶음 파일이 바뀌었으면 그 파일을 알리고 불러오거나 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "monitoring-system-images.tar" && contains "체크섬" && ! called load && ! called "^compose"'

# --- 설정 파일 ---

setup; prepared; rm "$ENV"
run start
check '설정 파일이 없으면 설치 준비를 안내하고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "sh monitoring-server.sh install" && ! contains "install 대신" && ! contains "sudo cp" && ! called "^compose"'

# 갱신하며 .env를 옮기지 않은 경우: install로 기본값 .env를 만들면 바꿔 둔 값이 조용히 기본값으로 돌아간다.
setup; prepared; rm "$ENV"; running_other v1 1761500000 "$INSTALL/monitoring-system-v1"
run start
check '모니터링 시스템이 이미 있는데 설치 폴더에 설정 파일이 없으면 install 대신 쓰던 .env를 복사하라고 안내한다' \
  '[ $RC -ne 0 ] && contains "install 대신" && ! called "^compose"'

# 다른 묶음 폴더의 설정 파일은 찾지 않는다(요구사항 0021의 S3). 떠 있는 버전의 폴더에 실제 .env가 있어도 같다.
setup; prepared; mkdir "$INSTALL/monitoring-core-v1"; mv "$ENV" "$INSTALL/monitoring-core-v1/.env"
running_other v1 1761500000 "$INSTALL/monitoring-core-v1"
run start
check '떠 있는데 설치 폴더에 설정 파일이 없으면 다른 묶음 폴더의 파일을 찾지 않고 쓰던 .env를 복사하라고 안내한다' \
  '[ $RC -ne 0 ] && contains "install 대신" && ! contains "monitoring-core-v1" && ! contains "sudo cp" && [ ! -e "$ENV" ] && ! called "^compose"'

# 처음 설치(요구사항 0021의 S1): 옛 이름 묶음 폴더에 .env가 남아 있어도 보지 않는다.
setup; prepared; mkdir "$INSTALL/monitoring-core-v1"; mv "$ENV" "$INSTALL/monitoring-core-v1/.env"
run start
check '모니터링 시스템이 없으면 다른 묶음 폴더의 설정 파일을 찾지 않고 설치 준비를 안내한다' \
  '[ $RC -ne 0 ] && contains "sh monitoring-server.sh install" && ! contains "monitoring-core-v1" && ! contains "sudo cp" && [ ! -e "$ENV" ] && ! called "^compose"'

# 묶음 폴더에 실제 설정 파일만 있는 경우(요구사항 0021의 S4): 설치 폴더의 .env가 없어도 같은 안내로 멈추고 옮기지 않는다.
setup; prepared; mv "$ENV" "$BUNDLE/.env"
run start
check '설정 파일이 묶음 폴더에만 있으면 묶음 폴더의 파일은 쓰지 않는다고 안내하고 옮기거나 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "묶음 폴더의 설정 파일은 쓰지 않습니다" && contains "$ENV" && ! contains "sudo mv" && [ ! -e "$ENV" ] && [ -f "$BUNDLE/.env" ] && ! called "^compose"'

# 양쪽에 설정 파일이 있는 경우(S6): 어느 값을 쓰는지 헷갈리지 않게 멈춘다.
setup; prepared; printf 'MONITORING_APP_ADDRESS=other-admin:9000\n' > "$BUNDLE/.env"
run start
check '설치 폴더와 묶음 폴더에 모두 설정 파일이 있으면 묶음 폴더의 파일을 지우라고 안내하고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "sudo rm $BUNDLE/.env" && grep -q other-admin "$BUNDLE/.env" && ! called "^compose"'

setup; prepared; printf 'MONITORING_APP_ADDRESS=other-admin:9000\n' > "$BUNDLE/.env"
run render
check '설정 펼치기도 묶음 폴더에 별도의 설정 파일이 있으면 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "sudo rm $BUNDLE/.env" && [ ! -e "$RENDERED" ]'

setup; prepared; printf 'MONITORING_APP_ADDRESS=other-admin:9000\n' > "$T/other.env"; ln -s "$T/other.env" "$BUNDLE/.env"
run start
check '묶음 폴더의 .env가 다른 곳을 가리키는 링크면 따라가지 않고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "sudo rm $BUNDLE/.env" && [ "$(cat "$T/other.env")" = "MONITORING_APP_ADDRESS=other-admin:9000" ] && ! called "^compose"'

# 설치 폴더의 .env가 링크면 따라가지 않는다(CWE-59).
setup; prepared; mv "$ENV" "$T/real.env"; ln -s "$T/real.env" "$ENV"
run start
check '설치 폴더의 설정 파일이 링크면 따라가 읽지 않고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "링크입니다" && ! called "^compose"'

setup; prepared; rm "$ENV"; ln -s "$T/victim.conf" "$ENV"; STDIN="$T/answer"; printf 'manager-api:8080\n' > "$STDIN"
run install
unset STDIN
check '설치 준비는 설치 폴더의 설정 파일 자리에 있는 링크를 따라 쓰지 않는다' \
  '[ $RC -ne 0 ] && contains "링크입니다" && [ ! -e "$T/victim.conf" ]'

setup; prepared; printf 'MONITORING_APP_ADDRESS=other-admin:9000\n' > "$T/other.env"; ln -s "$T/other.env" "$BUNDLE/.env"
run install
check '설치 준비도 묶음 폴더의 .env가 다른 곳을 가리키는 링크면 멈춘다' \
  '[ $RC -ne 0 ] && contains "sudo rm $BUNDLE/.env" && [ "$(readlink "$BUNDLE/.env")" = "$T/other.env" ]'

for address in '' 'http://manager-api:8080' 'manager-api' 'a;touch${IFS}x:80' 'manager-api:70000'; do
  setup; prepared; printf 'MONITORING_APP_ADDRESS=%s\n' "$address" > "$ENV"
  run start
  check "관리자 서버 주소가 '$address'이면 거부한다" \
    '[ $RC -ne 0 ] && contains "관리자 서버 주소" && ! called "^compose" && [ ! -e "$BUNDLE/x" ]'
done

setup; prepared
OUT=$(cd "$BUNDLE" && env "MONITORING_APP_ADDRESS=x
manager-api:8080" sh ./monitoring-server.sh start 2>&1 < /dev/null)
RC=$?
check '셸 환경 변수의 관리자 서버 주소가 여러 줄이면 한 줄이 맞아도 거부한다' \
  '[ $RC -ne 0 ] && contains "관리자 서버 주소" && ! called "^compose"'

setup; prepared
OUT=$(cd "$BUNDLE" && env "MONITORING_APP_ADDRESS=manager-api:8080
" sh ./monitoring-server.sh start 2>&1 < /dev/null)
RC=$?
check '셸 환경 변수의 관리자 서버 주소 끝에 줄바꿈이 있으면 검사한 값 그대로 설정에 펼친다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && [ "$(rendered_address)" = "manager-api:8080" ]'

setup; prepared; printf "MONITORING_APP_ADDRESS='manager-api:8080' # 관리자 서버\n" > "$ENV"
run start
check '설정 파일의 관리자 서버 주소는 따옴표와 주석을 뺀 값으로 설정에 펼친다' \
  '[ $RC -eq 0 ] && [ "$(rendered_address)" = "manager-api:8080" ]'

setup; prepared; printf 'MONITORING_APP_ADDRESS=manager-api:8080\n$(touch %s/injected)\n' "$T" > "$ENV"
run start
check '설정 파일의 셸 명령은 실행하지 않는다' '[ ! -e "$T/injected" ]'

# --- token ---

setup; prepared; rm "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'token 파일이 없으면 알리고 그 자리에 폴더를 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "token 파일이 없습니다" && [ ! -e "$INSTALL/secrets/alertmanager_webhook_token" ] && ! called "^compose"'

# 모니터링 시스템이 이미 있으면 관리자 서버가 그 모니터링 시스템의 token을 쓰고 있다. install로 새 token을 만들면 장애 알림이 모두 거부된다.
setup; prepared; rm "$TOKEN"; running_other v1 1761500000 "$INSTALL/monitoring-system-v1"
run start
check '모니터링 시스템이 이미 있는데 token 파일이 없으면 새 token을 만들지 말고 쓰던 파일을 두라고 안내한다' \
  '[ $RC -ne 0 ] && contains "token 파일이 없습니다" && contains "관리자 서버의 token과 어긋납니다" && ! called "^compose"'

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

setup; prepared; mkdir "$T/custom"; mv "$INSTALL/secrets/alertmanager_webhook_token" "$T/custom/"
printf 'MONITORING_APP_ADDRESS=manager-api:8080\nMONITORING_SECRETS_DIR=%s\n' "$T/custom" > "$ENV"
run start
check '설정 파일에 token 폴더를 적었으면 그 폴더를 쓴다' '[ $RC -eq 0 ] && called "^compose"'

# --- 띄우기 ---

setup; prepared
run start
check '준비를 마쳤으면 불러오고 띄운 뒤 버전을 보인다' \
  '[ $RC -eq 0 ] && called load && called "^compose.* up" && contains "v2" && ! contains "secret-value-for-test"'
check '띄우면 묶음 폴더에 설치 폴더의 설정 파일을 가리키는 ../.env 링크를 만든다' \
  '[ -L "$BUNDLE/.env" ] && [ "$(readlink "$BUNDLE/.env")" = ../.env ] && [ -f "$ENV" ] && [ ! -L "$ENV" ]'

setup; prepared
run render
check '설정을 펼치면 묶음 폴더에 ../.env 링크를 만든다' \
  '[ $RC -eq 0 ] && [ -L "$BUNDLE/.env" ] && [ "$(readlink "$BUNDLE/.env")" = ../.env ]'

setup; prepared; ln -s ../.env "$BUNDLE/.env"
run start
check '이미 ../.env 링크가 있으면 그대로 두고 띄운다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && [ "$(readlink "$BUNDLE/.env")" = ../.env ]'

setup; prepared; touch "$FAKE/images_loaded"
run start
check '이미지가 이미 있으면 다시 불러오지 않는다' '[ $RC -eq 0 ] && ! called load'

setup; prepared; printf '9090\n' > "$FAKE/not_ready_ports"
run start
check '정해진 시간 안에 준비되지 않으면 그 컨테이너와 로그 명령만 보인다' \
  '[ $RC -ne 0 ] && contains "정상 상태가 되지 않았습니다" && contains "docker logs prometheus" && ! contains "docker logs alertmanager"'

# 직전 버전으로 되돌리기(요구사항 0015의 S21): 버전의 앞뒤와 관계없이 묻지 않고 이 묶음의 버전으로 띄운다.
for other in 'v3 1761700000' 'v1 1761500000'; do
  set -- $other
  setup; prepared; running_other "$1" "$2" "$INSTALL/monitoring-system-$1"
  run start
  check "다른 버전($1)이 떠 있어도 묻지 않고 이 묶음의 버전으로 띄운다" \
    '[ $RC -eq 0 ] && ! contains "계속할까요" && called "^compose.* up" && contains "버전 v2"'
done

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
check '처음 설치하면 입력한 주소로 설정 파일을 만든다' 'grep -q "^MONITORING_APP_ADDRESS=manager-api:8080$" "$ENV"'
check '처음 설치하면 설정 파일을 설치 폴더에 두고 묶음 폴더에는 ../.env 링크만 둔다' \
  '[ -f "$ENV" ] && [ ! -L "$ENV" ] && [ -L "$BUNDLE/.env" ] && [ "$(readlink "$BUNDLE/.env")" = ../.env ]'
unset STDIN

setup; prepared; before=$(cat "$INSTALL/secrets/alertmanager_webhook_token" "$ENV")
run install
check '설치 준비를 다시 하면 token과 설정 파일을 덮어쓰지 않는다' \
  '[ $RC -eq 0 ] && contains "이미 있습니다" && [ "$(cat "$INSTALL/secrets/alertmanager_webhook_token" "$ENV")" = "$before" ]'

setup; prepared; mv "$ENV" "$BUNDLE/.env"; STDIN="$T/answer"; printf 'manager-api:8080\n' > "$STDIN"
run install
unset STDIN
check '설치 준비에서 설정 파일이 묶음 폴더에만 있으면 묶음 폴더의 파일은 쓰지 않는다고 안내하고 새로 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "묶음 폴더의 설정 파일은 쓰지 않습니다" && ! contains "sudo mv" && [ ! -e "$ENV" ]'

# 처음 설치(요구사항 0021의 S1): 옛 이름 묶음 폴더에 .env가 남아 있어도 보지 않고 설치 폴더에 새로 만든다.
setup; prepared; rm "$ENV"; mkdir "$INSTALL/monitoring-core-v1"
printf 'MONITORING_APP_ADDRESS=old-admin:9000\n' > "$INSTALL/monitoring-core-v1/.env"
before=$(cat "$TOKEN")
STDIN="$T/answer"; printf 'manager-api:8080\n' > "$STDIN"
run install
unset STDIN
check '설치 준비는 다른 묶음 폴더의 설정 파일을 보지 않고 설치 폴더에 새로 만들며 token은 그대로 둔다' \
  '[ $RC -eq 0 ] && ! contains "monitoring-core-v1" && grep -q "^MONITORING_APP_ADDRESS=manager-api:8080$" "$ENV" && [ "$(cat "$TOKEN")" = "$before" ]'

setup; STDIN="$T/answer"; printf 'bad address\n' > "$STDIN"
run install
unset STDIN
check '설치 준비에서 잘못된 주소를 넣으면 설정 파일을 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "관리자 서버 주소" && [ ! -e "$ENV" ]'

# --- 설정 파일 읽기 ---

setup; prepared; printf "export MONITORING_APP_ADDRESS='manager-api:8080' # 관리자 서버\n" > "$ENV"
run start
check 'export, 작은따옴표, 줄 끝 주석이 있는 설정 파일을 Compose처럼 읽는다' '[ $RC -eq 0 ] && called "^compose.* up"'

setup; prepared; mkdir "$T/fromshell"; mv "$INSTALL/secrets/alertmanager_webhook_token" "$T/fromshell/"
OUT=$(cd "$BUNDLE" && MONITORING_SECRETS_DIR="$T/fromshell" sh ./monitoring-server.sh start 2>&1 < /dev/null)
RC=$?
check '셸 환경 변수에 token 폴더가 있으면 Compose처럼 그 값을 쓴다' '[ $RC -eq 0 ] && called "^compose.* up"'

setup; touch "$FAKE/docker_down"; STDIN="$T/answer"; printf 'manager-api:8080\n' > "$STDIN"
run install
unset STDIN
check 'Docker가 꺼져 있으면 설치 준비에서 아무것도 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "Docker에 연결하지 못했습니다" && [ ! -e "$INSTALL/secrets" ] && [ ! -e "$ENV" ]'

setup; mkdir "$T/shared"; chmod 0755 "$T/shared"
printf 'MONITORING_APP_ADDRESS=manager-api:8080\nMONITORING_SECRETS_DIR=%s\n' "$T/shared" > "$ENV"
run install
check 'token을 이미 있는 폴더에 만들면 그 폴더의 권한을 바꾸지 않는다' \
  '[ $RC -eq 0 ] && [ "$(mode "$T/shared")" = drwxr-xr-x ] && [ -s "$T/shared/alertmanager_webhook_token" ]'

# --- 서버에서 바꾸는 설정 값(요구사항 0013) ---

# 설정 파일에 줄을 더한다. 인자 하나가 한 줄이다.
with_settings() {
  for line in "$@"; do
    printf '%s\n' "$line" >> "$ENV"
  done
}

# 셸 환경 변수 하나를 더해 실행한다. 값에 줄바꿈도 넣을 수 있다.
run_with_env() {
  assignment=$1
  shift
  OUT=$(cd "$BUNDLE" && env "$assignment" sh ./monitoring-server.sh "$@" 2>&1 < /dev/null)
  RC=$?
}

# 떠 있는 모니터링 시스템의 prometheus 컨테이너가 받은 보관 값. 빈 인자는 그 변수가 없는 것이다.
running_retention() {
  printf 'PATH=/bin\n' > "$FAKE/env.prometheus"
  [ -z "$1" ] || printf 'MONITORING_RETENTION_DAYS=%s\n' "$1" >> "$FAKE/env.prometheus"
  [ -z "$2" ] || printf 'MONITORING_RETENTION_SIZE_GB=%s\n' "$2" >> "$FAKE/env.prometheus"
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
  reject_in_env_file MONITORING_RETENTION_DAYS "$value" '1~365' '앞의 0 없이'
done
reject_in_env_file MONITORING_RETENTION_DAYS 0 '1~365'
reject_in_env_file MONITORING_RETENTION_DAYS 366 '1~365'
reject_in_env_file MONITORING_RETENTION_SIZE_GB 50GB '1 이상'
reject_in_env_file MONITORING_RETENTION_SIZE_GB 0 '1 이상'
reject_in_env_file MONITORING_RETENTION_SIZE_GB 1000000000 '999999999'
for value in 5s '5|g' '5&' 'prober:'; do
  reject_in_env_file MONITORING_PROBE_TIMEOUT_SECONDS "$value" '1~9'
done
reject_in_env_file MONITORING_PROBE_TIMEOUT_SECONDS 0 '1~9' '10초 안에'
reject_in_env_file MONITORING_PROBE_TIMEOUT_SECONDS 10 '1~9' '10초 안에'
reject_in_env_file MONITORING_PROMETHEUS_PORT 65536 '1024~65535'
reject_in_env_file MONITORING_ALERTMANAGER_PORT 80 '1024~65535'
reject_in_env_file MONITORING_BLACKBOX_PORT 1023 '1024~65535'
reject_in_env_file MONITORING_PROMETHEUS_PORT '0.0.0.0:9090' '1024~65535'
reject_in_env_file MONITORING_BLACKBOX_PORT '9115-9120' '1024~65535'

setup; prepared; with_settings 'MONITORING_RETENTION_DAYS=$(touch '"$T"'/injected)' 'MONITORING_PROBE_TIMEOUT_SECONDS=`touch '"$T"'/injected`'
run start
check '설정 값에 든 셸 명령은 실행하지 않고 형식 오류로 거부한다' \
  '[ $RC -ne 0 ] && [ ! -e "$T/injected" ] && contains "MONITORING_RETENTION_DAYS" && contains "MONITORING_PROBE_TIMEOUT_SECONDS" && ! called "^compose"'

name=MONITORING_PROBE_TIMEOUT_SECONDS value='prober: tcp' range='1~9' extra=''
setup; prepared
run_with_env "MONITORING_PROBE_TIMEOUT_SECONDS=5
prober: tcp" start
check '셸 환경 변수의 값에 줄바꿈과 YAML을 넣으면 거부한다' "$REJECTED"

setup; prepared; with_settings MONITORING_RETENTION_DAYS=30
name=MONITORING_RETENTION_DAYS value=abc range='1~365' extra=''
run_with_env MONITORING_RETENTION_DAYS=abc start
check '셸 환경 변수가 설정 파일 값을 덮으면 셸 환경 변수의 값을 검사한다' "$REJECTED"

setup; prepared; with_settings MONITORING_RETENTION_DAYS=abc
run_with_env MONITORING_RETENTION_DAYS=30 start
check '셸 환경 변수의 값이 맞으면 설정 파일의 틀린 값과 관계없이 그 값으로 띄운다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && grep -q "^MONITORING_RETENTION_DAYS=30$" "$FAKE/env.prometheus"'

setup; prepared; with_settings 'MONITORING_RETENTION_DAYS=abc' 'MONITORING_PROBE_TIMEOUT_SECONDS=10'
run start
check '틀린 값이 여럿이면 모두 한 번에 보인다' \
  '[ $RC -ne 0 ] && contains "MONITORING_RETENTION_DAYS" && contains "MONITORING_PROBE_TIMEOUT_SECONDS" && ! called "^compose"'

setup; prepared; with_settings MONITORING_RETENTION_DAYS= MONITORING_RETENTION_SIZE_GB= MONITORING_PROBE_TIMEOUT_SECONDS= \
  MONITORING_PROMETHEUS_PORT= MONITORING_ALERTMANAGER_PORT= MONITORING_BLACKBOX_PORT=
run start
check '값이 모두 비어 있으면 기본값으로 띄우고 모두 기본값이라고 보인다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && contains "모두 기본값"'

setup; prepared
run start
check '설정 파일에 보관·포트 값이 없으면 모두 기본값이라고 보인다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && contains "모두 기본값" && ! contains "기본값과 다른"'

setup; prepared; with_settings CORE_RETENTION_DAYS=30 CORE_PROMETHEUS_PORT=19090
run start
check '옛 이름(CORE_)으로 적은 값은 쓰지 않고 기본값으로 띄운다(요구사항 0020)' \
  '[ $RC -eq 0 ] && called "^compose.* up" && grep -q "^MONITORING_RETENTION_DAYS=15$" "$FAKE/env.prometheus" && ! grep -q "CORE_" "$FAKE/env.prometheus" && contains "모두 기본값"'

setup; prepared; with_settings 'MONITORING_RETENTION_DAYS=" 30 "' MONITORING_PROMETHEUS_PORT=19090
run start
check '허용 범위 안의 값이면 앞뒤 공백을 지운 값으로 띄우고 기본값과 다른 값만 보인다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && contains "30일" && contains "19090" && ! contains "확인 제한 시간" && ! contains "모두 기본값"'

setup; prepared; with_settings MONITORING_RETENTION_DAYS=365 MONITORING_RETENTION_SIZE_GB=999999999 MONITORING_PROBE_TIMEOUT_SECONDS=9 \
  MONITORING_PROMETHEUS_PORT=65535 MONITORING_ALERTMANAGER_PORT=1024 MONITORING_BLACKBOX_PORT=1025
run start
check '허용 범위 양 끝의 값이면 띄운다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && contains "365일" && contains "999999999GB" && contains "9초" && contains "65535"'

setup; prepared; touch "$FAKE/port_taken"
run start
check '다른 프로그램이 쓰는 포트라 띄우지 못하면 포트를 바꾸는 방법, 정상이 아닌 컨테이너, 로그 명령을 보인다' \
  '[ $RC -ne 0 ] && contains "port is already allocated" && contains "MONITORING_PROMETHEUS_PORT" && contains "prometheus: created" && contains "docker logs prometheus" && ! contains "docker logs alertmanager"'

# --- 기록 보관 값을 줄일 때의 확인 ---

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 100; with_settings MONITORING_RETENTION_DAYS=15 MONITORING_RETENTION_SIZE_GB=100
run start
check '보관 기간을 줄이는데 터미널이 아니면 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "30일" && contains "15일" && contains "되돌릴 수 없습니다" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 100; with_settings MONITORING_RETENTION_DAYS=30 MONITORING_RETENTION_SIZE_GB=50
run start
check '보관 용량을 줄이는데 터미널이 아니면 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "100GB" && contains "50GB" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''; with_settings MONITORING_RETENTION_DAYS=30 MONITORING_RETENTION_SIZE_GB=50
run start
check '제한이 없던 보관 용량을 처음 정하면 줄임으로 보고 터미널이 아니면 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "제한 없음" && contains "50GB" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention '' ''; with_settings MONITORING_RETENTION_DAYS=10
run start
check '보관 값 변수가 없는 컨테이너는 15일로 보고, 그보다 줄이면 터미널이 아니면 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "15일" && contains "10일" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention '' ''; with_settings MONITORING_RETENTION_DAYS=30
run start
check '보관 값 변수가 없는 컨테이너에서 보관 기간을 늘리면 묻지 않고 띄운다' \
  '[ $RC -eq 0 ] && ! contains "계속할까요" && called "^compose.* up"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 100; with_settings MONITORING_RETENTION_DAYS=30 MONITORING_RETENTION_SIZE_GB=100
run start
check '보관 값이 떠 있는 모니터링 시스템과 같으면 묻지 않고 띄운다' \
  '[ $RC -eq 0 ] && ! contains "계속할까요" && called "^compose.* up"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 50; with_settings MONITORING_RETENTION_DAYS=30
run start
check '보관 용량 제한을 없애면 줄임이 아니므로 묻지 않고 띄운다' \
  '[ $RC -eq 0 ] && ! contains "계속할까요" && called "^compose.* up"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''; touch "$FAKE/stopped"
run start
check '멈춘 모니터링 시스템의 보관 기간보다 줄여도 확인을 묻는다' '[ $RC -ne 0 ] && contains "되돌릴 수 없습니다" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
run_in_terminal n start
check '보관 기간을 줄일지 물었을 때 n이면 띄우지 않고 정상 종료한다' \
  '[ $RC -eq 0 ] && contains "계속할까요" && contains "모니터링 시스템은 바꾸지 않았습니다" && ! called "^compose"'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
run_in_terminal y start
check '보관 기간을 줄일지 물었을 때 y이면 띄운다' \
  '[ $RC -eq 0 ] && contains "계속할까요" && called "^compose.* up"'

# --- 상태 보기 ---

setup; running_other v1 1761500000 "$INSTALL/monitoring-system-v1"
run status
check '상태를 보면 모니터링 시스템의 버전, 커밋, 컨테이너 상태와 준비 여부, 묶음과의 버전 차이를 보인다' \
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
check '모니터링 시스템이 멈춰 있으면 떠 있다고 보이지 않고 준비 확인 주소를 부르지 않는다' \
  'contains "멈춰 있습니다" && ! contains "떠 있는 버전" && contains "prometheus: exited" && [ ! -f "$FAKE/ready_calls" ]'

setup; running_other v2 1761600000 "$BUNDLE"
OUT=$(cd "$BUNDLE" && MONITORING_READY_TOOLS="" sh ./monitoring-server.sh status 2>&1 < /dev/null)
RC=$?
check 'curl과 wget이 없으면 상태에서 준비 여부를 확인하지 못했다고 보인다' \
  '[ $RC -eq 0 ] && contains "prometheus: running, 준비 확인 못 함"'

setup
run status
check '모니터링 시스템이 없으면 없다고 보인다' '[ $RC -eq 0 ] && contains "모니터링 시스템이 없습니다"'

setup; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
printf '{"9090/tcp":[{"HostIp":"127.0.0.1","HostPort":"19090"}]}\n' > "$FAKE/ports.prometheus"
run status
check '상태를 보면 컨테이너가 받은 값 가운데 기본값과 다른 것만 보인다' \
  '[ $RC -eq 0 ] && contains "기본값과 다른 설정 값" && contains "30일" && contains "127.0.0.1:19090" && ! contains "확인 제한 시간"'

setup; running_other v2 1761600000 "$BUNDLE"; touch "$FAKE/stopped"
printf '{"9115/tcp":[{"HostIp":"127.0.0.1","HostPort":"19115"}]}\n' > "$FAKE/ports.blackbox-exporter"
run status
check '모니터링 시스템이 멈춰 있어도 바꾼 포트를 보인다' '[ $RC -eq 0 ] && contains "멈춰 있습니다" && contains "127.0.0.1:19115"'

setup; running_other v2 1761600000 "$BUNDLE"
run status
check '상태를 보면 값이 모두 기본값일 때 기본값이라고 보인다' '[ $RC -eq 0 ] && contains "모두 기본값"'

setup; running_other v2 1761600000 "$BUNDLE"; printf 'MONITORING_PROBE_TIMEOUT_SECONDS=10\n' > "$FAKE/env.blackbox-exporter"
run status
check '컨테이너가 받은 값이 틀리면 기본값이라고 하지 않고 그 값을 올바르지 않다고 보인다' \
  '[ $RC -eq 0 ] && contains "확인 제한 시간 '"'"'10'"'"'(올바르지 않음)" && ! contains "모두 기본값"'

# --- 설정 펼치기(요구사항 0015) ---

# 이전에 펼친 설정이 있는 상태와, 그 설정이 그대로인지 보는 조건
previously_rendered() {
  mkdir -p "$RENDERED"
  for name in prometheus.yml alertmanager.yml blackbox.yml monitoring.env; do
    printf 'previous\n' > "$RENDERED/$name"
  done
}
unchanged() {
  [ "$(ls -A "$RENDERED" | wc -l | tr -d ' ')" -eq 4 ] && [ "$(cat "$RENDERED"/* | sort -u)" = previous ]
}
config_hash() { sed -n 's/^MONITORING_CONFIG_SHA256=//p' "$RENDERED/monitoring.env"; }

setup; prepared
run render
check '설정을 펼치면 검사한 주소와 기본값으로 세 설정을 rendered/에 쓰고 이미지와 컨테이너는 바꾸지 않는다' \
  '[ $RC -eq 0 ] && [ "$(rendered_address)" = "manager-api:8080" ] && grep -q "^      time: 15d$" "$RENDERED/prometheus.yml" && grep -q "^      size: 0B$" "$RENDERED/prometheus.yml" && [ "$(grep -c "^    timeout: 5s$" "$RENDERED/blackbox.yml")" -eq 4 ] && grep -q "http://manager-api:8080/api/integrations/alertmanager/webhook" "$RENDERED/alertmanager.yml" && ! called "^compose" && ! called load'
check '설정을 펼치면 Compose로 띄우는 방법과 .env를 바꾸면 다시 펼쳐야 한다는 것을 보인다' \
  'contains "docker compose up -d" && contains "다시 실행"'
check '펼친 설정에 자리표시와 token 값이 남지 않는다' \
  '! grep -rq "@[A-Z][A-Z_]*@" "$RENDERED" && ! grep -rq "secret-value-for-test" "$RENDERED" && ! contains "secret-value-for-test"'
check '펼친 설정은 운영자만 고칠 수 있고 컨테이너 사용자가 읽을 수 있다' \
  '[ "$(mode "$RENDERED")" = drwxr-xr-x ] && [ "$(mode "$RENDERED/prometheus.yml")" = -rw-r--r-- ] && [ "$(mode "$RENDERED/alertmanager.yml")" = -rw-r--r-- ] && [ "$(mode "$RENDERED/blackbox.yml")" = -rw-r--r-- ] && [ "$(mode "$RENDERED/monitoring.env")" = -rw-r--r-- ]'
check '설정을 펼치면 컨테이너에 넘길 값을 monitoring.env에 쓰고 CA 인증서 파일은 옮기지 않는다' \
  '[ ! -e "$RENDERED/ca-certificates.crt" ] && [ "$(cat "$CA")" = "TEST CA 1" ] && grep -q "^MONITORING_RETENTION_DAYS=15$" "$RENDERED/monitoring.env" && grep -q "^MONITORING_RETENTION_SIZE_GB=$" "$RENDERED/monitoring.env" && grep -q "^MONITORING_PROBE_TIMEOUT_SECONDS=5$" "$RENDERED/monitoring.env" && [ "$(config_hash | grep -c "^[0-9a-f]\{64\}$")" -eq 1 ]'
check '설정을 펼친 뒤 임시 파일을 남기지 않는다' \
  '[ "$(ls -A "$RENDERED" | sort | tr "\n" " ")" = "alertmanager.yml blackbox.yml monitoring.env prometheus.yml " ]'

# umask가 좁은 서버에서도 컨테이너 사용자(nobody)가 읽을 수 있어야 한다.
setup; prepared
umask 077
run render
umask 022
check 'umask가 077이어도 펼친 설정은 컨테이너 사용자가 읽을 수 있다' \
  '[ $RC -eq 0 ] && [ "$(mode "$RENDERED")" = drwxr-xr-x ] && [ "$(mode "$RENDERED/prometheus.yml")" = -rw-r--r-- ] && [ "$(mode "$RENDERED/monitoring.env")" = -rw-r--r-- ]'

# 펼친 설정은 떠 있는 컨테이너가 다시 시작할 때 읽을 수 있어, 보관 값을 줄이면 설정 펼치기도 확인을 묻는다.
setup; prepared; previously_rendered; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
run render
check '떠 있는 모니터링 시스템보다 보관 기간을 줄여 펼치는데 터미널이 아니면 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "되돌릴 수 없습니다" && unchanged'

setup; prepared; running_other v2 1761600000 "$BUNDLE"; running_retention 30 ''
run_in_terminal y render
check '떠 있는 모니터링 시스템보다 보관 기간을 줄여 펼칠지 물었을 때 y이면 펼친다' \
  '[ $RC -eq 0 ] && contains "계속할까요" && grep -q "^      time: 15d$" "$RENDERED/prometheus.yml"'

# Docker에 닿지 못하면 떠 있는 모니터링 시스템의 보관 값과 비교할 수 없다. 그 사실을 알린다.
setup; prepared; touch "$FAKE/docker_down"
run render
check 'Docker에 연결하지 못하면 설정 펼치기가 보관 값을 줄였는지 확인하지 못했다고 경고한다' \
  '[ $RC -eq 0 ] && contains "줄였는지 확인하지 못했습니다" && [ -f "$RENDERED/monitoring.env" ]'

setup; prepared; with_settings MONITORING_RETENTION_DAYS=30 MONITORING_RETENTION_SIZE_GB=50 MONITORING_PROBE_TIMEOUT_SECONDS=9
run render
check '바꾼 설정 값으로 보관 항목과 확인 제한 시간을 펼치고 monitoring.env에도 쓴다' \
  '[ $RC -eq 0 ] && grep -q "^      time: 30d$" "$RENDERED/prometheus.yml" && grep -q "^      size: 50GB$" "$RENDERED/prometheus.yml" && [ "$(grep -c "^    timeout: 9s$" "$RENDERED/blackbox.yml")" -eq 4 ] && grep -q "^MONITORING_RETENTION_SIZE_GB=50$" "$RENDERED/monitoring.env"'

setup; prepared; rm "$ENV"
run render
check '설정 파일이 없으면 설정 펼치기도 설치 준비를 안내하고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "sh monitoring-server.sh install" && [ ! -e "$RENDERED" ]'

setup; prepared; printf 'x' >> "$BUNDLE/templates/prometheus.yml"
run render
check '묶음의 템플릿이 바뀌었으면 체크섬 오류를 보이고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "templates/prometheus.yml" && contains "체크섬" && [ ! -e "$RENDERED" ]'

# 형식이 틀린 관리자 서버 주소와 설정 값: 빌드 PC의 설정 검사가 시작 스크립트로 확인하던 목록이다.
for address in '' 'manager-api' 'http://manager-api:8080' 'manager-api:0' 'manager-api:70000' 'a:b:8080' '-manager:8080' \
  'manager api:8080' 'manager|api:8080' 'manager&api:8080' 'manager;api:8080' '$(touch${IFS}injected):8080'; do
  setup; prepared; previously_rendered; printf 'MONITORING_APP_ADDRESS=%s\n' "$address" > "$ENV"
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
for pair in 'MONITORING_RETENTION_DAYS=0' 'MONITORING_RETENTION_DAYS=15 --web.enable-admin-api' 'MONITORING_RETENTION_SIZE_GB=abc' \
  'MONITORING_PROBE_TIMEOUT_SECONDS=10' 'MONITORING_PROBE_TIMEOUT_SECONDS=5|g' 'MONITORING_PROBE_TIMEOUT_SECONDS=5&'; do
  setup; prepared; previously_rendered; with_settings "$pair"
  run render
  check "설정 펼치기는 $pair 값을 거부하고 이전에 펼친 설정을 바꾸지 않는다" \
    '[ $RC -ne 0 ] && contains "${pair%%=*}" && contains "허용 범위" && unchanged'
done
setup; prepared; previously_rendered
run_with_env "MONITORING_PROBE_TIMEOUT_SECONDS=5
prober: tcp" render
check '설정 펼치기는 셸 환경 변수의 값에 줄바꿈과 YAML이 있으면 거부한다' \
  '[ $RC -ne 0 ] && contains "MONITORING_PROBE_TIMEOUT_SECONDS" && unchanged'
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

# --- CA 인증서 파일(요구사항 0015의 S3, S4, S13) ---
# 운영자가 가이드대로 설치 폴더의 certs/에 복사한다. 운영 명령은 OS의 파일을 읽거나 옮기지 않고 확인만 한다.

COPY_HINT='contains "/etc/ssl/certs/ca-certificates.crt" && contains "/etc/pki/tls/certs/ca-bundle.crt" && contains "docs/OPERATIONS.md"'

setup; prepared; rm "$CA"
run render
check 'CA 인증서 파일이 없으면 복사할 곳과 OS의 파일 위치를 보이고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "$CA" && '"$COPY_HINT"' && [ ! -e "$RENDERED" ]'

setup; prepared; rm "$CA"
run start
check 'CA 인증서 파일이 없으면 띄우기도 이미지를 불러오거나 컨테이너를 바꾸지 않는다' \
  '[ $RC -ne 0 ] && contains "$CA" && ! called load && ! called "^compose"'

setup; prepared; : > "$CA"
run render
check 'CA 인증서 파일이 비어 있으면 알리고 펼치지 않는다' '[ $RC -ne 0 ] && contains "비어 있습니다" && contains "$CA" && [ ! -e "$RENDERED" ]'

setup; prepared; rm "$CA"; mkdir "$CA"
run render
check 'CA 인증서 파일 자리에 폴더가 있으면 알리고 펼치지 않는다' '[ $RC -ne 0 ] && contains "폴더가 있습니다" && [ ! -e "$RENDERED" ]'

setup; prepared; chmod 0600 "$CA"
run render
check '컨테이너가 CA 인증서 파일을 읽을 수 없으면 권한을 바꾸라고 알리고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "읽을 수 없습니다" && contains "0644" && [ ! -e "$RENDERED" ]'

setup; prepared; no_find
run render
check 'find가 없는 서버에서도 CA 인증서 파일의 읽기 권한을 확인한다' '[ $RC -eq 0 ]'

# --- CA 인증서 파일 경로(요구사항 0027) ---
# 운영자가 .env의 MONITORING_CA_FILE에 OS의 CA 인증서 파일 경로를 적으면 복사본 대신 그 파일을 쓴다. 링크는 따라간다.

ca_hash() { sed -n 's/^MONITORING_CA_SHA256=//p' "$RENDERED/monitoring.env"; }
file_hash() { sha256sum < "$1" | cut -d ' ' -f 1; }
# OS의 CA 인증서 파일을 흉내 낸다. ca-bundle.crt는 실제 파일(real/bundle.crt)을 가리키는 링크다.
os_ca() {
  OS="$T/os"
  mkdir -p "$OS/real"
  printf 'OS CA 1\n' > "$OS/real/bundle.crt"
  chmod 0644 "$OS/real/bundle.crt"
  ln -s "$OS/real/bundle.crt" "$OS/ca-bundle.crt"
}
# 경로를 적은 경우의 안내는 OS의 파일을 지우거나 권한을 바꾸라고 하지 않는다.
NO_OS_CHANGE='! contains "rmdir" && ! contains "rm " && ! contains "chmod" && ! contains "지운 뒤"'

setup; prepared; rm "$CA"; os_ca; with_settings "MONITORING_CA_FILE=$OS/real/bundle.crt"
run render
check 'CA 인증서 파일 경로를 적으면 설치 폴더의 복사본 없이 그 파일로 펼치고 CA 해시를 그 파일로 계산한다' \
  '[ $RC -eq 0 ] && [ "$(ca_hash)" = "$(file_hash "$OS/real/bundle.crt")" ]'

setup; prepared; os_ca
run_with_env "MONITORING_CA_FILE=$OS/real/bundle.crt" render
check '셸 환경 변수로 CA 인증서 파일 경로를 줘도 그 파일로 펼친다' \
  '[ $RC -eq 0 ] && [ "$(ca_hash)" = "$(file_hash "$OS/real/bundle.crt")" ]'

setup; prepared; os_ca; with_settings "MONITORING_CA_FILE=$OS/ca-bundle.crt"
run start
check '띄우면 검사한 CA 인증서 파일 경로를 Compose에 넘긴다' \
  '[ $RC -eq 0 ] && [ "$(cat "$FAKE/compose_ca_file")" = "$OS/ca-bundle.crt" ]'

setup; prepared; os_ca; with_settings "MONITORING_CA_FILE=$OS/ca-bundle.crt"
run render
check 'CA 인증서 파일 경로가 링크면 링크가 가리키는 파일로 펼친다' \
  '[ $RC -eq 0 ] && [ "$(ca_hash)" = "$(file_hash "$OS/real/bundle.crt")" ]'

for broken in directory missing empty unreadable; do
  setup; prepared; os_ca
  case "$broken" in
    directory) rm "$OS/real/bundle.crt"; mkdir "$OS/real/bundle.crt" ;;
    missing) rm "$OS/real/bundle.crt" ;;
    empty) : > "$OS/real/bundle.crt" ;;
    unreadable) chmod 0600 "$OS/real/bundle.crt" ;;
  esac
  with_settings "MONITORING_CA_FILE=$OS/ca-bundle.crt"
  run render
  # 없는 파일을 가리키는 링크는 실제 경로를 풀 수 없는 셸도 있어, 실제 경로는 파일이 있는 경우만 본다.
  real_shown='contains "$OS/real/bundle.crt"'
  [ "$broken" != missing ] || real_shown=true
  check "CA 인증서 파일 경로의 링크가 쓸 수 없는 파일($broken)을 가리키면 .env의 값을 고치라고 알리고 펼치지 않는다" \
    '[ $RC -ne 0 ] && contains "쓸 수 없습니다: $OS/ca-bundle.crt" && contains ".env의 MONITORING_CA_FILE 값을 고친 뒤" && '"$real_shown"' && '"$NO_OS_CHANGE"' && [ ! -e "$RENDERED" ]'
done

setup; prepared; os_ca; rm "$OS/real/bundle.crt"
run_with_env "MONITORING_CA_FILE=$OS/ca-bundle.crt" render
check '셸 환경 변수의 CA 인증서 파일 경로를 쓸 수 없으면 셸의 값을 지우거나 고치라고 알리고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "쓸 수 없습니다" && contains "unset MONITORING_CA_FILE" && '"$NO_OS_CHANGE"' && [ ! -e "$RENDERED" ]'

# Compose의 .env 읽기는 운영 명령이 읽지 않는 형식도 받는다. 0027에서는 복사본을 붙이고 띄웠지만,
# 운영자가 적은 파일 대신 복사본을 쓰는 줄 모르므로 멈춘다(요구사항 0028의 S9).
setup; prepared; os_ca; printf 'MONITORING_CA_FILE: %s\n' "$OS/ca-bundle.crt" >> "$ENV"
run start
check 'CA 인증서 파일 경로를 운영 명령이 읽지 않는 표기로 적으면 복사본이 있어도 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "MONITORING_CA_FILE=경로" && ! called "^compose" && [ ! -e "$RENDERED" ]'

setup; prepared
run start
check 'CA 인증서 파일 경로가 없으면 Compose에 빈 값을 넘겨 검사한 복사본을 붙이게 한다' \
  '[ $RC -eq 0 ] && [ -z "$(cat "$FAKE/compose_ca_file")" ] && [ "$(ca_hash)" = "$(file_hash "$CA")" ]'

setup; prepared; os_ca; with_settings "MONITORING_CA_FILE=$OS/ca-bundle.crt"
run start
check 'CA 인증서 파일 경로를 적으면 Compose에 검사한 경로를 넘긴다' \
  '[ $RC -eq 0 ] && [ "$(cat "$FAKE/compose_ca_file")" = "$OS/ca-bundle.crt" ]'

setup; prepared; with_settings 'MONITORING_CA_FILE='
run render
check 'CA 인증서 파일 경로를 비우면 설치 폴더의 복사본으로 펼친다' \
  '[ $RC -eq 0 ] && [ "$(ca_hash)" = "$(file_hash "$CA")" ]'

setup; prepared; rm "$CA"
run render
check 'CA 인증서 파일 경로를 적지 않았고 복사본도 없으면 .env에 적을 OS의 파일 경로를 안내한다' \
  '[ $RC -ne 0 ] && contains "MONITORING_CA_FILE" && '"$COPY_HINT"' && [ ! -e "$RENDERED" ]'

setup; prepared; os_ca; with_settings "MONITORING_CA_FILE=$OS/ca-bundle.crt"; started
run start
check 'CA 인증서 파일 경로의 내용이 그대로면 띄울 때 컨테이너를 다시 만들지 않는다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && ! called "force-recreate"'
rm -f "$FAKE/calls"; printf 'OS CA 2\n' > "$OS/real/bundle.crt"
run start
check 'OS의 CA 인증서 파일이 바뀐 뒤 띄우면 컨테이너를 다시 만든다' \
  '[ $RC -eq 0 ] && called "^compose.* up -d --force-recreate"'

for value in 'certs/ca-certificates.crt' '/tmp/a b.crt' '/a:/b' '/tmp/인증서.crt' '/tmp/$HOME.crt' '/etc/ssl/certs/ca.crt:rw'; do
  setup; prepared; with_settings "MONITORING_CA_FILE=$value"
  run render
  check "CA 인증서 파일 경로가 허용 형식이 아니면($value) 값 이름과 형식을 알리고 펼치지 않는다" \
    '[ $RC -ne 0 ] && contains "MONITORING_CA_FILE" && contains "절대 경로" && [ ! -e "$RENDERED" ]'
done

setup; prepared; os_ca
run_with_env "MONITORING_CA_FILE=$OS/real/bundle.crt
/etc/passwd" render
check 'CA 인증서 파일 경로가 여러 줄이면 거부하고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "MONITORING_CA_FILE" && [ ! -e "$RENDERED" ]'

# Compose는 셸 환경 변수의 값 끝 줄바꿈까지 볼륨 원본으로 쓴다(요구사항 0028의 S15).
setup; prepared; os_ca
run_with_env "MONITORING_CA_FILE=$OS/real/bundle.crt
" start
check '셸 환경 변수의 CA 인증서 파일 경로 끝에 줄바꿈이 있으면 형식 검사에서 거부하고 Compose를 부르지 않는다' \
  '[ $RC -ne 0 ] && contains "MONITORING_CA_FILE" && contains "절대 경로" && ! called "^compose" && [ ! -e "$RENDERED" ]'

setup; prepared
run render
first=$(config_hash)
printf 'TEST CA 2\n' > "$CA"
run render
check '운영자가 CA 인증서 파일을 다시 복사하면 다시 펼칠 때 monitoring.env의 설정 값이 바뀐다' \
  '[ $RC -eq 0 ] && [ "$(config_hash)" != "$first" ]'

setup; prepared; started; printf 'TEST CA 2\n' > "$CA"
run start
check '운영자가 CA 인증서 파일을 다시 복사한 뒤 띄우면 컨테이너를 다시 만든다' \
  '[ $RC -eq 0 ] && called "^compose.* up -d --force-recreate"'

setup; prepared
run render
first=$(config_hash)
printf 'MONITORING_APP_ADDRESS=other-api:9000\n' > "$ENV"
run render
check '관리자 서버 주소를 바꿔 다시 펼치면 Compose가 컨테이너를 다시 만들도록 monitoring.env의 설정 값이 바뀐다' \
  '[ $RC -eq 0 ] && [ "$(rendered_address)" = "other-api:9000" ] && [ "$(config_hash)" != "$first" ]'

# --- 경로 설정 값의 .env 표기(요구사항 0028) ---
# 운영 명령이 읽지 않는 표기로 token 폴더나 CA 인증서 파일 경로를 적은 줄이 있으면, Compose가 그 줄을 다르게 읽을 수 있어
# 그 값을 다루는 명령이 아무것도 바꾸지 않고 멈춘다. 표기는 2026-11-25에 Compose와 운영 명령에 같은 .env를 주어 찾은 것이다.

BOM=$(printf '\357\273\277')
UNREAD_FORMS='1 2 3 4 5 6 7 8 9 10'
# 운영 명령이 읽지 않거나 Compose와 다른 값으로 읽는 표기(세 번째 인자의 번호)로 변수(첫 번째 인자)에 값(두 번째 인자)을 적는다.
# 3번은 파일의 첫 줄에 둔다. 10번은 운영 명령이 읽는 값(first_value)을 앞에 두고 같은 줄에 Compose가 읽는 값을 이어 쓴다.
write_unread() {
  case "$3" in
    1) printf '%s: %s\n' "$1" "$2" >> "$ENV" ;;
    2) printf '%s:%s\n' "$1" "$2" >> "$ENV" ;;
    3) { printf '%s%s=%s\n' "$BOM" "$1" "$2"; cat "$ENV"; } > "$ENV.new" && mv "$ENV.new" "$ENV" ;;
    4) printf '\302\240%s=%s\n' "$1" "$2" >> "$ENV" ;;
    5) printf '\302\205%s=%s\n' "$1" "$2" >> "$ENV" ;;
    6) printf 'OTHER="a" %s=%s\n' "$1" "$2" >> "$ENV" ;;
    7) printf "OTHER='a' %s=%s\n" "$1" "$2" >> "$ENV" ;;
    8) printf '\343\200\200%s=%s\n' "$1" "$2" >> "$ENV" ;;
    9) printf '\342\200\203%s=%s\n' "$1" "$2" >> "$ENV" ;;
    10) printf '%s="%s" %s=%s\n' "$1" "$first_value" "$1" "$2" >> "$ENV" ;;
  esac
}
# token 파일 두 곳과 설정 파일의 지금 상태. 내용은 출력하지 않고 체크섬만 비교한다.
token_state() {
  for f in "$TOKEN" "$OTHER_TOKEN" "$ENV"; do
    if [ -e "$f" ]; then cksum < "$f"; else echo none; fi
  done
}
# 기본 위치(첫 번째 인자)와 적은 폴더(두 번째 인자)에 token을 둘지(yes, no) 정한다. 적은 폴더는 $T/other다.
place_tokens() {
  OTHER_TOKEN="$T/other/alertmanager_webhook_token"
  [ "$1" = yes ] || rm -f "$TOKEN"
  if [ "$2" = yes ]; then
    mkdir -p "$T/other"
    printf 'other-token-for-test\n' > "$OTHER_TOKEN"
    chmod 0644 "$OTHER_TOKEN"
  fi
}
# 안내의 줄 번호(unread_line)는 시험이 그 표기를 적은 줄이다.
UNREAD_STOPPED='[ $RC -ne 0 ] && contains "의 ${unread_line}번째 줄" && contains "$unread_fix" && contains "다시 입력" && ! called "^compose" && ! called load && [ ! -e "$RENDERED" ] && ! contains "secret-value-for-test" && ! contains "other-token-for-test"'

for form in $UNREAD_FORMS; do
  for tokens in 'yes yes' 'yes no' 'no yes' 'no no'; do
    for command in start render install; do
      set -- $tokens
      setup; prepared; place_tokens "$1" "$2"; first_value="$INSTALL/secrets"; write_unread MONITORING_SECRETS_DIR "$T/other" "$form"
      before=$(token_state)
      unread_fix='MONITORING_SECRETS_DIR=폴더'; unread_line=2; [ "$form" != 3 ] || unread_line=1
      run "$command"
      check "token 폴더를 운영 명령이 읽지 않는 표기($form)로 적으면 기본 위치($1)와 적은 폴더($2)의 token과 관계없이 $command가 아무것도 바꾸지 않고 멈춘다" \
        "$UNREAD_STOPPED"' && [ "$(token_state)" = "$before" ]'
    done
  done
done

setup; prepared; place_tokens yes yes; write_unread MONITORING_SECRETS_DIR "$T/other" 1; unread_fix='MONITORING_SECRETS_DIR=폴더'; unread_line=2
run_with_env "MONITORING_SECRETS_DIR=$INSTALL/secrets" start
check '셸 환경 변수로 token 폴더를 줘도 설정 파일에 운영 명령이 읽지 않는 표기의 줄이 있으면 멈춘다' "$UNREAD_STOPPED"

setup; prepared; place_tokens yes yes; with_settings "MONITORING_SECRETS_DIR=$INSTALL/secrets"
write_unread MONITORING_SECRETS_DIR "$T/other" 1; unread_fix='MONITORING_SECRETS_DIR=폴더'; unread_line=3
run start
check '읽는 형식의 줄과 읽지 않는 표기의 줄이 함께 있어도 멈춘다' "$UNREAD_STOPPED"

for form in $UNREAD_FORMS; do
  for files in 'yes yes' 'yes no' 'no yes' 'no no'; do
    for command in start render; do
      set -- $files
      setup; prepared; [ "$1" = yes ] && os_ca; [ "$2" = yes ] || rm "$CA"
      first_value="$CA"; write_unread MONITORING_CA_FILE "$T/os/ca-bundle.crt" "$form"
      unread_fix='MONITORING_CA_FILE=경로'; unread_line=2; [ "$form" != 3 ] || unread_line=1
      run "$command"
      check "CA 인증서 파일 경로를 운영 명령이 읽지 않는 표기($form)로 적으면 적은 파일($1)과 복사본($2)과 관계없이 $command가 멈춘다" \
        "$UNREAD_STOPPED"
    done
  done
done

setup; prepared; os_ca; write_unread MONITORING_CA_FILE "$OS/ca-bundle.crt" 1; unread_fix='MONITORING_CA_FILE=경로'; unread_line=2
run_with_env "MONITORING_CA_FILE=$OS/ca-bundle.crt" start
check '셸 환경 변수로 CA 인증서 파일 경로를 줘도 설정 파일에 운영 명령이 읽지 않는 표기의 줄이 있으면 멈춘다' "$UNREAD_STOPPED"

# 줄 앞 공백 뒤가 #인 주석 줄과 이름이 더 긴 다른 변수의 줄은 Compose도 그 변수로 읽지 않으므로 멈추지 않는다.
setup; prepared
printf '# MONITORING_SECRETS_DIR: %s\n  # MONITORING_SECRETS_DIR=%s\n\t#MONITORING_CA_FILE: /x\n\013# MONITORING_CA_FILE=/x\n\014#MONITORING_SECRETS_DIR=/x\nMONITORING_SECRETS_DIR_OLD: /x\nMONITORING_CA_FILE2: /x\nXMONITORING_CA_FILE=/x\n' "$T/other" "$T/other" >> "$ENV"
run start
check '주석 줄과 이름이 더 긴 변수의 줄은 읽지 않는 줄로 보지 않고 띄운다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && [ -z "$(cat "$FAKE/compose_secrets_dir")" ]'

# 다른 변수의 값이나 줄 끝 주석에 이름이 들어 있으면 Compose가 그 변수로 읽는지 가리지 않고 멈춘다. 드물고, 멈추는 쪽이 안전하다.
# 그 줄을 이름=값 형식으로 고치면 다른 변수를 잃으므로, 안내는 값이나 주석에서 이름을 빼라고도 한다.
for name in MONITORING_SECRETS_DIR MONITORING_CA_FILE; do
  for where in value comment; do
    setup; prepared
    case "$where" in
      value) printf 'NOTE=see %s for details\n' "$name" >> "$ENV" ;;
      comment) printf 'MONITORING_RETENTION_DAYS=15 # %s 참고\n' "$name" >> "$ENV" ;;
    esac
    case "$name" in MONITORING_SECRETS_DIR) unread_fix="$name=폴더" ;; *) unread_fix="$name=경로" ;; esac
    unread_line=2
    run start
    check "다른 변수의 값이나 줄 끝 주석($where)에 $name 이름이 들어 있으면 이름을 빼라는 안내와 함께 멈춘다" \
      "$UNREAD_STOPPED"' && contains "이름을 빼"'
  done
done

# NUL이 든 줄이 앞에 있어도 안내의 줄 번호는 실제 줄 번호다.
setup; prepared; os_ca; printf 'NOTE=a\000b\n' >> "$ENV"; write_unread MONITORING_CA_FILE "$OS/ca-bundle.crt" 1
unread_fix='MONITORING_CA_FILE=경로'; unread_line=3
run start
check 'NUL이 든 줄 뒤에 운영 명령이 읽지 않는 줄이 있으면 그 줄의 실제 번호를 보이고 멈춘다' "$UNREAD_STOPPED"

# 운영 명령이 읽는 형식(export, 줄 앞 공백, = 앞뒤 공백, CRLF 줄 끝, 따옴표 값, 공백 뒤 줄 끝 주석)은 멈추지 않고
# 따옴표와 주석을 뺀 값을 Compose에 넘긴다.
for form in export indent spaces crlf dquote squote comment; do
  for name in MONITORING_SECRETS_DIR MONITORING_CA_FILE; do
    setup; prepared; place_tokens yes yes; os_ca
    case "$name" in
      MONITORING_SECRETS_DIR) value="$T/other" seen="$FAKE/compose_secrets_dir" ;;
      *) value="$OS/ca-bundle.crt" seen="$FAKE/compose_ca_file" ;;
    esac
    case "$form" in
      export) printf 'export %s=%s\n' "$name" "$value" >> "$ENV" ;;
      indent) printf '  \t%s=%s\n' "$name" "$value" >> "$ENV" ;;
      spaces) printf '%s = %s\n' "$name" "$value" >> "$ENV" ;;
      crlf) printf '%s=%s\r\n' "$name" "$value" >> "$ENV" ;;
      dquote) printf '%s="%s"\n' "$name" "$value" >> "$ENV" ;;
      squote) printf "%s='%s'\n" "$name" "$value" >> "$ENV" ;;
      comment) printf '%s=%s # 주석\n' "$name" "$value" >> "$ENV" ;;
    esac
    run start
    check "운영 명령이 읽는 형식($form)으로 $name을 적으면 띄우고 그 값을 Compose에 넘긴다" \
      '[ $RC -eq 0 ] && [ "$(cat "$seen")" = "$value" ]'
  done
done

# 빈 값은 운영 명령이 값이 없다고 보고 빈 값을 Compose에 넘겨, Compose가 compose 파일의 기본값을 쓰게 한다.
for name in MONITORING_SECRETS_DIR MONITORING_CA_FILE; do
  setup; prepared
  case "$name" in MONITORING_SECRETS_DIR) seen="$FAKE/compose_secrets_dir" ;; *) seen="$FAKE/compose_ca_file" ;; esac
  printf '%s=\n' "$name" >> "$ENV"
  run start
  check "$name을 빈 값으로 적으면 멈추지 않고 Compose에 빈 값을 넘긴다" '[ $RC -eq 0 ] && [ "$(cat "$seen")" = "" ]'
done

# 다른 변수의 여러 줄 따옴표 값 안에 든 줄은 운영 명령이 읽고 Compose는 다른 변수의 값으로 읽는다.
# start는 운영 명령이 읽은 값을 넘기므로 Compose도 그 폴더를 쓴다.
setup; prepared; place_tokens no yes; printf 'NOTE="first\nMONITORING_SECRETS_DIR=%s\n"\n' "$T/other" >> "$ENV"
run start
check '다른 변수의 여러 줄 따옴표 값 안에 token 폴더 줄이 있으면 운영 명령이 읽은 폴더를 Compose에 넘긴다' \
  '[ $RC -eq 0 ] && [ "$(cat "$FAKE/compose_secrets_dir")" = "$T/other" ]'

# start는 운영 명령이 검사한 token 폴더 값을 Compose에 넘긴다. Compose가 .env를 다시 읽어 다른 폴더를 쓰지 않게 하기 위해서다.
setup; prepared; place_tokens no yes; with_settings "MONITORING_SECRETS_DIR=$T/other"
run start
check '설정 파일에 token 폴더를 적으면 Compose에 그 폴더를 넘긴다' \
  '[ $RC -eq 0 ] && [ "$(cat "$FAKE/compose_secrets_dir")" = "$T/other" ]'

setup; prepared; place_tokens no yes
run_with_env "MONITORING_SECRETS_DIR=$T/other" start
check '셸 환경 변수로 token 폴더를 주면 Compose에 그 폴더를 넘긴다' \
  '[ $RC -eq 0 ] && [ "$(cat "$FAKE/compose_secrets_dir")" = "$T/other" ]'

setup; prepared
run start
check 'token 폴더를 적지 않으면 Compose에 빈 값을 넘겨 기본 위치를 쓰게 한다' \
  '[ $RC -eq 0 ] && [ "$(cat "$FAKE/compose_secrets_dir")" = "" ]'

# 운영 명령은 글자 그대로 읽고 Compose는 다른 값으로 푸는 표기다. 운영 명령이 읽은 폴더를 Compose에 넘긴다.
setup; prepared; rm "$TOKEN"; LITERAL_DIR="$T/a\${UNSET_FOR_TEST:-b}"
mkdir -p "$LITERAL_DIR"
printf 'literal-token-for-test\n' > "$LITERAL_DIR/alertmanager_webhook_token"; chmod 0644 "$LITERAL_DIR/alertmanager_webhook_token"
with_settings "MONITORING_SECRETS_DIR=$LITERAL_DIR"
run start
check '운영 명령과 Compose가 다르게 읽는 표기의 token 폴더면 운영 명령이 검사한 폴더를 Compose에 넘긴다' \
  '[ $RC -eq 0 ] && [ "$(cat "$FAKE/compose_secrets_dir")" = "$LITERAL_DIR" ]'

setup; prepared; rm "$TOKEN"; LITERAL_DIR="$T/a\${UNSET_FOR_TEST:-b}"
mkdir -p "$T/ab"; printf 'compose-token-for-test\n' > "$T/ab/alertmanager_webhook_token"; chmod 0644 "$T/ab/alertmanager_webhook_token"
with_settings "MONITORING_SECRETS_DIR=$LITERAL_DIR"
for command in start render; do
  rm -f "$FAKE/calls"
  run "$command"
  check "운영 명령과 Compose가 다르게 읽는 표기의 token 폴더에서 Compose가 읽는 위치에만 token이 있으면 $command가 멈춘다" \
    '[ $RC -ne 0 ] && contains "token 파일이 없습니다" && ! called "^compose" && [ ! -e "$RENDERED" ]'
done

setup; prepared; os_ca; with_settings "MONITORING_CA_FILE=$OS/\${UNSET_FOR_TEST:-ca-bundle.crt}"
run start
check '운영 명령과 Compose가 다르게 읽는 표기의 CA 인증서 파일 경로면 형식 검사에서 멈추고 Compose를 부르지 않는다' \
  '[ $RC -ne 0 ] && contains "MONITORING_CA_FILE" && contains "절대 경로" && ! called "^compose" && [ ! -e "$RENDERED" ]'

# --- token 폴더를 바꾼 뒤 띄우기(요구사항 0028의 S10~S12) ---
# Compose는 token 파일의 경로만 바뀌면 Alertmanager를 다시 만들지 않는다. 그래서 운영 명령이 token 파일의 실제 경로로 다시 만들지 정한다.

token_path_hash() { sed -n 's/^MONITORING_TOKEN_PATH_SHA256=//p' "$RENDERED/monitoring.env"; }
# 다른 폴더($T/other)에 시험용 token을 둔다.
other_token() {
  mkdir -p "$T/other"
  printf 'other-token-for-test\n' > "$T/other/alertmanager_webhook_token"
  chmod 0644 "$T/other/alertmanager_webhook_token"
}

setup; prepared; other_token; started
with_settings "MONITORING_SECRETS_DIR=$T/other"
run start
check '떠 있는 채로 설정 파일의 token 폴더를 바꾸고 띄우면 컨테이너를 다시 만든다' \
  '[ $RC -eq 0 ] && called "^compose.* up -d --force-recreate" && [ "$(cat "$FAKE/compose_secrets_dir")" = "$T/other" ]'

setup; prepared; other_token; started
run_with_env "MONITORING_SECRETS_DIR=$T/other" start
check '떠 있는 채로 셸 환경 변수로 token 폴더를 바꾸고 띄우면 컨테이너를 다시 만든다' \
  '[ $RC -eq 0 ] && called "^compose.* up -d --force-recreate"'

setup; prepared; other_token
run render
first=$(config_hash)
with_settings "MONITORING_SECRETS_DIR=$T/other"
run render
check 'token 폴더를 바꿔 다시 펼치면 Compose가 컨테이너를 다시 만들도록 monitoring.env의 설정 값이 바뀐다' \
  '[ $RC -eq 0 ] && [ "$(config_hash)" != "$first" ]'

setup; prepared; started
with_settings "MONITORING_SECRETS_DIR=$INSTALL/secrets"
run start
check 'token 폴더를 같은 폴더의 절대 경로로만 바꾸면 컨테이너를 강제로 다시 만들지 않는다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && ! called "force-recreate"'

setup; prepared; other_token; mkdir -p "$T/links"; ln -s "$INSTALL/secrets" "$T/links/secrets"
with_settings "MONITORING_SECRETS_DIR=$T/links/secrets"; started
rm "$T/links/secrets"; ln -s "$T/other" "$T/links/secrets"
run start
check 'token 폴더가 링크이고 가리키는 폴더만 바뀌면 컨테이너를 다시 만든다' \
  '[ $RC -eq 0 ] && called "^compose.* up -d --force-recreate"'

setup; prepared; other_token; with_settings "MONITORING_SECRETS_DIR=$T/other"
run render
check 'monitoring.env에는 token 파일 경로의 해시만 남기고 token 값과 경로 글자는 남기지 않는다' \
  '[ $RC -eq 0 ] && printf "%s\n" "$(token_path_hash)" | grep -Eqx "[0-9a-f]{64}" && ! grep -qF "$T/other" "$RENDERED/monitoring.env" && ! grep -rq "other-token-for-test" "$RENDERED" && ! contains "other-token-for-test"'

# 이 변경 전 운영 명령이 펼친 monitoring.env에는 token 파일 경로의 해시가 없다. 나머지 펼친 파일은 같다.
setup; prepared; started
sed -i '/^MONITORING_TOKEN_PATH_SHA256=/d' "$RENDERED/monitoring.env"
run start
check '이 변경 전 운영 명령이 펼친 설정이 있으면 첫 띄우기에서 컨테이너를 한 번 다시 만든다' \
  '[ $RC -eq 0 ] && called "^compose.* up -d --force-recreate"'
rm -f "$FAKE/calls"
run start
check '이 변경 전 운영 명령이 펼친 설정을 한 번 다시 펼친 뒤에는 컨테이너를 강제로 다시 만들지 않는다' \
  '[ $RC -eq 0 ] && called "^compose.* up" && ! called "force-recreate"'

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
mkdir -p "$REPO/certs"; printf 'REPO CA\n' > "$REPO/certs/ca-certificates.crt"; chmod 0644 "$REPO/certs/ca-certificates.crt"
printf 'MONITORING_APP_ADDRESS=dev-api:8080\n' > "$REPO/.env"
OUT=$(cd "$REPO" && sh ./monitoring-server.sh render 2>&1 < /dev/null)
RC=$?
check '저장소의 infrastructure/에서 펼치면 저장소의 템플릿, ./secrets의 token, ./certs의 CA 인증서 파일로 rendered/에 쓴다' \
  '[ $RC -eq 0 ] && grep -q "url: http://dev-api:8080/api/prometheus/sd/blackbox" "$REPO/rendered/prometheus.yml" && [ -f "$REPO/rendered/monitoring.env" ]'

# --- 저장소에서 Compose에 넘길 값(요구사항 0028의 S13~S15) ---
# 저장소에서는 render가 끝나면 사라지는 컨테이너 안에서 돌 수 있어(monitoring-dev.bat start), 검사한 값을 compose.env로 남기고
# Compose가 .env 대신 그 파일을 읽게 한다.

# 저장소의 infrastructure/를 본뜬 폴더를 만든다. token은 ./secrets, CA 인증서 파일은 ./certs에 있다.
repo_setup() {
  setup
  REPO="$T/repo"
  mkdir -p "$REPO/prometheus" "$REPO/alertmanager" "$REPO/blackbox" "$REPO/secrets" "$REPO/certs"
  cp "$SCRIPT" "$SETTINGS" "$REPO/"
  cp "$SOURCE_DIR/prometheus/prometheus.yml" "$REPO/prometheus/"
  cp "$SOURCE_DIR/alertmanager/alertmanager.yml" "$REPO/alertmanager/"
  cp "$SOURCE_DIR/blackbox/blackbox.yml" "$REPO/blackbox/"
  printf 'repo-token-for-test\n' > "$REPO/secrets/alertmanager_webhook_token"
  chmod 0644 "$REPO/secrets/alertmanager_webhook_token"
  printf 'REPO CA\n' > "$REPO/certs/ca-certificates.crt"
  chmod 0644 "$REPO/certs/ca-certificates.crt"
  printf 'MONITORING_APP_ADDRESS=dev-api:8080\n' > "$REPO/.env"
}
# 저장소 폴더 안에 시험용 token이 든 폴더(첫 번째 인자, 저장소 기준 경로)를 만든다.
repo_token_dir() {
  mkdir -p "$REPO/$1"
  printf 'repo-other-token-for-test\n' > "$REPO/$1/alertmanager_webhook_token"
  chmod 0644 "$REPO/$1/alertmanager_webhook_token"
}
# 저장소 폴더에서 render를 실행한다. 인자를 주면 그 명령(예: env 이름=값)으로 실행한다.
run_repo() {
  OUT=$(cd "$REPO" && "$@" sh ./monitoring-server.sh render 2>&1 < /dev/null)
  RC=$?
}
# compose.env에 있어야 할 줄(주석 밖). 인자는 token 폴더, CA 인증서 파일 경로, Prometheus, Alertmanager, Blackbox Exporter 포트다.
expect_compose_env() {
  printf "MONITORING_SECRETS_DIR='%s'\nMONITORING_CA_FILE='%s'\nMONITORING_PROMETHEUS_PORT='%s'\nMONITORING_ALERTMANAGER_PORT='%s'\nMONITORING_BLACKBOX_PORT='%s'\n" \
    "$@" > "$T/expected"
}
compose_env_as_expected() { [ "$(grep -v '^#' "$REPO/rendered/compose.env")" = "$(cat "$T/expected")" ]; }
rendered_state() { cksum "$REPO"/rendered/* 2>/dev/null; }

repo_setup
run_repo
expect_compose_env '' '' 9090 9093 9115
check '저장소에서 펼치면 Compose에 넘길 두 경로 값과 기본 호스트 포트를 작은따옴표로 감싸 compose.env에 남긴다' \
  '[ $RC -eq 0 ] && compose_env_as_expected && [ "$(mode "$REPO/rendered/compose.env")" = "-rw-r--r--" ]'
check '저장소에서 펼치면 .env 대신 compose.env를 주는 docker compose 명령을 안내한다' \
  'contains "docker compose --env-file infrastructure/rendered/compose.env -f infrastructure/compose.yml up -d --build"'

# 떠 있는 모니터링 시스템에 새 설정을 쓰는 안내도 .env를 다시 읽지 않는 명령이어야 한다.
repo_setup; touch "$FAKE/running"
run_repo
check '저장소에서 떠 있는 모니터링 시스템이 있으면 새 설정을 쓰는 명령도 compose.env를 주어 안내한다' \
  '[ $RC -eq 0 ] && contains "새 설정을 쓰려면 저장소 폴더에서 docker compose --env-file infrastructure/rendered/compose.env -f infrastructure/compose.yml up -d" && ! contains "docker compose up -d"'

setup; prepared; touch "$FAKE/running"
run render
check '묶음에서 떠 있는 모니터링 시스템이 있으면 compose.yml이 있는 폴더의 docker compose up -d로 새 설정을 쓰라고 안내한다' \
  '[ $RC -eq 0 ] && contains "새 설정을 쓰려면 docker compose up -d"'

repo_setup; printf 'MONITORING_PROMETHEUS_PORT=19090\nMONITORING_ALERTMANAGER_PORT=19093\nMONITORING_BLACKBOX_PORT=19115\n' >> "$REPO/.env"
run_repo
expect_compose_env '' '' 19090 19093 19115
check '저장소에서 호스트 포트를 바꿔 펼치면 compose.env에 검사한 포트를 남긴다' '[ $RC -eq 0 ] && compose_env_as_expected'

# 운영 명령은 글자 그대로 읽고 Compose는 다른 값으로 푸는 표기다.
repo_setup; repo_token_dir 'a${UNSET_FOR_TEST:-b}'; printf '%s\n' 'MONITORING_SECRETS_DIR=./a${UNSET_FOR_TEST:-b}' >> "$REPO/.env"
run_repo
expect_compose_env './a${UNSET_FOR_TEST:-b}' '' 9090 9093 9115
check '운영 명령과 Compose가 다르게 읽는 표기의 token 폴더면 compose.env에 운영 명령이 검사한 폴더를 남긴다' \
  '[ $RC -eq 0 ] && compose_env_as_expected'

# 다른 변수의 여러 줄 따옴표 값 안에 든 줄이다. 운영 명령은 그 줄을 읽고, Compose는 다른 변수의 값으로 읽는다.
repo_setup; repo_token_dir inner; printf 'NOTE="first\nMONITORING_SECRETS_DIR=./inner\n"\n' >> "$REPO/.env"
run_repo
expect_compose_env './inner' '' 9090 9093 9115
check '다른 변수의 여러 줄 값 안에 token 폴더 줄이 있으면 compose.env에 운영 명령이 검사한 폴더를 남긴다' \
  '[ $RC -eq 0 ] && compose_env_as_expected'

repo_setup; repo_token_dir shell
run_repo env MONITORING_SECRETS_DIR=./shell
expect_compose_env './shell' '' 9090 9093 9115
check '저장소에서 셸 환경 변수로 token 폴더를 주면 compose.env에 그 폴더를 남긴다' '[ $RC -eq 0 ] && compose_env_as_expected'

# 줄 끝 주석 앞의 탭은 운영 명령이 주석으로 보지만 Compose는 값으로 읽는다.
repo_setup; printf 'MONITORING_CA_FILE=%s\t# 주석\n' "$REPO/certs/ca-certificates.crt" >> "$REPO/.env"
run_repo
expect_compose_env '' "$REPO/certs/ca-certificates.crt" 9090 9093 9115
check '운영 명령과 Compose가 다르게 읽는 표기의 CA 인증서 파일 경로면 compose.env에 운영 명령이 검사한 경로를 남긴다' \
  '[ $RC -eq 0 ] && compose_env_as_expected'

setup; prepared
run render
check '묶음에서 펼치면 compose.env를 만들지 않는다' '[ $RC -eq 0 ] && [ ! -e "$RENDERED/compose.env" ]'

# compose.env의 작은따옴표 값에서 Compose가 푸는 글자다. 그런 값은 저장소에서만 멈춘다(요구사항 0028의 S14).
for bad in "it's" 'back\slash'; do
  repo_setup; run_repo; before=$(rendered_state)
  repo_token_dir "$bad"; printf 'MONITORING_SECRETS_DIR=./%s\n' "$bad" >> "$REPO/.env"
  run_repo
  check "저장소에서 token 폴더 값에 Compose에 그대로 넘길 수 없는 글자($bad)가 있으면 펼친 설정을 바꾸지 않고 멈춘다" \
    '[ $RC -ne 0 ] && contains "MONITORING_SECRETS_DIR" && contains "상대 경로" && ! contains "$bad" && [ "$(rendered_state)" = "$before" ]'
done

repo_setup; run_repo; before=$(rendered_state); repo_token_dir 'new
line'
run_repo env 'MONITORING_SECRETS_DIR=./new
line'
check '저장소에서 셸 환경 변수의 token 폴더 값에 줄바꿈이 있으면 펼친 설정을 바꾸지 않고 멈춘다' \
  '[ $RC -ne 0 ] && contains "MONITORING_SECRETS_DIR" && contains "셸 환경 변수" && [ "$(rendered_state)" = "$before" ]'

# Compose는 셸 환경 변수의 값 끝 줄바꿈까지 token 폴더로 쓴다. 끝의 줄바꿈만 있어도 넘길 수 없다.
repo_setup; run_repo; before=$(rendered_state); repo_token_dir shell
run_repo env 'MONITORING_SECRETS_DIR=./shell
'
check '저장소에서 셸 환경 변수의 token 폴더 값 끝에 줄바꿈이 있으면 펼친 설정을 바꾸지 않고 멈춘다' \
  '[ $RC -ne 0 ] && contains "MONITORING_SECRETS_DIR" && contains "셸 환경 변수" && [ "$(rendered_state)" = "$before" ]'

# 묶음의 start는 검사한 값을 export해 셸의 값을 덮으므로, 지금처럼 끝 줄바꿈을 뗀 폴더를 검사하고 같은 값을 Compose에 넘긴다.
setup; prepared; mkdir -p "$T/other"; cp "$TOKEN" "$T/other/"
run_with_env "MONITORING_SECRETS_DIR=$T/other
" start
check '묶음에서는 셸 환경 변수의 token 폴더 값 끝에 줄바꿈이 있어도 뗀 값을 검사하고 그 값을 Compose에 넘긴다' \
  '[ $RC -eq 0 ] && [ "$(cat "$FAKE/compose_secrets_dir"; printf x)" = "$T/other
x" ]'

setup; prepared; mkdir -p "$T/it's"; cp "$TOKEN" "$T/it's/"; with_settings "MONITORING_SECRETS_DIR=$T/it's"
run render
check '묶음에서는 token 폴더 값에 작은따옴표가 있어도 멈추지 않는다' '[ $RC -eq 0 ]'

# --- 준비 확인(요구사항 0015의 S7, S15) ---

setup; prepared
run start
check '띄운 뒤 curl로 세 프로그램의 준비 확인 주소를 부른다' \
  '[ $RC -eq 0 ] && ready_called "^curl .*http://127.0.0.1:9090/-/ready" && ready_called "^curl .*http://127.0.0.1:9093/-/ready" && ready_called "^curl .*http://127.0.0.1:9115/-/healthy"'

setup; prepared; with_settings MONITORING_PROMETHEUS_PORT=19090 MONITORING_ALERTMANAGER_PORT=19093 MONITORING_BLACKBOX_PORT=19115
run start
check '호스트 포트를 바꿨으면 바꾼 포트로 준비 확인 주소를 부른다' \
  '[ $RC -eq 0 ] && ready_called "http://127.0.0.1:19090/-/ready" && ready_called "http://127.0.0.1:19093/-/ready" && ready_called "http://127.0.0.1:19115/-/healthy" && ! ready_called ":9090/"'

setup; prepared
OUT=$(cd "$BUNDLE" && MONITORING_READY_TOOLS=wget sh ./monitoring-server.sh start 2>&1 < /dev/null)
RC=$?
check 'curl을 쓸 수 없으면 wget으로 준비 확인 주소를 부른다' \
  '[ $RC -eq 0 ] && ready_called "^wget .*http://127.0.0.1:9090/-/ready" && ! ready_called "^curl"'

setup; prepared; printf '9115\n' > "$FAKE/not_ready_ports"
OUT=$(cd "$BUNDLE" && MONITORING_READY_TOOLS=wget sh ./monitoring-server.sh start 2>&1 < /dev/null)
RC=$?
check 'wget으로 부른 준비 확인 주소가 실패하면 그 컨테이너와 로그 명령을 보인다' \
  '[ $RC -ne 0 ] && contains "docker logs blackbox-exporter" && ! contains "docker logs prometheus"'

setup; prepared
OUT=$(cd "$BUNDLE" && MONITORING_READY_TOOLS="" sh ./monitoring-server.sh start 2>&1 < /dev/null)
RC=$?
check 'curl과 wget이 없으면 curl을 설치하라고 안내하고 이미지를 불러오거나 컨테이너를 바꾸지 않는다' \
  '[ $RC -ne 0 ] && contains "curl" && contains "설치" && ! called load && ! called "^compose" && [ ! -e "$RENDERED" ]'

# --- 서버 사용량 수집 끄기(요구사항 0022) ---

# 펼친 Prometheus 설정에 서버 사용량 수집 부분과 서버 감시 대상 목록 API 주소가 몇 번 나오는지
os_job() { grep -c 'job_name: manager-os' "$RENDERED/prometheus.yml"; }
os_sd() { grep -c '/api/prometheus/sd/os-exporter' "$RENDERED/prometheus.yml"; }

setup; prepared
run start
check '서버 사용량 수집 값이 없으면 수집 부분을 펼치고 꺼짐을 보이지 않는다' \
  '[ $RC -eq 0 ] && [ "$(os_job)" = 1 ] && [ "$(os_sd)" = 1 ] && ! contains "서버 사용량 수집"'

setup; prepared; with_settings MONITORING_OS_EXPORTER=on
run start
check '서버 사용량 수집 값이 on이면 수집 부분을 펼친다' \
  '[ $RC -eq 0 ] && [ "$(os_job)" = 1 ] && [ "$(os_sd)" = 1 ] && ! contains "서버 사용량 수집"'

setup; prepared; with_settings 'MONITORING_OS_EXPORTER= off '
run start
check '서버 사용량 수집 값이 off면 수집 부분을 빼고 펼치고 꺼짐을 보인다' \
  '[ $RC -eq 0 ] && [ "$(os_job)" = 0 ] && [ "$(os_sd)" = 0 ] && grep -q "job_name: manager-blackbox" "$RENDERED/prometheus.yml" && grep -q "^MONITORING_OS_EXPORTER=off$" "$RENDERED/monitoring.env" && contains "서버 사용량 수집 꺼짐"'

setup; prepared; with_settings MONITORING_OS_EXPORTER=off; started
run status
check '상태를 보면 서버 사용량 수집이 꺼졌다고 보인다' \
  '[ $RC -eq 0 ] && contains "서버 사용량 수집 꺼짐"'

for value in yes false 0 ON Off 'o n'; do
  setup; prepared; with_settings "MONITORING_OS_EXPORTER=$value"
  run start
  check "서버 사용량 수집 값이 '$value'이면 허용 값과 고치는 방법을 보이고 펼치거나 띄우지 않는다" \
    '[ $RC -ne 0 ] && contains "MONITORING_OS_EXPORTER" && contains "on, off" && contains ".env의 MONITORING_OS_EXPORTER" && ! called load && ! called "^compose" && [ ! -e "$RENDERED" ]'
done

setup; prepared; with_settings 'MONITORING_OS_EXPORTER=$(touch '"$T"'/injected)'
run start
check '서버 사용량 수집 값에 든 셸 명령은 실행하지 않고 틀린 값으로 거부한다' \
  '[ $RC -ne 0 ] && [ ! -e "$T/injected" ] && contains "MONITORING_OS_EXPORTER" && ! called "^compose"'

setup; prepared
run_with_env "MONITORING_OS_EXPORTER=off
  - job_name: injected" start
check '셸 환경 변수의 서버 사용량 수집 값에 줄바꿈과 YAML을 넣으면 거부하고 unset 방법을 보인다' \
  '[ $RC -ne 0 ] && contains "unset MONITORING_OS_EXPORTER" && ! called "^compose" && [ ! -e "$RENDERED" ]'

setup; prepared; with_settings MONITORING_OS_EXPORTER=yes
run render
check '설정 펼치기도 틀린 서버 사용량 수집 값을 거부하고 펼치지 않는다' \
  '[ $RC -ne 0 ] && contains "MONITORING_OS_EXPORTER" && [ ! -e "$RENDERED" ]'

setup; prepared; with_settings MONITORING_RETENTION_DAYS=abc MONITORING_OS_EXPORTER=yes
run start
check '숫자 값과 서버 사용량 수집 값이 함께 틀리면 모두 한 번에 보인다' \
  '[ $RC -ne 0 ] && contains "MONITORING_RETENTION_DAYS" && contains "MONITORING_OS_EXPORTER" && ! called "^compose"'

setup; prepared; with_settings MONITORING_OS_EXPORTER=on
run_with_env MONITORING_OS_EXPORTER=off start
check '셸 환경 변수의 서버 사용량 수집 값이 설정 파일보다 먼저 쓰인다' \
  '[ $RC -eq 0 ] && [ "$(os_job)" = 0 ] && contains "서버 사용량 수집 꺼짐"'

setup; prepared
run start
rm "$FAKE/calls"; with_settings MONITORING_OS_EXPORTER=off
run start
check '서버 사용량 수집을 끄면 묻지 않고 컨테이너를 다시 만든다' \
  '[ $RC -eq 0 ] && called "^compose.* up -d --force-recreate" && ! contains "계속할까요" && [ "$(os_job)" = 0 ]'

setup; prepared; with_settings MONITORING_OS_EXPORTER=off
sed -i '/# BEGIN manager-os/d' "$BUNDLE/templates/prometheus.yml"
(cd "$BUNDLE" && sha256sum compose.yml .env.example monitoring-system-images.tar monitoring-server.sh monitoring-settings.sh templates/* > SHA256SUMS)
run start
check '서버 사용량 수집을 끄는데 템플릿의 표시 줄이 맞지 않으면 묶음을 다시 옮기라고 안내하고 펼치거나 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "묶음 폴더를 다시 옮겨" && ! called "^compose" && [ ! -e "$RENDERED/prometheus.yml" ]'

setup; prepared; with_settings MONITORING_OS_EXPORTER=off
sed -i 's/# BEGIN manager-os/# SWAP-BEGIN/; s/# END manager-os/# BEGIN manager-os/; s/# SWAP-BEGIN/# END manager-os/' "$BUNDLE/templates/prometheus.yml"
(cd "$BUNDLE" && sha256sum compose.yml .env.example monitoring-system-images.tar monitoring-server.sh monitoring-settings.sh templates/* > SHA256SUMS)
run start
check '서버 사용량 수집을 끄는데 템플릿의 표시 줄 순서가 바뀌었으면 펼치거나 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "묶음 폴더를 다시 옮겨" && ! called "^compose" && [ ! -e "$RENDERED/prometheus.yml" ]'

# --- 오래된 버전 정리와 되돌리기(요구사항 0023) ---
# v1, v2(이 묶음), v3, v4는 이 순서로 배포한 버전이다.
running_is() { [ "$(cat "$FAKE/running_version")" = "$1" ]; }

setup; prepared
run start
check '처음 띄우면 아무것도 지우지 않고 지금 버전을 기록한다(S1)' \
  '[ $RC -eq 0 ] && [ "$(recorded current)" = v2 ] && [ -z "$(recorded previous)" ] && ! called "^image rm" && kept v2 && ! contains "지웠습니다" && ! contains "지우지 않았습니다"'

setup; prepared; add_bundle v1
run_at v1 start; run start
check '두 번째 버전을 띄우면 처음 버전을 직전 버전으로 남긴다(S2)' \
  '[ $RC -eq 0 ] && [ "$(recorded current)" = v2 ] && [ "$(recorded previous)" = v1 ] && kept v1 && kept v2 && ! called "^image rm"'
printf 'other-app:v1\nmonitoring-node-exporter:v1\n' >> "$FAKE/images"
printf 'keep\n' > "$INSTALL/notes.txt"
add_bundle v3; run_at v3 start
check '세 번째 버전을 띄우면 지금과 직전 버전 밖의 묶음 폴더와 이미지를 지운다(S2)' \
  '[ $RC -eq 0 ] && removed v1 && kept v2 && kept v3 && [ "$(recorded current)" = v3 ] && [ "$(recorded previous)" = v2 ]'
check '오래된 버전을 지우면 지운 버전을 보인다(S2)' 'contains "지웠습니다: v1"'
check '정리해도 기록, 설정 파일, token, CA 인증서 파일, 다른 이미지, 묶음이 아닌 파일을 지우지 않는다(S2)' \
  '[ -f "$ENV" ] && [ -f "$TOKEN" ] && [ -f "$CA" ] && has_image other-app:v1 && has_image monitoring-node-exporter:v1 && [ -f "$INSTALL/notes.txt" ] && ! called "volume"'
rm -f "$FAKE/calls"
run_at v3 start
check '같은 버전을 다시 띄우면 직전 버전을 그대로 두고 아무것도 지우지 않는다(S3)' \
  '[ $RC -eq 0 ] && [ "$(recorded current)" = v3 ] && [ "$(recorded previous)" = v2 ] && kept v2 && ! called "^image rm"'

# 새 버전을 띄웠다가 준비 확인에서 실패하면 컨테이너는 새 버전이지만 기록은 그대로다.
setup; prepared; add_bundle v1
run_at v1 start; run start; printf '9090\n' > "$FAKE/not_ready_ports"
add_bundle v3; run_at v3 start
check '띄우기가 실패하면 아무것도 지우지 않고 버전 기록을 바꾸지 않는다(S5)' \
  '[ $RC -ne 0 ] && kept v1 && [ "$(recorded current)" = v2 ] && [ "$(recorded previous)" = v1 ] && ! called "^image rm"'
rm "$FAKE/not_ready_ports"
run_at v3 start
check '실패한 버전을 다시 띄워 성공하면 마지막으로 성공한 버전을 직전 버전으로 기록한다(S5)' \
  '[ $RC -eq 0 ] && [ "$(recorded current)" = v3 ] && [ "$(recorded previous)" = v2 ] && kept v2 && removed v1'

setup; prepared; add_bundle v1
run_at v1 start; run start; printf '9090\n' > "$FAKE/not_ready_ports"
add_bundle v3; run_at v3 start; rm "$FAKE/not_ready_ports"
add_bundle v4; run_at v4 start
check '띄우기에 실패한 버전 뒤에 새 버전을 띄우면 마지막으로 성공한 버전을 직전 버전으로 기록한다(S10)' \
  '[ $RC -eq 0 ] && [ "$(recorded current)" = v4 ] && [ "$(recorded previous)" = v2 ]'
check '띄우기에 실패한 버전 뒤에 새 버전을 띄우면 마지막으로 성공한 버전을 남긴다(S10)' 'kept v2 && kept v4'
check '띄우기에 실패한 버전 뒤에 새 버전을 띄우면 실패한 버전을 지운다(S10)' 'removed v3 && removed v1'

setup; prepared; add_bundle v1
run_at v1 start; run start; printf '9090\n' > "$FAKE/not_ready_ports"
add_bundle v3; run_at v3 start; rm "$FAKE/not_ready_ports"
run start
check '성공했던 버전을 실패한 버전 뒤에 다시 띄우면 직전 버전을 그대로 둔다(S3)' \
  '[ $RC -eq 0 ] && [ "$(recorded current)" = v2 ] && [ "$(recorded previous)" = v1 ] && kept v1'

setup; prepared; add_bundle v1
run_at v1 start; run start; add_bundle v3; run_at v3 start; rm -f "$FAKE/calls"
run_at v3 rollback
check '되돌리면 직전 버전의 묶음 폴더에서 띄운다(S4)' \
  '[ $RC -eq 0 ] && running_is v2 && [ "$(cat "$FAKE/running_workdir")" = "$BUNDLE" ]'
check '되돌리면 되돌린 버전을 보인다(S4)' 'contains "되돌렸습니다: v3 → v2"'
check '되돌린 뒤에는 되돌리기 전의 버전을 직전 버전으로 기록한다(S4)' \
  '[ "$(recorded current)" = v2 ] && [ "$(recorded previous)" = v3 ]'
check '되돌린 뒤 정리할 때 되돌리기 전의 버전을 지우지 않는다(S4)' 'kept v2 && kept v3 && ! called "^image rm"'
run rollback
check '되돌린 뒤 다시 되돌리면 되돌리기 전의 버전으로 돌아간다(S4)' \
  '[ $RC -eq 0 ] && running_is v3 && contains "되돌렸습니다: v2 → v3" && [ "$(recorded current)" = v3 ] && [ "$(recorded previous)" = v2 ]'

setup; prepared; add_bundle v1
run_at v1 start; run start
run_at v1 rollback
check '되돌릴 버전의 묶음 폴더에서 실행해도 그 버전으로 되돌린다(S4)' \
  '[ $RC -eq 0 ] && running_is v1 && contains "되돌렸습니다: v2 → v1"'

# docker compose down으로 컨테이너를 지운 상태: 기록의 지금 버전을 기준으로 되돌린다.
setup; prepared; add_bundle v1
run_at v1 start; run start; rm "$FAKE/running"
run rollback
check '모니터링 시스템 컨테이너가 없으면 기록의 지금 버전의 직전 버전으로 되돌린다(S4)' \
  '[ $RC -eq 0 ] && running_is v1 && contains "되돌렸습니다: v2 → v1"'

setup; prepared; add_bundle v1
run_at v1 start; run start
run_in_terminal '' rollback
check '터미널에서 되돌려도 되돌릴지 묻지 않는다(S4)' \
  '[ $RC -eq 0 ] && ! contains "[y/N]" && running_is v1'

setup; prepared; add_bundle v1
run_at v1 start; run start; add_bundle v3; run_at v3 start; rm -f "$FAKE/calls"; printf '9090\n' > "$FAKE/not_ready_ports"
run_at v3 rollback
check '되돌리다 준비 확인에서 실패하면 되돌렸다고 보이지 않는다(S4)' \
  '[ $RC -ne 0 ] && ! contains "되돌렸습니다" && [ "$(recorded current)" = v3 ]'

setup; prepared; add_bundle v1
run_at v1 start; run start; with_settings MONITORING_RETENTION_DAYS=10
# BusyBox script는 안쪽 명령의 종료 코드를 넘기지 않으므로 터미널 안에서 종료 코드를 출력한다.
run_in_terminal n 'rollback; echo "rollback-exit=$?"'
check '되돌리다 기록 보관 값 줄임 확인을 거절하면 되돌리지 않았다고 알린다(S11)' \
  'contains "되돌리지 않았습니다" && ! contains "되돌렸습니다" && running_is v2'
check '되돌리다 기록 보관 값 줄임 확인을 거절하면 실패 종료 코드로 끝난다(S11)' 'contains "rollback-exit=1"'

# 이 기능 전 묶음으로 install만 해 둔 서버: 기록도 떠 있는 버전도 없다.
setup; prepared; add_bundle v1
run start
check '직전 버전을 알 수 없으면 아무것도 지우지 않는다(S6)' \
  '[ $RC -eq 0 ] && kept v2 && [ -d "$INSTALL/monitoring-system-v1" ] && ! called "^image rm" && [ "$(recorded current)" = v2 ]'
check '직전 버전을 알 수 없으면 손으로 지우는 방법을 안내한다(S6)' 'contains "지우지 않았습니다" && contains "OPERATIONS.md"'
add_bundle v3
run_at v3 start
check '직전 버전을 알게 된 뒤 새 버전을 띄우면 띄운 적 없는 묶음 폴더도 지운다(S6)' \
  '[ $RC -eq 0 ] && [ ! -e "$INSTALL/monitoring-system-v1" ] && kept v2 && kept v3'

setup; prepared; run start; rm -f "$FAKE/calls"
run rollback
check '띄운 버전이 하나뿐이면 되돌릴 버전이 없다고 알리고 아무것도 바꾸지 않는다(S7)' \
  '[ $RC -ne 0 ] && contains "되돌릴 버전이 없습니다" && ! called "^compose" && [ "$(recorded current)" = v2 ]'

setup; prepared
run rollback
check '띄운 적이 없으면 되돌릴 버전이 없다고 알린다(S7)' \
  '[ $RC -ne 0 ] && contains "되돌릴 버전이 없습니다" && ! called "^compose"'

setup; prepared; add_bundle v1
run_at v1 start; run start; rm -rf "$INSTALL/monitoring-system-v1"; rm -f "$FAKE/calls"
run rollback
check '직전 버전의 묶음 폴더가 없으면 그 폴더를 알리고 떠 있는 모니터링 시스템을 건드리지 않는다(S7)' \
  '[ $RC -ne 0 ] && contains "monitoring-system-v1" && ! called "^compose" && running_is v2'

setup; prepared; add_bundle v1
run_at v1 start; run start; printf 'monitoring-system-alertmanager:v1\n' > "$FAKE/images_in_use"
add_bundle v3; run_at v3 start
check '이미지를 지우지 못하면 그 이미지와 이유를 경고하고 성공으로 끝난다(S8)' \
  '[ $RC -eq 0 ] && contains "지우지 못했습니다" && contains "monitoring-system-alertmanager:v1" && contains "is using"'
check '이미지 하나를 지우지 못해도 나머지 이미지와 묶음 폴더는 지운다(S8)' \
  'has_image monitoring-system-alertmanager:v1 && ! has_image monitoring-system-prometheus:v1 && [ ! -e "$INSTALL/monitoring-system-v1" ]'
rm "$FAKE/images_in_use"
run_at v3 start
check '다음에 띄울 때 지우지 못했던 이미지를 다시 지운다(S8)' \
  '[ $RC -eq 0 ] && ! has_image monitoring-system-alertmanager:v1'

# 이름이 줄바꿈으로 끝나는 폴더는 명령 치환이 끝의 줄바꿈을 지워 실제 위치가 늘 다르게 나온다. 이것으로 묶음 폴더를 지우지 못하는 경우를 만든다.
setup; prepared; add_bundle v1
run_at v1 start; run start
odd="$INSTALL/monitoring-system-v1/odd
"
mkdir "$odd"
add_bundle v3; run_at v3 start
check '묶음 폴더를 지우지 못하면 그 폴더와 이유를 경고한다(S8)' \
  '[ $RC -eq 0 ] && contains "지우지 못했습니다" && contains "monitoring-system-v1" && contains "실제 위치가 달라"'
check '묶음 폴더를 지우지 못한 버전은 지웠다고 보이지 않는다(S8)' '! contains "지웠습니다: v1"'
check '묶음 폴더를 지우다 실패해도 그 폴더의 compose.yml은 남긴다(S8)' '[ -f "$INSTALL/monitoring-system-v1/compose.yml" ]'
run_at v3 start
check '지우지 못한 묶음 폴더는 다음에 띄울 때 다시 지우려 한다(S8)' \
  '[ $RC -eq 0 ] && contains "지우지 못했습니다" && contains "monitoring-system-v1"'
rmdir "$odd"
run_at v3 start
check '지우지 못한 까닭이 없어지면 다음에 띄울 때 그 묶음 폴더를 지운다(S8)' \
  '[ $RC -eq 0 ] && [ ! -e "$INSTALL/monitoring-system-v1" ]'

# 지우면 안 되는 대상(S9). outside는 설치 폴더 밖이다.
setup; prepared; add_bundle v1
mkdir -p "$T/outside/link-target" "$T/outside/templates"
printf 'services:\n  prometheus:\n    image: monitoring-system-prometheus:v0\n' > "$T/outside/link-target/compose.yml"
printf 'keep\n' > "$T/outside/templates/keep"
ln -s "$T/outside/link-target" "$INSTALL/monitoring-system-v0"
mkdir "$INSTALL/monitoring-system-x y"; cp "$INSTALL/monitoring-system-v1/compose.yml" "$INSTALL/monitoring-system-x y/"
mkdir "$INSTALL/monitoring-system-v8"; printf 'keep\n' > "$INSTALL/monitoring-system-v8/keep"
mkdir "$INSTALL/monitoring-system-v9"; cp "$INSTALL/monitoring-system-v1/compose.yml" "$INSTALL/monitoring-system-v9/"
run_at v1 start; run start
# 띄울 때는 체크섬 확인을 통과해야 하므로, v1을 띄운 뒤에 그 안의 폴더를 링크로 바꾼다.
rm -r "$INSTALL/monitoring-system-v1/templates"; ln -s "$T/outside/templates" "$INSTALL/monitoring-system-v1/templates"
add_bundle v3; run_at v3 start
check '정리할 때 링크인 묶음 폴더는 지우려 하지 않는다(S9)' \
  '[ $RC -eq 0 ] && [ -L "$INSTALL/monitoring-system-v0" ] && [ -f "$T/outside/link-target/compose.yml" ] && ! contains "지우지 못했습니다"'
check '정리할 때 버전 형식이 아닌 이름, compose.yml이 없는 폴더, 이름과 compose.yml의 버전이 다른 폴더를 지우지 않는다(S9)' \
  '[ -f "$INSTALL/monitoring-system-x y/compose.yml" ] && [ -f "$INSTALL/monitoring-system-v8/keep" ] && [ -f "$INSTALL/monitoring-system-v9/compose.yml" ]'
check '지우는 묶음 폴더 안의 링크는 링크만 지운다(S9)' \
  '[ ! -e "$INSTALL/monitoring-system-v1" ] && [ -f "$T/outside/templates/keep" ]'

setup; prepared; printf 'keep\n' > "$T/elsewhere"; ln -s "$T/elsewhere" "$INSTALL/monitoring-versions"
run start
check '버전 기록 파일이 링크면 링크가 가리키는 파일에 쓰지 않는다(보안)' \
  '[ $RC -eq 0 ] && contains "monitoring-versions" && [ -L "$INSTALL/monitoring-versions" ] && [ "$(cat "$T/elsewhere")" = keep ]'

setup; prepared; add_bundle v1
run_at v1 start; run start
cp "$INSTALL/monitoring-versions" "$T/versions-copy"; rm "$INSTALL/monitoring-versions"; ln -s "$T/versions-copy" "$INSTALL/monitoring-versions"
rm -f "$FAKE/calls"
run rollback
check '버전 기록 파일이 링크면 읽지 않는다(보안)' \
  '[ $RC -ne 0 ] && contains "되돌릴 버전이 없습니다" && ! called "^compose"'

setup; prepared; add_bundle v1
run_at v1 start; run start; printf 'current=v2\nprevious=../../outside\n' > "$INSTALL/monitoring-versions"; rm -f "$FAKE/calls"
run rollback
check '버전 기록의 값이 버전 형식이 아니면 그 값으로 경로를 만들지 않는다(보안)' \
  '[ $RC -ne 0 ] && contains "되돌릴 버전이 없습니다" && ! called "^compose"'

printf '\n통과 %s, 실패 %s\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
