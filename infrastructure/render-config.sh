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

address=${MONITORING_APP_ADDRESS:-}

# 값이 설정 파일에 그대로 들어가므로 host:port 모양만 받습니다. 줄바꿈과 YAML 문법이 끼어들지 못합니다.
case "$address" in
	'' | *[!A-Za-z0-9.:-]*)
		echo "MONITORING_APP_ADDRESS는 host:port 형식이어야 합니다. 예: manager-api:8080" >&2
		exit 1
		;;
esac
if ! printf '%s' "$address" | grep -Eq '^[A-Za-z0-9.-]+:[0-9]{1,5}$'; then
	echo "MONITORING_APP_ADDRESS는 host:port 형식이어야 합니다. 예: manager-api:8080" >&2
	exit 1
fi

sed "s|@MONITORING_APP_ADDRESS@|$address|g" "$template" > "$rendered"
echo "앱 주소 $address 로 $rendered 를 만들었습니다."

exec "$@"
