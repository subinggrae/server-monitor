# 코어의 Blackbox Exporter 이미지입니다. 공식 이미지에서 실행 파일만 빈 이미지로 옮깁니다. (docs/decisions/0012)
#
# 빌드 context는 infrastructure/입니다.
# 셸, BusyBox, CA 묶음, 시간대 자료는 담지 않습니다. 묶음에 Blackbox Exporter 밖의 라이선스를 더하지 않기 위해서입니다. (요구사항 0015)
# module 정의(blackbox.yml)는 코어 서버의 운영 명령(core-server.sh render)이 펼치고, compose 파일이 /etc/core/에 읽기 전용으로 붙입니다.
# HTTPS 확인에 쓰는 CA 인증서는 운영자가 코어 서버 OS의 파일을 복사해 두고, compose 파일이 Go가 가장 먼저 찾는 경로에 붙입니다.
FROM quay.io/prometheus/blackbox-exporter:v0.28.0@sha256:e753ff9f3fc458d02cca5eddab5a77e1c175eee484a8925ac7d524f04366c2fc AS src

FROM scratch

ARG CORE_VERSION=dev
ARG CORE_REVISION=unknown
# 코어 서버의 core-server.sh가 이 값(커밋 시각, epoch 초)으로 버전의 앞뒤를 판단합니다. 빌드 캐시 때문에 이미지의 Created는 버전마다 같을 수 있습니다.
ARG CORE_COMMIT_TIME=0
LABEL org.opencontainers.image.title="monitoring-core-blackbox-exporter" \
      org.opencontainers.image.version="${CORE_VERSION}" \
      org.opencontainers.image.revision="${CORE_REVISION}" \
      monitoring-core.commit-time="${CORE_COMMIT_TIME}" \
      org.opencontainers.image.source="https://github.com/subinggrae/server-monitor"

COPY --from=src /bin/blackbox_exporter /bin/blackbox_exporter

# 공식 이미지처럼 root로 돕니다. ping 확인에 raw socket이 필요합니다.
USER 0:0
EXPOSE 9115
ENTRYPOINT ["/bin/blackbox_exporter"]
CMD ["--config.file=/etc/core/blackbox.yml"]
