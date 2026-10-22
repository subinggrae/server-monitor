# 0010. 내부망에 옮겨 실행하는 코어 이미지 묶음 구현 계획

- 요구사항: [0010. 내부망에 옮겨 실행하는 코어 이미지 묶음](0010-core-image-bundle.md)
- 상태: 진행 중

## 접근 방법

코어 구성 요소 세 개마다 Dockerfile을 두어, 공개 이미지에 설정 템플릿, Alert 규칙, `render-config.sh`를 넣은 이미지를 만든다. 저장소의 `infrastructure/compose.yml`은 이 Dockerfile로 이미지를 빌드해 띄우고, 설정 volume 마운트를 뺀다. 결정과 이유는 [ADR 0009](../decisions/0009-core-config-in-image.md)에 있다.

빌드 스크립트 `scripts/build-core-bundle.mjs` 하나가 이미지 빌드, 설정 검사, 내보내기, 체크섬, 라이선스 파일 모으기, node_exporter 묶음 만들기를 차례로 한다. Node로 쓰는 이유는 저장소가 이미 `scripts/check-docs.mjs`를 Node로 돌리고, 개발 PC가 Windows여도 Git Bash 없이 돌기 때문이다.

내부망 서버에서 쓰는 compose 파일은 저장소의 `infrastructure/compose.yml` 하나를 그대로 쓰는 것을 먼저 시도한다. `image`와 `build`를 함께 두고 `pull_policy`를 환경 변수로 바꾸면, 내부망에서는 빌드하지 않고 불러온 이미지만 쓴다. 빌드 정의(Dockerfile)가 없는 폴더에서 Compose가 이 파일을 거부하면, `build` 항목만 뺀 실행용 compose 파일을 빌드 스크립트가 만들어 묶음에 넣는다. 어느 쪽이든 서비스 정의는 저장소의 파일 한곳에서 나온다.

검토했지만 고르지 않은 방법은 다음과 같다.

- **셸 스크립트**: Linux에서는 간단하다. 하지만 Windows 개발 PC에서는 Git Bash가 있어야 하고, 체크섬과 다운로드 검증 명령이 OS마다 다르다.
- **실행용 compose 파일을 손으로 따로 관리**: 단순하다. 하지만 개발용과 내부망용 서비스 정의가 두 곳에 생겨 어긋난다.

## 바뀌는 부분

바뀌는 파일과 내용은 다음과 같다.

| 위치 | 변경 |
|---|---|
| `infrastructure/prometheus/Dockerfile` | Prometheus 이미지에 설정 템플릿, 규칙, `render-config.sh`를 넣음(새 파일) |
| `infrastructure/alertmanager/Dockerfile` | Alertmanager 이미지에 설정 템플릿과 `render-config.sh`를 넣음(새 파일) |
| `infrastructure/blackbox/Dockerfile` | Blackbox 이미지에 `blackbox.yml`을 넣음(새 파일) |
| `infrastructure/.dockerignore` | `secrets/`, `.env`, `grafana/`, `node-exporter/`를 빌드 context에서 뺌(새 파일) |
| `infrastructure/compose.yml` | 설정 volume 마운트를 빼고 `build`, `image`, `pull_policy`를 더함(데이터 volume, 네트워크, 포트, 비밀값은 그대로) |
| `infrastructure/node-exporter/node_exporter.service` | 지금 운영 가이드에만 있는 systemd unit을 파일로 둠(새 파일) |
| `scripts/build-core-bundle.mjs` | 빌드부터 묶음까지(새 파일) |
| `.gitignore` | 묶음 출력 폴더 `dist/` 추가 |
| `docs/OPERATIONS.md` | 내부망 설치 절, 코어 실행 명령을 `--build`로 |
| `docs/DEVELOPMENT.md` | 코어 실행, 규칙 테스트, 코어 설정 검사 명령을 빌드한 이미지 기준으로 |
| `docs/ARCHITECTURE.md` | 실행 단위와 코드 지도에 코어 이미지와 빌드 스크립트 |
| `docs/LICENSES.md` | 묶음에 싣는 라이선스 파일의 출처 |

