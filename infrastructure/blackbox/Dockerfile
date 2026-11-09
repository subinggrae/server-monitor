# 코어의 Blackbox Exporter 이미지입니다. 공개 이미지에 module 정의 템플릿을 넣습니다. (docs/decisions/0009)
#
# 빌드 context는 infrastructure/입니다.
# 확인 제한 시간(CORE_PROBE_TIMEOUT_SECONDS)은 코어 서버의 .env에서 받아, render-config.sh가 검사한 뒤 템플릿에 펼칩니다. (요구사항 0013)
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

# render-config.sh가 검사해 펼칠 코어 설정 값의 이름입니다. 값은 이미지에 넣지 않고 컨테이너 환경 변수로 받습니다.
ENV CORE_SETTINGS="CORE_PROBE_TIMEOUT_SECONDS"

COPY render-config.sh core-settings.sh /etc/core/
COPY blackbox/blackbox.yml /etc/blackbox_exporter/config.yml.template

ENTRYPOINT ["/bin/sh", "/etc/core/render-config.sh", "/etc/blackbox_exporter/config.yml.template", "/tmp/blackbox.yml", "/bin/blackbox_exporter"]
CMD ["--config.file=/tmp/blackbox.yml"]
