# 코어의 Blackbox Exporter 이미지입니다. 공개 이미지에 module 정의를 넣습니다. (docs/decisions/0009)
#
# 빌드 context는 infrastructure/입니다. 공개 이미지가 읽는 기본 경로에 넣으므로 실행 인자는 바꾸지 않습니다.
FROM quay.io/prometheus/blackbox-exporter:v0.28.0@sha256:e753ff9f3fc458d02cca5eddab5a77e1c175eee484a8925ac7d524f04366c2fc

ARG CORE_VERSION=dev
ARG CORE_REVISION=unknown
# 코어 서버의 core-server.sh가 이 값(커밋 시각, epoch 초)으로 버전의 앞뒤를 판단합니다. 빌드 캐시 때문에 이미지의 Created는 버전마다 같을 수 있습니다.
ARG CORE_COMMIT_TIME=0
LABEL org.opencontainers.image.title="monitoring-core-blackbox-exporter" \
      org.opencontainers.image.version="${CORE_VERSION}" \
      org.opencontainers.image.revision="${CORE_REVISION}" \
      monitoring-core.commit-time="${CORE_COMMIT_TIME}" \
      org.opencontainers.image.source="https://github.com/subinggrae/server-monitor"

COPY blackbox/blackbox.yml /etc/blackbox_exporter/config.yml