### 데이터 모델 변경

해당 없음.

### 계약 변경

해당 없음. 코어와 앱의 통합 계약, 포트, 네트워크 이름, 데이터 volume 이름은 바뀌지 않는다.

묶음의 구성은 다음과 같다. `<버전>`은 `git describe --tags --always --dirty`의 결과이고, 이미지 태그와 OCI label `org.opencontainers.image.revision`에도 같은 값과 커밋을 넣는다.

```text
dist/monitoring-core-<버전>/
  core-images.tar                 이미지 세 개(docker save)
  compose.yml                     실행용 compose 파일
  .env.example                    앱 주소 설정 예시
  secrets/alertmanager_webhook_token.example
  licenses/                       세 구성 요소의 LICENSE, NOTICE
  SHA256SUMS
dist/node-exporter-<버전>/
  node-exporter-image.tar         quay.io/prometheus/node-exporter:v1.12.1(docker save)
  compose.yml                     infrastructure/node-exporter/compose.yml
  node_exporter-1.12.1.linux-amd64.tar.gz
  node_exporter.service
  SHA256SUMS
```

## 위험과 되돌리기

- 빌드 정의 없이 Compose가 compose 파일을 거부함: 첫 작업에서 확인하고, 거부하면 실행용 compose 파일을 만드는 방법으로 바꾼다. 진행 중 발견에 쓴다.
- 이미지 안의 `/tmp`, `/prometheus` 권한이 바뀌어 비루트 사용자가 쓰지 못함: 공개 이미지의 `USER`와 경로를 그대로 둔다. 빌드한 이미지로 띄워 세 컨테이너가 `healthy`인지 본다.
- 기존 개발 환경의 컨테이너와 volume: 컨테이너 이름과 volume 이름이 같아 `up -d --build`로 바뀐다. 기록은 이어진다.
- 되돌리기: 이 변경을 revert하면 volume 마운트 방식으로 돌아간다. 데이터 volume은 그대로라 이전 기록이 남는다. 내부망 서버는 이전 묶음을 다시 불러와 띄운다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 빌드 PC가 인터넷에서 이미지와 node_exporter 실행 파일을 받아 묶음을 만들고, 사람이 그 묶음을 내부망 서버로 옮긴다.
- 잘못될 수 있는 것과 막는 방법:
  - 받은 실행 파일이 바뀌었음(CWE-494): node_exporter 실행 파일은 공식 릴리스의 `sha256sums.txt`와 대조하고, 맞지 않으면 빌드를 멈춘다. 바탕 이미지는 태그와 함께 digest로 고정한다.
  - 비밀값이 이미지나 묶음에 들어감(CWE-538): `.dockerignore`로 `secrets/`와 `.env`를 빌드 context에서 빼고, 빌드 스크립트가 묶음 안에 `*.example`이 아닌 비밀값 파일이 없는지 확인한다.
  - 옮기는 중에 묶음이 망가짐(CWE-353): `SHA256SUMS`를 함께 만들고, 운영 가이드에 불러오기 전 확인 명령을 쓴다.
- 받아들이는 위험: 체크섬은 망가짐을 잡지만, 묶음과 `SHA256SUMS`를 함께 바꿔치기하는 공격은 막지 못한다. 서명은 이번 범위가 아니다(사람 승인: 2026-10-22).

## 테스트 계획

