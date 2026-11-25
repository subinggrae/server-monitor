# 0018. CA 인증서 파일은 `.env`에 적은 OS의 경로를 바로 붙인다

- 상태: 승인
- 날짜: 2026-11-25
- 관련: [요구사항 0027](../requirements/0027-ca-file-path.md), 지운 계획은 [PR #72](https://github.com/subinggrae/server-monitor/pull/72)에서 볼 수 있다, [요구사항 0015](../requirements/0015-core-minimal-image.md), [ADR 0012](0012-core-render-outside-image.md)(이 ADR이 그 결정 가운데 CA 인증서 파일을 설치 폴더의 `certs/`에 복사해 붙인다는 문장만 대신함)

## 배경과 문제

모니터링 시스템 이미지에는 CA 묶음이 없다(요구사항 0015). Blackbox Exporter의 HTTPS 확인은 모니터링 서버 OS의 CA 인증서 파일을 쓴다. ADR 0012는 운영자가 그 파일을 설치 폴더의 `certs/`에 복사하고 compose 파일이 복사본을 붙이게 했다. 운영 명령이 시스템 파일을 찾거나 복사하지 않게 하려는 결정이었다.

복사본은 OS가 CA 인증서를 갱신해도 따라 바뀌지 않는다. 운영자가 갱신할 때마다 다시 복사해야 하고, 잊으면 새 인증서를 쓰는 HTTPS 주소의 확인이 실패한다.

## 검토한 선택지

- **복사 유지**: 바뀌는 것이 없다 / OS의 CA를 갱신할 때마다 다시 복사해야 한다.
- **운영 명령이 띄울 때마다 OS의 파일을 복사**: 운영자가 할 일이 없다 / 운영 명령이 OS별 경로를 찾고 시스템 파일을 다뤄야 한다. 2026-11-10에 버렸다.
- **`.env`에 OS의 파일 경로를 적고 compose 파일이 바로 붙임**: OS의 CA 갱신이 `start`만으로 반영된다. 운영 명령은 지금처럼 확인만 한다 / 설정 값이 하나 늘고, 예전 버전으로 되돌릴 때를 위해 복사본을 한동안 남겨야 한다.

## 결정

세 번째 선택지를 고른다(사람 확인: 2026-11-25, 관리자 서버 Testigo_Mng). `.env`의 `MONITORING_CA_FILE`에 OS의 CA 인증서 파일 경로를 적으면 compose 파일이 그 파일을 Blackbox Exporter에만 `/etc/ssl/certs/ca-certificates.crt`로 읽기 전용으로 붙인다. 값을 비우면 지금처럼 설치 폴더의 `certs/ca-certificates.crt`를 붙인다.

운영 명령은 값이 절대 경로이고 영문자, 숫자, `.`, `_`, `/`, `-`만 쓰는지 검사한다. 볼륨 표기(`원본:대상:ro`)가 깨지지 않게 하기 위해서다. 링크는 따라가 실제 파일이 있고, 폴더가 아니고, 비어 있지 않고, 다른 사용자가 읽을 수 있는지 확인한다. OS의 파일을 찾거나 복사하지 않는다는 ADR 0012의 원칙은 그대로다. CA 해시는 그 경로의 파일로 계산해, OS의 CA가 바뀐 뒤 `start`하면 세 컨테이너를 다시 만든다.

ADR 0012의 결정에서 "운영자가 운영 가이드대로 설치 폴더의 `certs/`에 복사하고, compose 파일이 그 파일을 Blackbox Exporter 컨테이너에만 읽기 전용으로 붙인다"를 대신한다. 운영 명령이 파일을 확인만 한다는 것과 나머지 결정은 그대로다. ADR 템플릿에는 일부만 대체하는 것을 표시하는 상태 값이 없어서 ADR 0012는 고치지 않고, 이 ADR에서 가리킨다.

## 결과

- 좋아지는 점: OS의 CA를 갱신하거나 사내 인증 기관을 등록하면 `start`만으로 반영된다. 이미 설치한 서버는 값을 적지 않으면 그대로 돈다.
- 감수할 점:
  - 이 값을 모르는 예전 버전으로 되돌릴 수 있는 동안은 `certs/` 복사본을 남겨야 한다.
  - Windows 개발 환경에서는 이 값을 비워 둔다. 운영 명령이 Linux 컨테이너 안에서 실행되어 Windows 경로를 볼 수 없다.
- 다시 볼 신호: 모니터링 서버 OS가 CA 인증서를 파일 하나가 아닌 폴더로만 제공할 때

## 확인 방법

- `infrastructure/tests/monitoring-server.test.sh`의 CA 인증서 파일 시험
- `node scripts/build-monitoring-bundle.mjs --check`의 compose 확인(Blackbox Exporter에만 붙고, 값이 없으면 `certs/`를 씀)
- Linux 확인(2026-11-25): Docker 안에 띄운 Linux Docker 호스트(Alpine, Docker 29)에 RHEL처럼 `/etc/pki/tls/certs/ca-bundle.crt`를 OS의 CA 인증서 파일로 가는 링크로 두었다. 묶음을 운영 가이드의 설치 절차대로 설치하고 이 경로를 적어 `start`하자, Blackbox Exporter에 그 경로가 붙고 공인 인증서를 쓰는 HTTPS 주소 두 곳의 `http_2xx` 확인이 성공했다(`probe_success 1`). 링크가 CA가 아닌 파일을 가리키면 인증서 오류로 실패하는 것은 Docker Desktop의 Linux VM에서 확인했다. SELinux를 켠 RHEL과 macOS는 확인하지 않았다([#73](https://github.com/subinggrae/server-monitor/issues/73))
