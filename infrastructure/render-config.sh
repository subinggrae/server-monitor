#!/bin/sh
# 코어 설정 템플릿의 @MONITORING_APP_ADDRESS@를 앱 주소로 바꿔 쓰고, 이어서 받은 명령을 실행합니다.
# Prometheus와 Alertmanager는 설정 파일 안에서 환경 변수를 펼치지 못하기 때문입니다.
#
#   render-config.sh <템플릿> <펼친 설정을 쓸 경로> <명령> [인자...]
#
# 비밀값은 펼치지 않습니다. webhook token은 설정의 credentials_file로 따로 읽습니다.
set -eu

template=$1
rendered=$2
shift 2

# 비밀값 파일이 필요한 구성 요소는 이미지의 CORE_REQUIRED_FILES에 경로를 둡니다.
# Compose는 secret 파일이 없어도 컨테이너를 띄우고, Docker는 그 자리에 빈 디렉터리를 만듭니다.
# 그래서 일반 파일이 아니거나 비어 있으면 시작하지 않습니다.
for file in ${CORE_REQUIRED_FILES:-}; do
  if [ ! -f "$file" ] || [ ! -s "$file" ]; then
    echo "필요한 파일 $file 이(가) 없거나 비어 있습니다. 코어 폴더의 secrets/에 파일을 만든 뒤 다시 띄워 주세요. 그 자리에 같은 이름의 폴더가 있으면 지우고 만듭니다. (docs/OPERATIONS.md)" >&2
    exit 1
  fi
done

sed "s|@MONITORING_APP_ADDRESS@|$MONITORING_APP_ADDRESS|g" "$template" > "$rendered"
echo "앱 주소 $MONITORING_APP_ADDRESS 로 $rendered 를 만들었습니다."

exec "$@"
