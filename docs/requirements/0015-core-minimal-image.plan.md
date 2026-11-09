# 0015. 셸 없는 최소 코어 이미지 구현 계획

- 요구사항: [0015. 셸 없는 최소 코어 이미지](0015-core-minimal-image.md)
- 상태: 승인

## 접근 방법

코어 이미지와 node_exporter 이미지는 공식 이미지를 첫 단계(`FROM ... AS src`)로 두고, 빈 이미지(`scratch`)에 실행 파일만 옮겨 담는 다단계 빌드로 만든다. 세 프로그램의 실행 파일은 정적 링크라 셸과 공유 라이브러리 없이 뜬다. 2026-11-09의 빠른 확인([진행 중 발견](#진행-중-발견))에서 네 이미지 모두 이렇게 만들어 떴다. 공식 이미지는 지금처럼 태그와 digest로 고정한다.

설정 템플릿을 펼치는 일은 컨테이너 안의 `render-config.sh`에서 코어 서버의 운영 명령(`core-server.sh`)으로 옮긴다. 운영 명령에 설정 펼치기 작업을 새로 두고, 띄우기(`start`)도 이 작업을 거친 뒤 Compose를 부른다. 펼친 설정은 묶음 폴더의 `rendered/`에 쓰고, compose 파일이 읽기 전용으로 붙인다. 검사 규칙은 지금처럼 `core-settings.sh` 하나다.

이 방법을 고른 이유와 버린 방법은 다음과 같다. 결정은 [ADR 0012](../decisions/0012-core-render-outside-image.md)(제안)에 남긴다.

- **컨테이너 안에 셸을 두는 지금 방법**: 바꿀 것이 없지만 BusyBox(GPL-2.0)가 남아 요구사항의 목표와 맞지 않는다.
- **셸이 있는 이미지로 한 번만 도는 준비 컨테이너(init container)**: 직접 실행 경로가 그대로 남지만, 그 이미지의 셸(BusyBox 등)이 묶음에 다시 들어간다.
- **Compose 변수로 설정 내용을 펼침(`configs.content`)**: 스크립트가 없지만 형식을 검사할 수 없어 [ADR 0011](../decisions/0011-core-address-format-check.md)과 맞지 않고, [ADR 0007](../decisions/0007-core-app-contract.md)이 이미 버린 방법이다.
- **펼치기와 정상 확인을 하는 작은 프로그램을 따로 만들어 이미지에 넣음**: 직접 실행 경로가 그대로 남지만, 세 프로그램 밖의 코드와 Go 런타임 라이선스가 이미지에 들어간다(요구사항의 하지 않는 것).

설정 템플릿은 이미지가 아니라 묶음 폴더의 `templates/`에 둔다. 이미지와 템플릿은 같은 커밋에서 함께 만들고, 묶음의 `SHA256SUMS`가 둘을 함께 지킨다. 그래서 띄우는 이미지와 같은 버전의 템플릿에서 펼친다는 요구사항 S17을 지킨다. 템플릿을 이미지 안에 두고 `docker create`와 `docker cp`로 꺼내는 방법도 있지만, 펼칠 때마다 컨테이너를 만들고 지워야 하고 운영 명령 시험의 가짜 docker가 복잡해져서 고르지 않았다. Alert 규칙은 펼칠 값이 없으므로 지금처럼 Prometheus 이미지 안에 둔다.

## 바뀌는 부분

바뀌는 파일은 다음과 같다. 문서는 마지막 작업에서 함께 고친다.

| 위치 | 변경 |
|---|---|
| `infrastructure/prometheus/Dockerfile`, `alertmanager/Dockerfile`, `blackbox/Dockerfile` | 실행 파일과 규칙만 담은 최소 이미지로 바꾼다([최소 이미지](#최소-이미지)) |
| `infrastructure/node-exporter/Dockerfile`(새 파일) | node_exporter 실행 파일만 담은 최소 이미지를 만든다 |
| `infrastructure/render-config.sh` | 지우고, 검사와 펼치기를 `core-server.sh`로 옮긴다 |
| `infrastructure/core-server.sh` | 설정 펼치기 작업, CA 파일 찾기, 준비 확인을 더한다 |
| `infrastructure/core-settings.sh` | 검사 규칙은 그대로 두고 `render-config.sh`를 가리키는 주석만 고친다 |
| `infrastructure/compose.yml` | healthcheck와 환경 변수 대신 펼친 설정을 붙인다(아래 목록) |
| `infrastructure/node-exporter/compose.yml` | 공식 이미지 대신 이 저장소가 빌드한 최소 이미지를 쓴다 |
| `infrastructure/.dockerignore`, `.gitignore` | `infrastructure/rendered/`를 빌드 context와 Git에서 뺀다 |
| `infrastructure/tests/core-server.test.sh` | 펼치기, CA 찾기, 준비 확인의 시험을 더한다([테스트 계획](#테스트-계획)) |
| `scripts/build-core-bundle.mjs` | 검사를 공식 이미지의 도구로 옮기고, 이미지 파일 목록 검사를 더하고, BusyBox 소스 싣기를 지우고, node_exporter 이미지를 빌드한다 |
| `core-dev.bat` | `start`가 셸이 있는 공식 이미지 안에서 펼치기 작업을 실행한 뒤 Compose로 띄운다 |
| `infrastructure/licenses/busybox-LICENSE` | 지운다 |
| `infrastructure/licenses/README.txt` | BusyBox 줄과 `sources/` 안내를 지운다 |
| `infrastructure/.env.example` | "바꾸면 `docker compose up -d`" 같은 직접 실행 안내를 펼치기 작업 뒤로 고친다 |
| `docs/OPERATIONS.md` | 코어 서버 사양 표(다시 잰 값), 처음 따라 하기(`sources/`, `running healthy`), 운영 명령 없이 실행, 운영 작업, 문제 해결, 라이선스, OS exporter 설치 |
| `docs/ARCHITECTURE.md` | 실행 단위의 코어 이미지 설명, 코드 지도의 `infrastructure/`, 지켜야 할 경계의 앱 주소와 코어 값 검사 문장 |
| `docs/DEVELOPMENT.md` | 빌드 검사 설명(이미지 안의 promtool·amtool을 전제로 한 문장) |
| `docs/INTEGRATION.md` | 코어 설정 절의 "이미지 안의 `render-config.sh`가 주소를 넣는다" 문장 |
| `docs/LICENSES.md` | 고지의 코어 묶음 항목, 들여온 것 표의 `busybox-LICENSE` 행 |
| `docs/decisions/0012-core-render-outside-image.md` | 이 계획과 함께 제안으로 쓰고, 구현을 마칠 때 승인으로 바꾼다 |

표의 세 파일은 다음처럼 바꾼다.

- **`core-server.sh`**: `start`와 `rotate-token`은 healthcheck 대신 준비 확인을 기다리고, `status`는 상태와 함께 준비 여부를 보인다.
- **`compose.yml`**: 세 서비스의 `healthcheck`와 `environment`를 지운다. 대신 `env_file: rendered/core.env`(`required: true`)와 펼친 설정 파일의 읽기 전용 bind mount(컨테이너 안 `/etc/core/`)를 두고, Blackbox Exporter에만 CA 파일을 붙인다. `${MONITORING_APP_ADDRESS:?}` 검사는 펼치기 작업이 맡으므로 지운다. 포트, volume, secret, 네트워크는 그대로다.
- **node_exporter `compose.yml`**: 저장소에서는 `build:`, 묶음에서는 `pull_policy: never`로 쓴다. 실행 방식(`network_mode: host`, `pid: host`, `/:/host:ro,rslave`)은 그대로다.

바뀐 구조는 다음과 같다.

```text
지금
  core-server.sh start ──(검사)──> docker compose up
  컨테이너 시작: /bin/sh render-config.sh ──(검사, 템플릿 → /tmp/*.yml)──> 프로그램
  healthcheck: BusyBox wget → /-/ready

바뀐 뒤
  core-server.sh start  ─┐
  core-server.sh render ─┴─(검사, token·CA 확인, templates/ → rendered/)──> docker compose up
  컨테이너 시작: 프로그램만(rendered/의 설정을 읽기 전용으로 읽음)
  정상 확인: core-server.sh가 코어 서버에서 curl 또는 wget → 127.0.0.1:<호스트 포트>/-/ready
```

### 데이터 모델 변경

해당 없음. volume `prometheus-data`, `alertmanager-data`의 위치와 소유자(`nobody`)가 그대로다.

### 계약 변경

해당 없음. 통합 계약의 API, label, series, 컨테이너 이름과 안 포트가 그대로다. INTEGRATION.md는 설정을 펼치는 곳의 설명만 고친다.

## 최소 이미지

이미지마다 담는 것은 다음과 같다. 실행 사용자와 쓰기 위치는 공식 이미지에서 읽은 값 그대로다(요구사항의 배경).

| 이미지 | 담는 것 | 실행 사용자 | 쓰기 위치 |
|---|---|---|---|
| Prometheus | `/bin/prometheus`, 규칙, 빈 `/prometheus` | `65534:65534` | `/prometheus`(volume) |
| Alertmanager | `/bin/alertmanager`, 빈 `/alertmanager` | `65534:65534` | `/alertmanager`(volume) |
| Blackbox Exporter | `/bin/blackbox_exporter` | `0:0` | 없음 |
| node_exporter | `/bin/node_exporter` | `65534:65534` | 없음 |

만들 때 다음을 지킨다.

- 빈 이미지에는 `/etc/passwd`가 없으므로 `USER`를 이름(`nobody`)이 아니라 숫자로 쓴다.
- 빈 이미지에서는 `mkdir`을 쓸 수 없다. 그래서 공식 이미지의 빈 데이터 폴더를 `COPY --from=src --chown=65534:65534`로 옮긴다. 새 volume을 처음 붙이면 Docker가 이 폴더의 소유자를 volume에 옮기므로, 새 서버에서도 프로그램이 기록을 쓴다.
- 지금 이미지의 `/tmp`에 쓰던 펼친 설정은 `/etc/core/`의 bind mount로 바뀐다. 그래서 프로그램이 이미지 안에 쓰는 곳이 없다.
- label(`org.opencontainers.image.*`, `monitoring-core.commit-time`)과 빌드 인자는 지금 Dockerfile 그대로 옮긴다. 운영 명령이 이 label로 버전의 앞뒤를 판단한다.
- 공식 이미지의 `/LICENSE`, `/NOTICE`는 담지 않는다. 같은 파일이 묶음의 `licenses/`에 있다.

## 설정 펼치기 작업

운영 명령에 작업 `render`를 더한다(사람 확인: 2026-11-09). 문서에서는 "설정 펼치기"로 부른다. `start`는 `render`와 같은 함수를 부른 뒤 지금 순서대로 이어 간다.

`render`는 다음 순서로 동작하고, 하나라도 실패하면 `rendered/`를 바꾸지 않고 실패로 끝난다. 컨테이너는 만들거나 바꾸지 않는다.

1. `.env`가 있는지 보고, 관리자 서버 주소(`core_valid_address`)와 코어 값 여섯 개(`core_setting_check`)를 지금 `check_env`, `check_settings`와 같은 함수로 검사한다.
2. token 파일을 지금 `check_token`과 같은 함수로 확인한다.
3. CA 파일을 찾는다([CA 파일 찾기와 붙이기](#ca-파일-찾기와-붙이기)).
4. 묶음의 `templates/`에서 세 설정을 펼쳐 `rendered/`의 임시 파일에 쓴다. 펼치는 글자는 지금 `render-config.sh`처럼 `core_setting_text`의 결과와 검사를 통과한 주소뿐이다.
5. 펼친 파일에 자리표시(`@…@`)가 남지 않았는지 본다.
6. `rendered/core.env`에 검사를 통과한 코어 값(`CORE_RETENTION_DAYS` 등)을 쓴다. compose 파일은 이 파일을 `env_file`로 컨테이너에 넘긴다. 프로그램은 이 값을 읽지 않지만, `status`와 기록 보관 값 줄임 확인이 지금처럼 컨테이너의 환경 변수에서 떠 있는 값을 읽는다.
7. 임시 파일을 같은 폴더 안에서 이름만 바꿔 한꺼번에 옮긴다. 권한은 파일 0644, 폴더 0755다. 컨테이너 사용자 `nobody`가 읽어야 하고, 다른 사용자가 고치면 안 되기 때문이다.

`start`는 지금 순서(체크섬 → 검사 → token → 이미지 불러오기 → 버전 확인 → 보관 값 줄임 확인 → 띄우기 → 정상 확인)를 지키되, 검사와 token 확인 자리에서 `render`를 부른다. 다만 이전 버전 확인이나 보관 값 줄임 확인에서 운영자가 멈추면 `rendered/`를 바꾸지 않아야 한다. 그래서 4~7단계(쓰기)는 두 확인 뒤, Compose를 부르기 직전에 한다.

펼친 설정이 없을 때 Compose가 컨테이너를 만들지 않게 하는 방법은 `env_file`의 `required: true`다. 빠른 확인에서 `rendered/core.env`가 없으면 `docker compose up -d`가 `env file … not found`로 아무것도 만들지 않고 멈췄다. bind mount의 `create_host_path: false`와 `configs.file`은 Docker Desktop(Compose v5.1.0)에서 없는 파일 자리에 빈 폴더를 만들었으므로 쓰지 않는다([진행 중 발견](#진행-중-발견)). Compose의 오류는 영어이므로, 운영 가이드의 문제 해결에 `rendered/core.env … not found`가 보이면 `render`를 먼저 실행하라는 행을 둔다(요구사항 S16).

개발용 띄우기(`core-dev.bat start`)는 Windows에 POSIX sh가 없으므로, 빌드 PC에 받은 공식 Prometheus 이미지의 BusyBox `sh`로 `core-server.sh`의 펼치기 함수를 실행한다. 저장소에서는 템플릿 위치(`infrastructure/<구성 요소>/`)와 출력 위치(`infrastructure/rendered/`)가 묶음과 다르므로, 함수가 두 위치를 인자로 받는다. 빌드 PC의 이 셸은 묶음에 들어가지 않는다.

## 정상 확인

compose 파일의 `healthcheck`를 지우고, `core-server.sh`가 코어 서버에서 `http://127.0.0.1:<호스트 포트><경로>`를 부른다. 호스트 포트는 검사를 통과한 `CORE_PROMETHEUS_PORT`, `CORE_ALERTMANAGER_PORT`, `CORE_BLACKBOX_PORT`이고, 경로는 지금 healthcheck와 같다.

| 프로그램 | 준비 확인 주소 | 빠른 확인 결과 |
|---|---|---|
| Prometheus | `/-/ready` | 200 |
| Alertmanager | `/-/ready` | 200 |
| Blackbox Exporter | `/-/healthy` | 200 |

부르는 도구는 `curl`을 먼저 찾고, 없으면 `wget`을 쓴다. 두 도구 모두 GNU coreutils나 BusyBox의 옵션 차이가 있으므로 `curl -fsS -m 5 -o /dev/null`, `wget -q -T 5 -O /dev/null`처럼 두 구현이 함께 받는 옵션만 쓴다. `wait_healthy`는 이름과 판단만 바꾸고, 최대 3분 기다림과 정상이 아닌 컨테이너와 `docker logs` 명령을 보이는 동작은 그대로다. `status`는 `docker inspect`의 상태(`running` 등) 옆에 준비 여부를 보인다. 코어가 멈춰 있으면 부르지 않는다.

코어 서버에 두 도구가 기본으로 있는지 공식 자료로 확인한 결과는 다음과 같다(확인일 2026-11-09).

| OS | `curl` | `wget` | 근거 |
|---|---|---|---|
| Ubuntu 24.04 Server(클라우드 이미지) | 있음 | 있음 | [Ubuntu 클라우드 이미지 manifest](https://cloud-images.ubuntu.com/releases/noble/release/ubuntu-24.04-server-cloudimg-amd64.manifest) |
| Debian 12(클라우드 이미지) | 있음 | 있음 | [Debian 클라우드 이미지 패키지 목록](https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-amd64.json) |
| Amazon Linux 2023(AMI와 컨테이너) | 있음(`curl-minimal`) | 확인하지 못함 | [AL2023의 curl 패키지 안내](https://docs.aws.amazon.com/linux/al2023/ug/curl-minimal.html) |
| RHEL 계열 | 확인하지 못함 | 확인하지 못함 | Red Hat 문서가 403을 돌려줘 읽지 못했다 |

ISO로 설치한 서버와 최소 설치는 위 목록과 다를 수 있고, 이는 확인하지 못했다. 그래서 두 도구가 모두 없으면 준비 확인 대신 세 컨테이너가 실행 중이고 다시 시작 횟수(`RestartCount`)가 늘지 않는지 보고, 준비 확인을 하지 못했다는 경고를 보인다. 띄우기는 막지 않는다(사람 확인: 2026-11-09).

## CA 파일 찾기와 붙이기

`render`는 Go가 Linux에서 찾는 파일 목록([`root_linux.go`](https://github.com/golang/go/blob/master/src/crypto/x509/root_linux.go), 확인일 2026-11-09)을 같은 순서로 보고, 처음 찾은 일반 파일을 쓴다.

1. `/etc/ssl/certs/ca-certificates.crt`(Debian·Ubuntu)
2. `/etc/pki/tls/certs/ca-bundle.crt`(Fedora·RHEL)
3. `/etc/ssl/ca-bundle.pem`(openSUSE)
4. `/etc/pki/tls/cacert.pem`
5. `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`(CentOS·RHEL 7)
6. `/etc/ssl/cert.pem`

RHEL 계열의 `/etc/pki/tls/certs/ca-bundle.crt`는 `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`을 가리키는 심볼릭 링크다. 같은 패키지가 `/etc/ssl/certs`도 `/etc/pki/tls/certs`를 가리키는 링크로 만든다([CentOS Stream 9의 `ca-certificates.spec`](https://gitlab.com/redhat/centos-stream/rpms/ca-certificates/-/raw/c9s/ca-certificates.spec), 확인일 2026-11-09). 그래서 링크를 따라간 실제 파일이 비어 있지 않은 일반 파일인지 본다.

찾은 파일은 Blackbox Exporter 컨테이너의 `/etc/ssl/certs/ca-certificates.crt`에만 읽기 전용으로 붙인다. Go가 이 경로를 가장 먼저 찾기 때문이다. `render`가 찾은 파일을 `rendered/ca-certificates.crt`로 복사하고, compose 파일은 그 복사본을 붙인다. 띄울 때마다 다시 복사한다(사람 확인: 2026-11-09). 검토한 방법은 다음 두 가지였다.

- **`render`가 찾은 파일을 `rendered/ca-certificates.crt`로 복사하고 그 복사본을 붙임**: compose 파일의 경로가 늘 같아 직접 실행 경로에서도 `.env`에 경로를 적지 않는다. OS가 인증서를 갱신하면 다음 `render`나 `start` 때 반영된다(요구사항 S4).
- **OS의 파일 경로를 그대로 붙임**: OS 파일을 바로 쓰지만, compose 파일이 경로를 알려면 Compose 변수가 필요하다. Compose 변수는 `.env`나 셸 환경 변수에서만 오므로, 직접 실행 경로에서는 `.env`에 경로를 적어야 해 요구사항의 하지 않는 것과 맞지 않는다.

첫 번째를 골랐다. 어느 쪽이든 CA 파일은 코어 서버의 파일이고 묶음에 들어가지 않는다.

찾지 못하면 찾아본 여섯 위치와 설치할 패키지를 보인다. 패키지 이름을 공식 자료로 확인한 결과는 다음과 같다(확인일 2026-11-09).

| OS | 패키지 | 근거 |
|---|---|---|
| Debian·Ubuntu | `ca-certificates` | 위 Ubuntu manifest, Debian 패키지 목록 |
| RHEL 계열(CentOS Stream 9) | `ca-certificates` | 위 `ca-certificates.spec` |
| Amazon Linux 2023 | 확인하지 못함 | AWS 패키지 목록이 너무 커서 끝까지 읽지 못했다 |

확인한 OS가 모두 `ca-certificates`라서, 안내 문구는 `ca-certificates 패키지를 설치하고 다시 실행해 주세요`로 쓰고 OS를 나누지 않는다. Amazon Linux 2023의 이름은 작업 순서의 수동 확인(C4)에서 그 서버의 `rpm -qf /etc/pki/tls/certs/ca-bundle.crt`로 확인한다. 이름이 다르면 그때 안내 문구를 고친다.

## node_exporter

`infrastructure/node-exporter/Dockerfile`로 최소 이미지를 만든다. 공식 이미지는 지금 빌드 스크립트의 `NODE_EXPORTER.imageDigest`로 고정하던 digest를 `FROM`에 옮겨 고정한다. 이미지 이름은 `monitoring-node-exporter:<묶음 버전>`으로 하고, 묶음의 `compose.yml`은 코어처럼 `build:`를 `pull_policy: never`로 바꿔 싣는다. 빠른 확인에서 최소 이미지와 공식 이미지의 `/metrics`가 같은 지표 이름 306개를 냈다.

OPERATIONS.md는 인터넷이 되는 감시 대상 서버에서 저장소의 `compose.yml`만 복사해 띄우는 방법도 안내한다. 최소 이미지는 공개 저장소에 없으므로 이 방법은 동작하지 않는다. 그래서 저장소의 `compose.yml`도 `build:`로 최소 이미지를 빌드해 쓰고, 운영 가이드는 인터넷이 되는 서버에도 묶음이나 저장소 폴더를 쓰라고 안내한다(사람 확인: 2026-11-09). 개발용 테스트 대상(루트 `compose.yml`의 `os-test-node-exporter`)은 묶음에 들어가지 않으므로 공식 이미지를 그대로 둔다.

Docker가 없는 서버용 실행 파일(`node_exporter-1.12.1.linux-amd64.tar.gz`)과 systemd unit은 바꾸지 않는다.

## 빌드 검사

`scripts/build-core-bundle.mjs`의 검사는 운영 이미지 안의 도구 대신 빌드 PC에 받은 공식 이미지를 쓴다. 공식 이미지는 Dockerfile과 같은 digest로 받는다.

| 검사 | 지금 | 바뀐 뒤 |
|---|---|---|
| 허용 범위 읽기(`readSettingRanges`) | 운영 이미지 안의 `core-settings.sh` | 공식 Prometheus 이미지의 BusyBox `sh`로 저장소의 `core-settings.sh` |
| 세 벌로 펼치기 | 운영 이미지의 `render-config.sh` | 같은 셸로 `core-server.sh`의 펼치기 함수 |
| Prometheus 설정 검사 | 운영 이미지의 `promtool check config` | 공식 이미지의 `promtool check config`(규칙은 운영 이미지에서 꺼냄) |
| 규칙 테스트 | 운영 이미지의 `promtool test rules` | 공식 이미지의 `promtool test rules`(운영 이미지에서 꺼낸 규칙 옆에 테스트 파일을 둠) |
| Alertmanager 설정 검사 | 운영 이미지의 `amtool check-config` | 공식 이미지의 `amtool check-config` |
| Blackbox 설정 검사 | 운영 이미지의 `--config.check` | 그대로(셸 없이 실행 파일만 부름) |
| 실행 인자(`checkPrometheusArgs`) | 시작 스크립트로 인자를 찍어 봄 | `docker image inspect`의 `Entrypoint`와 `Cmd`를 기대값과 비교 |
| 틀린 값·주소 거부(`checkRejectedSettings`, `checkRejectedAddresses`) | 운영 이미지의 시작 스크립트 | 운영 명령 시험으로 옮긴다(같은 값 목록) |
| 운영 명령 시험 | 운영 이미지의 BusyBox `sh` | 공식 Prometheus 이미지의 BusyBox `sh` |
| 이미지 파일 목록(새 검사) | 없음 | 이미지마다 `docker create`와 `docker export`로 파일 목록을 꺼내 허용 목록과 비교 |
| 이미지 설정(새 검사) | 없음 | `docker image inspect`의 `User`, `WorkingDir`, `ExposedPorts`, label을 기대값과 비교 |

운영 이미지에서 규칙을 꺼낼 때는 `docker create`로 컨테이너를 만들고 `docker cp`로 `/etc/prometheus/rules`를 꺼낸 뒤 지운다. 셸 없이 할 수 있고, 이미지에 들어간 규칙을 그대로 검사한다는 지금 원칙을 지킨다. 허용 목록에는 Docker가 컨테이너마다 만드는 `/.dockerenv`, `/etc/hosts`, `/etc/hostname`, `/etc/resolv.conf`, `/dev`, `/proc`, `/sys`도 넣는다.

## 묶음과 라이선스

코어 묶음은 다음처럼 바뀐다.

| 파일 | 지금 | 바뀐 뒤 |
|---|---|---|
| `core-images.tar` | 약 167MB | 빠른 확인에서 약 100MB(구현한 뒤 다시 잼, C14) |
| `templates/` | 없음 | 세 설정 템플릿 |
| `licenses/busybox-LICENSE` | 있음 | 없음 |
| `licenses/README.txt` | BusyBox 줄과 `sources/` 안내가 있음 | 세 프로그램(node_exporter 묶음은 node_exporter)만 적음 |
| `sources/` | BusyBox 1.37.0, 1.38.0 소스 | 없음 |
| `rendered/` | 없음 | 없음(코어 서버에서 `render`가 만듦) |

node_exporter 묶음도 `sources/`와 `busybox-LICENSE`가 없어지고, `node-exporter-image.tar`는 최소 이미지가 된다(빠른 확인에서 약 12MB). 빌드 스크립트에서는 `BUSYBOX_SOURCES`, `BUSYBOX_MIRRORS`, `busyboxVersion`, BusyBox 소스 받기를 지우고, `addLicensesAndSources`는 라이선스 파일만 싣는다. 그래서 묶음 만들기가 인터넷에서 받는 것은 공식 이미지와 node_exporter 릴리스 파일뿐이다.

LICENSES.md의 고지에서는 BusyBox 소스를 싣는다는 문장과 2026-10-22 결정을, 이 요구사항과 ADR 0012를 가리키는 문장으로 바꾼다. 들여온 것 표의 `busybox-LICENSE` 행은 지운다. [#44](https://github.com/subinggrae/server-monitor/issues/44)는 구현 PR이 병합되면 필요 없어진 이유를 적어 닫는다.

## 다른 PR과 겹치는 파일

이 계획은 [PR #42](https://github.com/subinggrae/server-monitor/pull/42)(요구사항 0013) 위에서 시작한다. PR #42와 [PR #43](https://github.com/subinggrae/server-monitor/pull/43)(요구사항 0012, ADR 0010)은 모두 [PR #40](https://github.com/subinggrae/server-monitor/pull/40)을 기준으로 한다. 2026-11-09에 `gh pr view --json files`로 확인한 겹침은 다음과 같다.

| 파일 | PR #42 | PR #43 | 이 계획 |
|---|---|---|---|
| Dockerfile 세 개, `compose.yml`, `render-config.sh`, `core-server.sh`, `core-settings.sh`, 운영 명령 시험, 빌드 스크립트 | 고침 | 고치지 않음 | 고침(#42의 결과 위에서) |
| `infrastructure/alertmanager/alertmanager.yml`, `prometheus/rules/monitoring-checks.yml`과 그 시험 | 고치지 않음 | 고침 | 내용은 고치지 않음 |
| `docs/ARCHITECTURE.md`, `docs/OPERATIONS.md`, `docs/INTEGRATION.md` | 고침 | 고침 | 고침 |
| `docs/DEVELOPMENT.md` | 고침 | 고치지 않음 | 고침 |
| `docs/TESTING.md`, `docs/ADMIN-SERVER.md` | 고치지 않음 | 고침 | 고치지 않음 |

병합 순서는 #40, #42, #43, 이 계획의 구현 PR(#45)을 권한다. #45는 #42의 코드를 바로 바꾸므로 #42 뒤에 와야 한다. 또 #43과는 문서만 겹치므로, 가장 큰 변경인 #45를 마지막에 두고 #45 쪽에서 문서 충돌을 푼다. #42가 병합되면 #45의 base를 #42가 들어간 브랜치로 바꾼다. #43이 고친 Alert 규칙과 템플릿은 이미지에 그대로 들어가므로, #45의 빌드 검사가 #43의 규칙도 함께 검사한다.

## 위험과 되돌리기

- **새 서버의 volume에 쓸 수 없음**: 빈 이미지에 데이터 폴더가 없거나 소유자가 다르면 Prometheus와 Alertmanager가 기록을 쓰지 못한다. 빌드 검사의 파일 목록이 폴더와 소유자를 보고, 수동 확인(C3)에서 새 volume으로 띄운다. 빠른 확인에서는 새 volume에 기록이 쌓였다.
- **기존 서버의 갱신**: 컨테이너 이름, volume, 사용자가 같으므로 기록이 이어진다. 버전이 다른 묶음 두 개로 갱신과 되돌리기를 수동 확인한다(C6). 이전 버전으로 되돌리면 이전 묶음의 compose 파일과 이미지가 지금처럼 이미지 안에서 펼치므로 `rendered/`를 쓰지 않는다.
- **Docker가 없는 파일 자리에 빈 폴더를 만듦**: Docker Desktop에서 확인한 동작이고, Linux Docker Engine에서는 확인하지 않았다. `env_file`의 `required: true`가 Compose 단계에서 먼저 멈추게 하고, `render`는 `rendered/` 안에 폴더가 있으면 지우지 않고 무엇을 지우라고 안내한다.
- **준비 확인 도구가 없는 서버**: 실행 상태로 대신 보고 경고하며, 운영 명령 시험에 두 도구를 모두 뺀 경우를 넣는다.
- **CA 파일이 낡음**: 복사본을 쓰면 OS가 인증서를 갱신해도 다음 `render`나 `start` 전까지는 이전 인증서로 확인한다. 운영 가이드의 운영 작업에 "OS의 CA 인증서를 바꾼 뒤 `start`"를 쓴다.
- **문제를 컨테이너 안에서 살펴볼 수 없음**: 셸이 없어 `docker exec`를 쓸 수 없다. 운영 가이드의 문제 해결은 이미 `docker logs`와 HTTP API만 쓴다.
- **되돌리기**: 구현 PR 하나를 되돌리면 이전 Dockerfile, `render-config.sh`, compose 파일로 돌아간다. 데이터 이전이 없으므로 이미 갱신한 코어 서버는 이전 버전 묶음 폴더에서 `start`로 되돌린다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름:
  - 코어 서버의 `.env`와 셸 환경 변수의 값은 지금까지 컨테이너 안의 시작 스크립트와 운영 명령이 함께 검사했다. 바뀐 뒤에는 코어 서버의 운영 명령만 검사하고, 그 결과를 운영자 권한(보통 `sudo`)으로 묶음 폴더의 `rendered/`에 쓴다.
  - 코어 서버 OS의 CA 인증서 파일이 Blackbox Exporter의 신뢰 기준이 된다.
  - ARCHITECTURE.md의 지켜야 할 경계에서 "운영 명령과 시작 스크립트가 같은 `core-settings.sh`로 검사" 문장을 "운영 명령의 펼치기 작업이 검사"로 고친다.
- 잘못될 수 있는 것과 막는 방법:
  - 설정 값으로 YAML의 다른 항목이나 sed 명령을 바꿈(CWE-74, CWE-94): 지금처럼 `core-settings.sh`가 형식을 검사한 값만 펼친다. 거부 사례는 지금 빌드 검사의 값 목록(`15 --web.enable-admin-api`, `5|g`, `5&`, 줄바꿈 등)을 운영 명령 시험으로 옮긴다.
  - `.env`의 값이 셸 명령으로 실행됨(CWE-78): 지금처럼 `.env`를 `.`으로 읽지 않고 `env_value`로 읽는다. 펼치기 함수도 값을 `eval`하지 않는다. 시험에 `$(id)`가 든 주소와 값을 넣는다.
  - `rendered/`나 그 안의 파일이 다른 곳을 가리키는 심볼릭 링크라 운영자 권한으로 엉뚱한 파일을 덮어씀(CWE-59): `rendered/`와 그 안의 대상이 심볼릭 링크면 쓰지 않고 실패한다. 임시 파일은 `rendered/` 안에 `mktemp`로 만들고 이름만 바꾼다. 시험에 링크를 심어 둔 경우를 넣는다.
  - 펼친 설정을 다른 사용자가 고침(CWE-732): 파일 0644, 폴더 0755로 쓰고 시험이 권한을 본다.
  - CA 파일이 없을 때 인증서 확인을 끄는 것으로 넘어감(CWE-295): CA 파일을 찾지 못하면 띄우지 않는다. `insecure_skip_verify`를 어느 설정에도 두지 않고, 빌드 검사가 펼친 Blackbox 설정에 이 항목이 없는지 본다.
  - 공식 이미지를 바꿔치기한 것을 받음(CWE-494): 지금처럼 태그와 digest로 고정한다. node_exporter도 `FROM`의 digest로 고정한다.
  - token이 펼친 설정이나 출력에 들어감(CWE-532, CWE-200): token은 펼치지 않고 Docker secret으로만 넘긴다. 시험이 `rendered/`와 출력에 token 값이 없는지 본다.
- 받아들이는 위험(사람이 승인해야 함):
  - 직접 실행 경로에서 `render`가 token을 확인한 뒤 `docker compose up -d` 전에 token 파일을 지우면, Alertmanager가 token 없이 정상으로 뜬다(CWE-367). 빠른 확인에서 token 파일 없이도 Alertmanager가 준비 상태가 되는 것을 봤다. `start`는 Compose를 부르기 직전에 다시 확인하므로 이 경로에만 남는다. 사람이 이 위험을 받아들였다(사람 확인: 2026-11-09).
  - Blackbox Exporter는 지금처럼 root로 돈다(CWE-250). ping 확인에 raw socket이 필요하고, 이 변경 전과 같다.

## 테스트 계획

완료 기준마다 확인 방법은 다음과 같다. 자동 시험은 저장소 최상위에서 실행한다.

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | 빌드 검사의 이미지 파일 목록 검사와, `/bin/sh`를 넣은 시험용 이미지 | `node scripts/build-core-bundle.mjs --check` 통과, 시험용 이미지에서 실패 |
| C2 | 빌드 검사의 이미지 설정 검사와, 이 변경 전 이미지와의 `docker image inspect` 비교 | `--check` 통과, `User`·`WorkingDir`·`ExposedPorts`·label이 같음 |
| C3 | 인터넷을 끊은 `docker:dind`(`--network none`)에서 `install`, `start` | 세 프로그램 준비, flags API의 보관 값과 Blackbox `/config`의 `timeout`이 설정 값 |
| C4 | Debian 계열과 RHEL 계열 환경에서 `start` 뒤 HTTPS 확인과, 사내 인증 기관을 등록한 뒤 다시 `start` | 두 경우 모두 `probe_success 1`, Amazon Linux 2023의 패키지 이름 기록 |
| C5 | 가짜 루트에 CA 파일 없음·링크·빈 파일을 만든 운영 명령 시험과, 띄운 세 컨테이너의 mount 확인 | 없으면 여섯 위치와 `ca-certificates`를 안내하고 실패, CA mount는 `blackbox-exporter`에만 있음 |
| C6 | 이 변경 전 묶음과 이 변경의 묶음으로 갱신하고 되돌리는 수동 확인 | `minTime`과 silence가 같음 |
| C7 | 지금 빌드 검사의 거부 값 목록을 `render`와 `start`에 넣는 운영 명령 시험과, 검사를 지운 사본 | 모두 거부하고 `rendered/`와 컨테이너가 그대로, 사본에서 실패 |
| C8 | 가짜 `curl`·`wget`으로 준비·준비 안 됨·도구 없음과 바꾼 호스트 포트를 만드는 운영 명령 시험 | 정상이 아닌 컨테이너와 `docker logs` 명령이 보이고, `status`가 준비 여부를 보임 |
| C9 | `render`의 운영 명령 시험과, 묶음 폴더에서 `render` 뒤와 `rendered/core.env`를 지운 뒤의 `docker compose up -d` | 펼친 뒤에는 뜨고, 파일이 없으면 Compose가 아무것도 만들지 않음 |
| C10 | `rendered/prometheus.yml`을 고친 뒤 `render`하는 운영 명령 시험 | 템플릿에서 다시 만든 내용 |
| C11 | `node scripts/build-core-bundle.mjs --check`와, 범위 밖 값을 허용 범위에 넣은 사본 | 세 벌 통과, 사본에서 실패 |
| C12 | Windows 빌드 PC에서 `core-dev.bat start`, `stop` | 개발용 코어 준비 |
| C13 | `core-dev.bat build` 결과의 파일 목록과, 시험용 token을 둔 묶음 만들기 | 두 묶음에 `sources/`와 `busybox-LICENSE`가 없고 비밀값 검사 통과 |
| C14 | 2026-11-09와 같은 방법의 측정(`docker stats --no-stream`, 폴더와 이미지 크기) | 운영 가이드 사양 표의 새 값 |
| C15 | `node scripts/check-docs.mjs`와 하위 에이전트의 문서 리뷰 | 오류 0, 경고 0 |
| C16 | 인터넷을 끊은 `docker:dind`에서 node_exporter 묶음, 공식 이미지와의 `/metrics` 비교, 관리자 서버의 연결 시험 | 지표 이름이 같고 `nobody`로 돌며 연결 시험 통과 |

## 작업 순서

- [x] 1. [남길 결정](#남길-결정)의 질문에 사람의 답을 받아 이 계획과 ADR 0012에 반영한다.
- [ ] 2. 운영 명령 시험을 먼저 쓴다: `render`의 성공과 실패(S8, S12~S14, S16, S17), CA 찾기(C5), 준비 확인과 도구 없음(C8), 링크·권한·token 노출(보안 위협). 지금 `core-server.sh`에서 실패하는지 본다.
  - 2026-11-09에 계획과 ADR 0012를 승인한 뒤 멈췄다. 구현은 시작하지 않았고, 저장소의 코드와 설정은 PR #42의 상태 그대로다. 다음 세션은 이 작업부터 시작한다.
  - 시험 구조: `infrastructure/tests/core-server.test.sh`는 가짜 `docker`, `systemctl`을 `$T/bin`에 두고 `check`로 조건을 본다. 가짜 `docker compose up`은 지금 셸 환경 변수를 컨테이너 환경 변수로 기록하므로, `rendered/core.env`가 없으면 실패하고 있으면 그 내용을 `env.prometheus`, `env.blackbox-exporter`로 기록하게 바꾼다. 관리자 서버 주소를 보던 시험(`compose_address`)은 `rendered/`의 펼친 설정을 보게 바꾼다. 가짜 `curl`과 `wget`도 `$T/bin`에 두고, 준비 여부와 부른 주소를 `$FAKE`에 남긴다.
  - 시험이 두 도구가 없는 서버를 만들려면, 빌드 PC가 시험을 돌리는 BusyBox 이미지에 `wget`이 있으므로 PATH로는 숨길 수 없다. 그래서 `CORE_WAIT_SECONDS`처럼 시험에서 쓸 도구 목록을 바꾸는 환경 변수(예: `CORE_READY_TOOLS`, 기본 `curl wget`)를 두고, CA 파일 위치도 가짜 루트 아래에서 찾게 하는 변수(예: `CORE_CA_ROOT`)를 둔다.
  - `status` 시험의 `prometheus: healthy` 기대는 새 표시(상태와 준비 여부)로 바꾼다. 시험을 느슨하게 바꾸지 않고, 같은 결함을 잡는 새 조건으로 바꾼다.
- [ ] 3. `core-server.sh`에 펼치기 함수, `render` 작업, CA 찾기, 준비 확인을 넣고 `start`, `rotate-token`, `status`를 바꾼다. 2의 시험을 통과시킨다.
- [ ] 4. 빌드 검사에 이미지 파일 목록과 이미지 설정 검사를 먼저 더하고, 지금 이미지에서 실패하는지 본다.
- [ ] 5. 세 Dockerfile을 최소 이미지로 바꾸고 `render-config.sh`를 지운다. `compose.yml`, `.dockerignore`, `.gitignore`, `.env.example`을 고친다.
- [ ] 6. 빌드 검사를 공식 이미지의 도구로 옮기고, 거부 값 확인을 지운다(2에서 운영 명령 시험으로 옮겼다). `--check`를 통과시킨다.
- [ ] 7. node_exporter Dockerfile과 compose 파일을 바꾸고, 빌드 스크립트가 이미지를 빌드하게 한다. 저장소 compose도 최소 이미지를 빌드한다.
- [ ] 8. 빌드 스크립트에서 BusyBox 소스 싣기를 지우고, `infrastructure/licenses/`를 고친다.
- [ ] 9. `core-dev.bat start`를 고친다.
- [ ] 10. 수동 확인 C3, C4, C6, C9, C12, C14, C16을 한다.
- [ ] 11. 문서를 고친다: OPERATIONS.md, ARCHITECTURE.md, DEVELOPMENT.md, INTEGRATION.md, LICENSES.md. ADR 0012를 승인으로 바꾼다.
- [ ] 12. 구현한 대화를 이어받지 않은 하위 에이전트에 리뷰를 맡긴다(AGENTS.md의 자체 리뷰). 보안 위협의 CWE 목록을 함께 준다.
- [ ] 13. 요구사항 상태를 구현됨으로 바꾸고, 이 계획을 지운다. ADR 0012의 관련에 PR 링크를 쓴다.

## 진행 중 발견

2026-11-09에 빠른 확인을 했다. OS 임시 폴더에서 `scratch`에 실행 파일만 담은 이미지 네 개(`issue-minimal-*`)를 만들었다. 이 이미지들을 전용 네트워크와 volume, `127.0.0.1`의 39090·39093·39115·39100으로 띄웠다. 설정은 저장소 템플릿을 손으로 펼친 것이고, token은 시험용 임의 값이다. 확인한 것과 결과는 다음과 같다. 끝난 뒤 만든 컨테이너, 네트워크, volume, 이미지, 임시 파일을 모두 지웠고, 빌드 캐시만 남았다.

- 세 프로그램의 준비 확인 주소(`/-/ready`, `/-/ready`, `/-/healthy`)가 모두 200을 돌려줬다. 실행 사용자는 `65534:65534`, `65534:65534`, `0:0`이었다.
- Prometheus가 새 volume에 기록을 썼고(`headStats.numSeries` 57), 컨테이너 이름으로 Blackbox Exporter를 수집했다(`health: up`). `nsswitch.conf`가 없어도 Docker DNS로 이름을 풀었다.
- Blackbox Exporter의 ping 확인과 TCP 확인이 성공했다. CA 파일이 없으면 HTTPS 확인이 `x509: certificate signed by unknown authority`로 실패했다. 공식 이미지의 CA 묶음 파일을 `/etc/ssl/certs/ca-certificates.crt`에 읽기 전용으로 붙이자 `probe_success 1`이 나왔다.
- Alertmanager는 token 파일(`credentials_file`) 없이도 준비 상태가 됐고 시작 로그에 token 경고가 없었다. 그래서 token 확인은 띄우기 전에 운영 명령이 해야 한다.
- `docker exec … sh`는 `exec: "sh": executable file not found in $PATH`로 끝났다.
- 최소 node_exporter와 공식 node_exporter의 `/metrics`가 같은 지표 이름 306개를 냈다.
- 이미지 크기는 Prometheus 202MB(지금 372MB), Alertmanager 74.7MB(124MB), Blackbox Exporter 50.1MB(57MB), node_exporter 36.2MB였다. 세 코어 이미지의 `docker save` 파일은 약 100MB, node_exporter는 약 12MB였다. 규칙은 넣었지만 label은 넣지 않은 이미지라 실제 값은 구현한 뒤 다시 잰다.
- Docker Desktop(Compose v5.1.0)에서 없는 파일을 bind mount(`create_host_path: false`)하거나 `configs.file`로 붙이면, Docker가 그 자리에 빈 폴더를 만들고 Blackbox가 `is a directory`로 멈췄다. 반면 `env_file`에 `required: true`로 없는 파일을 두면, Compose가 컨테이너를 만들기 전에 `env file … not found`로 멈췄다. Linux Docker Engine에서는 확인하지 않았다.
- `env_file`에 `required: true`로 없는 파일을 두면 `docker compose config`도 실패하지만, `docker compose build`는 성공한다(2026-11-09, Docker Desktop). 그래서 빌드 스크립트의 포트 바인딩 검사(`checkPortBindings`, `validateRuntimeCompose`)는 compose 파일을 임시 폴더에 복사하고 빈 `rendered/core.env`를 둔 뒤 `config`를 실행해야 한다. 이미지 빌드는 바꾸지 않아도 된다.
- 저장소에서 펼치기를 실행하면 `core-server.sh`가 묶음이 아닌 저장소 배치를 다뤄야 한다. 템플릿은 `infrastructure/<구성 요소>/`에 있고, token 폴더의 기본값은 `./secrets`(묶음은 `../secrets`)다. 그래서 `templates/`가 없고 `prometheus/prometheus.yml`이 있으면 저장소 배치로 보도록 계획한다. 개발용 띄우기(`core-dev.bat start`)가 쓰는 공식 Prometheus 이미지의 digest는 Dockerfile의 `FROM`과 같아야 하므로, 빌드 검사가 두 값이 같은지 본다.

## 남길 결정

ADR 0012에 남긴 결정은 다음과 같다.

- 코어 설정은 운영 명령이 컨테이너 밖에서 검사해 펼치고, 코어 이미지에는 실행 파일과 규칙만 둔다.
- 템플릿은 묶음 폴더에 두고, 이미지와 같은 커밋에서 만든다.
- 정상 확인은 운영 명령이 코어 서버에서 준비 확인 주소를 불러 한다. `curl`과 `wget`이 모두 없으면 컨테이너가 실행 중이고 다시 시작이 늘지 않는지로 대신 보고 경고한다.
- CA 파일은 `render`가 `rendered/`로 복사하고, 그 복사본을 Blackbox Exporter에만 붙인다.

사람이 2026-11-09에 다음을 정했다. 이 계획의 해당 절에 반영했다.

| 항목 | 결정 |
|---|---|
| `curl`·`wget`이 모두 없는 서버 | 실행 상태로 대신 보고 경고하되, 띄우기는 막지 않는다 |
| 펼치기 작업 이름 | `render` |
| CA 파일 붙이기 | 찾은 파일을 `rendered/`로 복사하고 띄울 때마다 다시 복사한다 |
| 저장소의 node_exporter `compose.yml` | 최소 이미지를 빌드해 쓴다 |
| CWE-367(`render` 뒤 token을 지우고 직접 띄움) | 받아들인다 |
