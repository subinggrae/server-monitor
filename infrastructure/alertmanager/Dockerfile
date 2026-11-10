# 코어의 Alertmanager 이미지입니다. 공식 이미지에서 실행 파일만 빈 이미지로 옮깁니다. (docs/decisions/0012)
#
# 빌드 context는 infrastructure/입니다. webhook token은 이미지에 넣지 않고 Docker secret으로 받습니다.
# 셸, BusyBox, amtool, CA 묶음, 시간대 자료는 담지 않습니다. 묶음에 Alertmanager 밖의 라이선스를 더하지 않기 위해서입니다. (요구사항 0015)
# 설정(alertmanager.yml)은 코어 서버의 운영 명령(core-server.sh render)이 펼치고, compose 파일이 /etc/core/에 읽기 전용으로 붙입니다.
# Alertmanager는 token 파일이 없어도 경고 없이 뜨므로, 운영 명령이 펼치기 전에 token 파일을 확인합니다.
#
# 공식 이미지는 태그와 digest로 고정합니다. 빌드 PC의 설정 검사는 이 단계(src)만 빌드해 amtool을 씁니다.
FROM quay.io/prometheus/alertmanager:v0.34.1@sha256:e9733bafb1bdef9b00e25a21f8f99dc26a22224bf16641ad754d1649f4c3357a AS src

FROM scratch

ARG CORE_VERSION=dev
ARG CORE_REVISION=unknown
# 코어 서버의 core-server.sh가 이 값(커밋 시각, epoch 초)으로 버전의 앞뒤를 판단합니다. 빌드 캐시 때문에 이미지의 Created는 버전마다 같을 수 있습니다.
ARG CORE_COMMIT_TIME=0
LABEL org.opencontainers.image.title="monitoring-core-alertmanager" \
      org.opencontainers.image.version="${CORE_VERSION}" \
      org.opencontainers.image.revision="${CORE_REVISION}" \
      monitoring-core.commit-time="${CORE_COMMIT_TIME}" \
      org.opencontainers.image.source="https://github.com/subinggrae/server-monitor"

COPY --from=src /bin/alertmanager /bin/alertmanager
# 빈 이미지에서는 mkdir을 쓸 수 없어 공식 이미지의 빈 기록 폴더를 옮깁니다. 새 volume에는 이 폴더의 소유자(nobody)가 옮겨집니다.
COPY --from=src --chown=65534:65534 /alertmanager /alertmanager

# 빈 이미지에는 /etc/passwd가 없어 nobody를 숫자로 씁니다.
USER 65534:65534
WORKDIR /alertmanager
VOLUME ["/alertmanager"]
EXPOSE 9093
ENTRYPOINT ["/bin/alertmanager"]
CMD ["--config.file=/etc/core/alertmanager.yml", "--storage.path=/alertmanager", "--enable-feature=utf8-strict-mode"]
