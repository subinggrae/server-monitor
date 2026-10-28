#!/bin/sh
# core-server.sh의 사전 검사와 설치 준비를 가짜 docker, systemctl로 상황을 만들어 확인한다.
# 실제 Docker는 쓰지 않는다. 컨테이너를 띄우는 흐름은 요구사항 0011의 수동 확인이 맡는다.
#
#   sh core-server.test.sh <core-server.sh 경로>
#
# 종료 코드: 실패한 시험이 하나라도 있으면 1.
set -u

SCRIPT=$1
FAILED=0
PASSED=0

# 시험 하나마다 빈 설치 폴더, 묶음 폴더, 가짜 명령을 새로 만든다.
setup() {
  T=$(mktemp -d)
  INSTALL="$T/install"
  BUNDLE="$INSTALL/monitoring-core-v2"
  FAKE="$T/fake"
  mkdir -p "$BUNDLE" "$FAKE" "$T/bin"
  cp "$SCRIPT" "$BUNDLE/core-server.sh"
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
  (cd "$BUNDLE" && sha256sum compose.yml .env.example core-images.tar core-server.sh > SHA256SUMS)
  printf 'enabled\n' > "$FAKE/systemctl"
  printf 'healthy\n' > "$FAKE/health"
  printf '1761600000\n' > "$FAKE/bundle_time"
  write_fake_docker
  write_fake_systemctl
  PATH="$T/bin:$ORIGINAL_PATH"
  export PATH FAKE CORE_WAIT_SECONDS=2 CORE_WAIT_INTERVAL=1
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
      *" up "*) touch "$FAKE/running"; printf 'v2\n' > "$FAKE/running_version"; printf '%s\n' "$PWD" > "$FAKE/running_workdir" ;;
    esac ;;
  image)
    [ -f "$FAKE/images_loaded" ] || exit 1
    case "$*" in
      *commit-time*running-image*) cat "$FAKE/running_time" ;;
      *commit-time*) cat "$FAKE/bundle_time" ;;
    esac ;;
  inspect)
    [ -f "$FAKE/running" ] || { echo "Error: No such object" >&2; exit 1; }
    case "$*" in
      *working_dir*) cat "$FAKE/running_workdir" ;;
      *image.version*) cat "$FAKE/running_version" ;;
      *image.revision*) echo "abc1234" ;;
      *Health*) cat "$FAKE/health" ;;
      *.Image*) echo "running-image" ;;
      *) echo "running" ;;
    esac ;;
esac
EOF
  chmod +x "$T/bin/docker"
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

ORIGINAL_PATH=$PATH

# --- 명령과 Docker ---

setup
run deploy
check '알 수 없는 작업이면 쓸 수 있는 작업을 보이고 실패한다' \
  '[ $RC -ne 0 ] && contains "install" && contains "rotate-token" && ! called compose'

setup; prepared; touch "$FAKE/docker_down"
run start
check 'Docker가 꺼져 있으면 알리고 아무것도 하지 않는다' \
  '[ $RC -ne 0 ] && contains "Docker에 연결하지 못했습니다" && ! called load && ! called compose'

# --- 체크섬 ---

setup; prepared; printf 'broken' > "$BUNDLE/core-images.tar"
run start
check '묶음 파일이 바뀌었으면 그 파일을 알리고 불러오거나 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "core-images.tar" && contains "체크섬" && ! called load && ! called compose'

# --- 설정 파일 ---

setup; prepared; rm "$BUNDLE/.env"
run start
check '설정 파일이 없으면 설치 준비를 안내하고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "sh core-server.sh install" && ! called compose'

for address in '' 'http://manager-api:8080' 'manager-api' 'a;touch${IFS}x:80' 'manager-api:70000'; do
  setup; prepared; printf 'MONITORING_APP_ADDRESS=%s\n' "$address" > "$BUNDLE/.env"
  run start
  check "관리자 서버 주소가 '$address'이면 거부한다" \
    '[ $RC -ne 0 ] && contains "관리자 서버 주소" && ! called compose && [ ! -e "$BUNDLE/x" ]'
done

setup; prepared; printf 'MONITORING_APP_ADDRESS=manager-api:8080\n$(touch %s/injected)\n' "$T" > "$BUNDLE/.env"
run start
check '설정 파일의 셸 명령은 실행하지 않는다' '[ ! -e "$T/injected" ]'

# --- token ---

