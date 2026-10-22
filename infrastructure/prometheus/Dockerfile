# 코어의 Prometheus 이미지입니다. 공개 이미지에 설정 템플릿과 규칙을 넣습니다. (docs/decisions/0009)
#
# 빌드 context는 infrastructure/입니다. 비밀값은 .dockerignore로 빠집니다.
# 앱 주소(MONITORING_APP_ADDRESS)는 컨테이너가 시작할 때 받아 render-config.sh가 설정에 펼칩니다.
# 바탕 이미지는 태그와 digest로 고정합니다. 버전을 올릴 때 둘을 함께 바꿉니다.
FROM quay.io/prometheus/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0

ARG CORE_VERSION=dev
ARG CORE_REVISION=unknown
LABEL org.opencontainers.image.title="monitoring-core-prometheus" \
      org.opencontainers.image.version="${CORE_VERSION}" \
      org.opencontainers.image.revision="${CORE_REVISION}" \
      org.opencontainers.image.source="https://github.com/subinggrae/server-monitor"

COPY render-config.sh /etc/core/render-config.sh
COPY prometheus/prometheus.yml /etc/prometheus/prometheus.yml.template
COPY prometheus/rules/ /etc/prometheus/rules/

ENTRYPOINT ["/bin/sh", "/etc/core/render-config.sh", "/etc/prometheus/prometheus.yml.template", "/tmp/prometheus.yml", "/bin/prometheus"]
CMD ["--config.file=/tmp/prometheus.yml", "--storage.tsdb.path=/prometheus", "--storage.tsdb.retention.time=15d"]
