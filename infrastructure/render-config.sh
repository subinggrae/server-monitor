#!/bin/sh
# 코어 설정 템플릿의 자리표시를 값으로 바꿔 쓰고, 이어서 받은 명령을 실행합니다.
# Prometheus, Alertmanager, Blackbox Exporter는 설정 파일 안에서 환경 변수를 펼치지 못하기 때문입니다.
#
#   render-config.sh <템플릿> <펼친 설정을 쓸 경로> <명령> [인자...]
#
# 펼치는 값은 두 가지입니다.
# - @MONITORING_APP_ADDRESS@: 앱 주소입니다. 템플릿에 이 자리표시가 있을 때만 값을 요구하고,
#   운영 명령과 같은 규칙(core-settings.sh의 core_valid_address)으로 형식을 검사한 뒤 펼칩니다.
# - 이미지의 CORE_SETTINGS에 적은 코어 설정 값(같은 폴더의 core-settings.sh, 요구사항 0013)입니다.
#   값마다 형식과 허용 범위를 검사하고, 검사를 통과한 숫자만 템플릿의 @이름@ 자리에 펼칩니다.
#   기록 보관 값은 템플릿이 아니라 Prometheus 실행 인자로 명령 끝에 덧붙입니다.
#   값이 틀리면 원래 프로세스를 실행하지 않고 로그에 안내를 남긴 채 실패합니다.
#
# 비밀값은 펼치지 않습니다. webhook token은 설정의 credentials_file로 따로 읽습니다.
set -eu

template=$1
rendered=$2
shift 2

. "$(dirname "$0")/core-settings.sh"

# 비밀값 파일이 필요한 구성 요소는 이미지의 CORE_REQUIRED_FILES에 경로를 둡니다.
# Compose는 secret 파일이 없어도 컨테이너를 띄우고, Docker는 그 자리에 빈 디렉터리를 만듭니다.
# 그래서 일반 파일이 아니거나 비어 있으면 시작하지 않습니다.
# 컨테이너 사용자(nobody)가 읽지 못하는 권한이어도 경보가 조용히 끊기므로 함께 확인합니다.
for file in ${CORE_REQUIRED_FILES:-}; do
  if [ ! -f "$file" ] || [ ! -s "$file" ]; then
    echo "필요한 파일 없음: $file. 코어의 secrets/에 파일을 만든 뒤 다시 띄워 주세요. 그 자리에 같은 이름의 폴더가 있으면 지우고 만듭니다. (docs/OPERATIONS.md)" >&2
    exit 1
  fi
  if [ ! -r "$file" ]; then
    echo "필요한 파일 읽기 불가: $file. 컨테이너 사용자가 읽을 수 있게 권한을 0644로 바꾼 뒤 다시 띄워 주세요. (docs/OPERATIONS.md)" >&2
    exit 1
  fi
done

# 틀린 값을 모두 로그에 남긴 뒤 멈춥니다. 검사를 통과한 앱 주소에는 sed와 YAML이 특별하게 읽는 글자가 없고,
# 코어 설정 값은 숫자이거나 빈 값(보관 용량 제한 없음)뿐입니다.
settings_ok=yes
script=""
uses_address=no
if grep -q '@MONITORING_APP_ADDRESS@' "$template"; then
  uses_address=yes
  if core_valid_address "${MONITORING_APP_ADDRESS:-}"; then
    script="s|@MONITORING_APP_ADDRESS@|$MONITORING_APP_ADDRESS|g"
  else
    printf "관리자 서버 주소가 올바르지 않습니다: MONITORING_APP_ADDRESS='%s'\n" "${MONITORING_APP_ADDRESS:-}" >&2
    echo "  맞는 형식: 컨테이너 이름:포트(예: manager-api:8080). http://를 붙이지 않고, 이름은 영문자나 숫자로 시작해 영문자, 숫자, _, ., -만 쓰며, 포트는 1~65535입니다." >&2
    echo "  고치는 방법: .env의 MONITORING_APP_ADDRESS 값을 관리자 서버의 컨테이너 이름:포트로 고친 뒤 코어를 다시 띄워 주세요." >&2
    settings_ok=no
  fi
fi
used=""
for name in ${CORE_SETTINGS:-}; do
  if ! core_setting_check "$name" "$(printenv "$name" || true)"; then
    settings_ok=no
    continue
  fi
  script="$script
s|@$name@|$core_value|g"
  arg=$(core_setting_arg "$name" "$core_value")
  if [ -n "$arg" ]; then
    set -- "$@" "$arg"
  fi
  used="$used $name=$core_value"
done
if [ "$settings_ok" = no ]; then
  echo "코어 설정 값이 올바르지 않아 시작하지 않습니다. 위 안내대로 고친 뒤 다시 띄워 주세요. (docs/OPERATIONS.md)" >&2
  exit 1
fi

sed "$script" "$template" > "$rendered"
if [ "$uses_address" = yes ]; then
  echo "앱 주소 $MONITORING_APP_ADDRESS 로 $rendered 를 만들었습니다."
else
  echo "$rendered 를 만들었습니다."
fi
if [ -n "$used" ]; then
  echo "코어 설정 값:$used"
fi

exec "$@"