setup; prepared; rm "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'token 파일이 없으면 알리고 그 자리에 폴더를 만들지 않는다' \
  '[ $RC -ne 0 ] && contains "token 파일이 없습니다" && [ ! -e "$INSTALL/secrets/alertmanager_webhook_token" ] && ! called compose'

setup; prepared; : > "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'token 파일이 비어 있으면 알리고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "비어 있습니다" && ! called compose'

setup; prepared; rm "$INSTALL/secrets/alertmanager_webhook_token"; mkdir "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'token 자리에 폴더가 있으면 알리고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "폴더가 있습니다" && ! called compose'

setup; prepared; chmod 0600 "$INSTALL/secrets/alertmanager_webhook_token"
run start
check 'token 파일을 다른 사용자가 읽을 수 없으면 알리고 띄우지 않는다' \
  '[ $RC -ne 0 ] && contains "읽을 수 없습니다" && contains "0644" && ! called compose'

setup; prepared; rm -r "$INSTALL/secrets"; mkdir "$BUNDLE/secrets"; printf 'old\n' > "$BUNDLE/secrets/alertmanager_webhook_token"
run start
check '묶음 폴더 안에 token이 있으면 설치 폴더로 옮기라고 안내한다' \
  '[ $RC -ne 0 ] && contains "묶음 폴더 안에 token이 있습니다" && ! called compose'

setup; prepared; mkdir "$T/custom"; mv "$INSTALL/secrets/alertmanager_webhook_token" "$T/custom/"
printf 'MONITORING_APP_ADDRESS=manager-api:8080\nCORE_SECRETS_DIR=%s\n' "$T/custom" > "$BUNDLE/.env"
run start
check '설정 파일에 token 폴더를 적었으면 그 폴더를 쓴다' '[ $RC -eq 0 ] && called compose'

# --- 띄우기 ---

setup; prepared
run start
check '준비를 마쳤으면 불러오고 띄운 뒤 버전을 보인다' \
  '[ $RC -eq 0 ] && called load && called "compose.* up" && contains "v2" && ! contains "secret-value-for-test"'

setup; prepared; touch "$FAKE/images_loaded"
run start
check '이미지가 이미 있으면 다시 불러오지 않는다' '[ $RC -eq 0 ] && ! called load'

setup; prepared; printf 'starting\n' > "$FAKE/health"
run start
check '정해진 시간 안에 정상이 되지 않으면 컨테이너와 로그 명령을 보인다' \
  '[ $RC -ne 0 ] && contains "정상 상태가 되지 않았습니다" && contains "docker logs prometheus"'

setup; prepared; running_other v3 1761700000 "$INSTALL/monitoring-core-v3"
run start
check '이전 버전으로 바꾸는데 터미널이 아니면 바꾸지 않는다' \
  '[ $RC -ne 0 ] && contains "v3" && ! called "compose.* up"'

setup; prepared; running_other v3 1761700000 "$INSTALL/monitoring-core-v3"
run_in_terminal n start
check '이전 버전으로 바꿀지 물었을 때 n이면 바꾸지 않는다' \
  'contains "계속할까요" && contains "아무것도 바꾸지 않았습니다" && ! called "compose.* up"'

setup; prepared; running_other v3 1761700000 "$INSTALL/monitoring-core-v3"
run_in_terminal y start
check '이전 버전으로 바꿀지 물었을 때 y이면 바꾼다' \
  '[ $RC -eq 0 ] && contains "계속할까요" && called "compose.* up"'

setup; prepared; running_other v1 1761500000 "$INSTALL/monitoring-core-v1"
run start
check '새 버전으로 바꿀 때는 묻지 않고 떠 있는 버전과 새 버전을 보인다' \
  '[ $RC -eq 0 ] && contains "v1" && ! contains "계속할까요" && called "compose.* up"'

setup; prepared; running_other v0 '<no value>' "$INSTALL/monitoring-core-v0"
run start
check '떠 있는 이미지에 커밋 시각이 없으면 예전 버전으로 보고 묻지 않는다' \
  '[ $RC -eq 0 ] && called "compose.* up"'

setup; prepared; running_other v3 1761700000 "$INSTALL/monitoring-core-v3"; printf '<no value>\n' > "$FAKE/bundle_time"
run start
check '띄울 이미지에 커밋 시각이 없으면 이전 버전으로 보고 터미널이 아니면 바꾸지 않는다' \
  '[ $RC -ne 0 ] && ! called "compose.* up"'

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

printf '\n통과 %s, 실패 %s\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
