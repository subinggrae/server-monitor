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

sed "s|@MONITORING_APP_ADDRESS@|$MONITORING_APP_ADDRESS|g" "$template" > "$rendered"
echo "앱 주소 $MONITORING_APP_ADDRESS 로 $rendered 를 만들었습니다."

exec "$@"
