# 코어의 Prometheus 이미지입니다. 공개 이미지에 설정 템플릿과 규칙을 넣습니다. (docs/decisions/0009)
#
# 빌드 context는 infrastructure/입니다. 비밀값은 .dockerignore로 빠집니다.
# 앱 주소(MONITORING_APP_ADDRESS)는 컨테이너가 시작할 때 받아 render-config.sh가 설정에 펼칩니다.
# 기록 보관 기간과 용량도 코어 서버의 .env에서 받아, render-config.sh가 검사한 뒤 설정의 보관 항목에 펼칩니다. (요구사항 0013)
# 바탕 이미지는 태그와 digest로 고정합니다. 버전을 올릴 때 둘을 함께 바꿉니다.
FROM quay.io/prometheus/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0

ARG CORE_VERSION=dev
ARG CORE_REVISION=unknown
# 코어 서버의 core-server.sh가 이 값(커밋 시각, epoch 초)으로 버전의 앞뒤를 판단합니다. 빌드 캐시 때문에 이미지의 Created는 버전마다 같을 수 있습니다.
ARG CORE_COMMIT_TIME=0
LABEL org.opencontainers.image.title="monitoring-core-prometheus" \
      org.opencontainers.image.version="${CORE_VERSION}" \
      org.opencontainers.image.revision="${CORE_REVISION}" \
      monitoring-core.commit-time="${CORE_COMMIT_TIME}" \
      org.opencontainers.image.source="https://github.com/subinggrae/server-monitor"

# render-config.sh가 검사해 펼칠 코어 설정 값의 이름입니다. 값은 이미지에 넣지 않고 컨테이너 환경 변수로 받습니다.
ENV CORE_SETTINGS="CORE_RETENTION_DAYS CORE_RETENTION_SIZE_GB"

COPY render-config.sh core-settings.sh /etc/core/
COPY prometheus/prometheus.yml /etc/prometheus/prometheus.yml.template
COPY prometheus/rules/ /etc/prometheus/rules/

ENTRYPOINT ["/bin/sh", "/etc/core/render-config.sh", "/etc/prometheus/prometheus.yml.template", "/tmp/prometheus.yml", "/bin/prometheus"]
CMD ["--config.file=/tmp/prometheus.yml", "--storage.tsdb.path=/prometheus"]
