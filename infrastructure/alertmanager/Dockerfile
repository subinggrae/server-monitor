# 코어의 Alertmanager 이미지입니다. 공개 이미지에 설정 템플릿을 넣습니다. (docs/decisions/0009)
#
# 빌드 context는 infrastructure/입니다. webhook token은 이미지에 넣지 않고 Docker secret으로 받습니다.
# 앱 주소(MONITORING_APP_ADDRESS)는 컨테이너가 시작할 때 받아 render-config.sh가 설정에 펼칩니다.
FROM quay.io/prometheus/alertmanager:v0.34.1@sha256:e9733bafb1bdef9b00e25a21f8f99dc26a22224bf16641ad754d1649f4c3357a

ARG CORE_VERSION=dev
ARG CORE_REVISION=unknown
LABEL org.opencontainers.image.title="monitoring-core-alertmanager" \
      org.opencontainers.image.version="${CORE_VERSION}" \
      org.opencontainers.image.revision="${CORE_REVISION}" \
      org.opencontainers.image.source="https://github.com/subinggrae/server-monitor"

# token 파일이 없으면 webhook이 401로 거부되어 알림이 조용히 끊기므로, 시작할 때 확인합니다.
ENV CORE_REQUIRED_FILES=/run/secrets/alertmanager_webhook_token

COPY render-config.sh /etc/core/render-config.sh
COPY alertmanager/alertmanager.yml /etc/alertmanager/alertmanager.yml.template

ENTRYPOINT ["/bin/sh", "/etc/core/render-config.sh", "/etc/alertmanager/alertmanager.yml.template", "/tmp/alertmanager.yml", "/bin/alertmanager"]
CMD ["--config.file=/tmp/alertmanager.yml", "--storage.path=/alertmanager", "--enable-feature=utf8-strict-mode"]
