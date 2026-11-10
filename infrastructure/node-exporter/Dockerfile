# 감시 대상 Linux 서버에 띄우는 node_exporter 이미지입니다. 공식 이미지에서 실행 파일만 빈 이미지로 옮깁니다. (docs/decisions/0012)
#
# 셸, BusyBox, CA 묶음, 시간대 자료는 담지 않습니다. 묶음에 node_exporter 밖의 라이선스를 더하지 않기 위해서입니다. (요구사항 0015)
# 실행 파일은 정적 링크라 셸과 공유 라이브러리 없이 돕니다. 실행 방식(호스트 네트워크, 읽기 전용 루트)은 compose.yml에 있습니다.
# 공식 이미지는 태그와 digest로 고정합니다. 버전을 올릴 때 둘을 함께 바꾸고, scripts/build-core-bundle.mjs의 NODE_EXPORTER도 바꿉니다.
FROM quay.io/prometheus/node-exporter:v1.12.1@sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0 AS src

FROM scratch

ARG CORE_VERSION=dev
ARG CORE_REVISION=unknown
LABEL org.opencontainers.image.title="monitoring-node-exporter" \
      org.opencontainers.image.version="${CORE_VERSION}" \
      org.opencontainers.image.revision="${CORE_REVISION}" \
      org.opencontainers.image.source="https://github.com/subinggrae/server-monitor"

COPY --from=src /bin/node_exporter /bin/node_exporter

# 빈 이미지에는 /etc/passwd가 없어 공식 이미지의 nobody를 숫자로 씁니다.
USER 65534:65534
EXPOSE 9100
ENTRYPOINT ["/bin/node_exporter"]
