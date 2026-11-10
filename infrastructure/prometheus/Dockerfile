# 코어의 Prometheus 이미지입니다. 공식 이미지에서 실행 파일만 빈 이미지로 옮기고 Alert 규칙을 넣습니다. (docs/decisions/0012)
#
# 빌드 context는 infrastructure/입니다. 비밀값은 .dockerignore로 빠집니다.
# 셸, BusyBox, promtool, CA 묶음, 시간대 자료는 담지 않습니다. 묶음에 Prometheus 밖의 라이선스를 더하지 않기 위해서입니다. (요구사항 0015)
# 설정(prometheus.yml)은 코어 서버의 운영 명령(core-server.sh render)이 펼치고, compose 파일이 /etc/core/에 읽기 전용으로 붙입니다.
# 실행 파일은 정적 링크라 셸과 공유 라이브러리 없이 돕니다. 시각은 시간대 자료가 없어 UTC입니다.
# /etc/nsswitch.conf가 없으면 Go는 /etc/hosts를 DNS보다 먼저 봅니다. 공식 이미지의 순서와 같습니다.
#
# 공식 이미지는 태그와 digest로 고정합니다. 버전을 올릴 때 둘을 함께 바꿉니다.
# 빌드 PC의 설정 검사와 개발용 펼치기(core-dev.bat start)는 이 단계(src)만 빌드해 promtool과 셸을 씁니다.
FROM quay.io/prometheus/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0 AS src

FROM scratch

ARG CORE_VERSION=dev
ARG CORE_REVISION=unknown
# 코어 서버의 core-server.sh가 이 값(커밋 시각, epoch 초)으로 버전의 앞뒤를 판단합니다. 빌드 캐시 때문에 이미지의 Created는 버전마다 같을 수 있습니다.
ARG CORE_COMMIT_TIME=0
LABEL org.opencontainers.image.title="monitoring-core-prometheus" \
      org.opencontainers.image.version="${CORE_VERSION}" \
      org.opencontainers.image.revision="${CORE_REVISION}" \
      monitoring-core.commit-time="${CORE_COMMIT_TIME}" \
      org.opencontainers.image.source="https://github.com/subinggrae/server-monitor"

COPY --from=src /bin/prometheus /bin/prometheus
# 빈 이미지에서는 mkdir을 쓸 수 없어 공식 이미지의 빈 기록 폴더를 옮깁니다.
# 새 volume을 처음 붙이면 Docker가 이 폴더의 소유자(nobody)를 volume에 옮기므로 새 서버에서도 기록을 씁니다.
COPY --from=src --chown=65534:65534 /prometheus /prometheus
COPY prometheus/rules/ /etc/prometheus/rules/

# 빈 이미지에는 /etc/passwd가 없어 nobody를 숫자로 씁니다.
USER 65534:65534
WORKDIR /prometheus
VOLUME ["/prometheus"]
EXPOSE 9090
ENTRYPOINT ["/bin/prometheus"]
CMD ["--config.file=/etc/core/prometheus.yml", "--storage.tsdb.path=/prometheus"]