완료 기준마다 확인할 방법은 다음과 같다. 대부분 Docker와 서버 동작이라 수동 확인이다.

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | 빌드 스크립트 실행과 묶음 검사 | `node scripts/build-core-bundle.mjs` 성공, 위 구성의 파일이 생기고 비밀값 확인 단계 통과 |
| C2 | 깨끗한 Docker에서 묶음만으로 띄우기 | 로컬 코어 이미지를 지운 뒤 묶음 폴더만으로 `docker load`, `docker compose up -d`를 해 세 컨테이너 `healthy`, 앱을 붙여 기존 E2E 통과 |
| C3 | 앱 주소와 token 바꾸기 | 다른 주소로 바꿔 다시 띄운 뒤 Prometheus target 화면의 SD 주소가 바뀜 |
| C4 | 갱신과 되돌리기 | 규칙 주석만 바꾼 두 번째 버전을 불러와 띄운 뒤 기록과 Alert 유지, 첫 버전으로 되돌려도 뜸 |
| C5 | 예외 동작 | `.env`나 token 파일을 빼면 Compose가 이름을 대며 거부, 이미지를 지운 뒤 띄우면 받지 않고 멈춤, tar 끝을 잘라 `sha256sum -c SHA256SUMS`가 실패 |
| C6 | 빌드한 이미지로 설정 검사 | 빌드 스크립트의 `promtool check config`, `promtool test rules`, `amtool check-config` 단계가 `SUCCESS` |
| C7 | 문서 | `node scripts/check-docs.mjs` 오류 0, 하위 에이전트 문서 리뷰 |
| C8 | node_exporter 묶음 | Docker 방식은 개발 환경의 Linux 컨테이너에서, 실행 파일 방식은 Linux 컨테이너에서 설치해 `curl :9100/metrics` 응답 |

## 작업 순서

- [x] ADR 0009와 이 계획, 받아들이는 위험을 승인받는다(사람 확인: 2026-10-22).
- [x] 빌드 정의 없는 폴더에서 `build`가 든 compose 파일이 `pull_policy: never`로 뜨는지 확인한다.
- [x] Dockerfile 세 개와 `.dockerignore`를 쓰고 `infrastructure/compose.yml`을 바꾼다. 개발 환경에서 세 컨테이너가 `healthy`인지와 기존 E2E를 확인한다(C2 일부, C6).
- [x] 빌드 스크립트의 코어 묶음 부분을 쓴다(C1, C5의 체크섬).
- [x] node_exporter 묶음 부분을 쓴다(C8).
- [ ] 깨끗한 Docker에서 C2~C5를 수동 확인한다.
- [ ] 운영 가이드, 개발 가이드, 아키텍처, 라이선스 문서를 고친다(C7).
- [ ] 전체 검증을 실행하고, 새 세션의 하위 에이전트에게 리뷰를 맡긴다.
- [ ] 요구사항을 구현됨으로, ADR 관련 줄에 PR 링크를 쓰고 이 계획을 지운다.

## 진행 중 발견

- `build`와 `image`가 함께 있는 compose 파일은 이미지를 불러온 뒤에는 `pull_policy: never`로 빌드 정의 없이 뜬다. 하지만 이미지가 없으면 받지 않는 대신 빌드를 시도해 `unable to prepare context ... not found`로 멈춘다. 요구사항 S9의 오류와 다르고 원인을 알기 어렵다. 그래서 빌드 스크립트가 `build` 항목을 `pull_policy: never`로 바꾼 실행용 compose 파일을 만든다. 근거: 2026-10-22 Docker Compose v5.1.0에서 시험했다.
- 공개 이미지 가운데 Prometheus에만 `/LICENSE`와 `/NOTICE`가 있고 Alertmanager와 Blackbox Exporter에는 없다. 그래서 세 구성 요소의 같은 버전 태그에서 받은 파일을 `infrastructure/licenses/`에 두고 묶음에 싣는다.
- 개발 환경의 `prometheus-data`, `alertmanager-data` volume은 옛 프로젝트 이름(`monitoring`)으로 만들어져 Compose가 경고를 낸다. 이번 변경 전부터 있던 경고이고, 이름이 같아 기록은 이어진다.

## 남길 결정

- 코어 설정은 이미지에 넣고 개발과 배포가 같은 이미지를 쓴다: [ADR 0009](../decisions/0009-core-config-in-image.md)
