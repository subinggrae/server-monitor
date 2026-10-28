# 0011. 코어를 빌드하고 운영하는 명령 구현 계획

- 요구사항: [0011. 코어를 빌드하고 운영하는 명령](0011-core-ops-script.md)
- 상태: 검토 중

## 접근 방법

gradle wrapper처럼 쓰는 곳마다 스크립트 파일을 하나씩 두고, 하는 일은 동사 작업 이름으로 고른다(사람 확인: 2026-10-28).

| 파일 | 쓰는 곳 | 작업 |
|---|---|---|
| `core-dev.bat`(저장소 최상위) | Windows 빌드 PC | `build` 묶음 만들기, `check` 설정 검사, `start` 개발용 띄우기, `stop` 멈추기 |
| `infrastructure/core-server.sh` → 묶음 최상위 `core-server.sh` | Linux 코어 서버 | `install` 설치 준비, `start` 띄우기, `rotate-token` token 바꾸기, `status` 상태 보기 |

`core-dev.bat`은 이미 있는 명령을 부르기만 한다. `build`와 `check`는 `node scripts/build-core-bundle.mjs`(`check`는 `--check`)를, `start`와 `stop`은 `docker compose -f infrastructure/compose.yml`의 `up -d --build`와 `down`을 실행한다. 그래서 빌드 로직은 지금처럼 Node 스크립트 한곳에만 있다.

`core-server.sh`는 POSIX `sh`로 쓴다. 코어 서버에는 Node가 없고(요구사항 목표), bash가 없을 수도 있기 때문이다. 쓰는 외부 명령은 `docker`, `sha256sum`, `od`, `find`, `sed`, `mktemp`, `systemctl`(있을 때만)이고, 옵션은 GNU coreutils와 BusyBox가 함께 지원하는 것만 쓴다. 예를 들어 `sha256sum --quiet`는 BusyBox에 없어서, `sha256sum -c` 출력에서 `OK`가 아닌 줄을 골라 보인다.

`core-server.sh`의 주요 동작은 다음과 같다.

- **경로**: 스크립트가 있는 폴더가 묶음 폴더이고, 그 상위 폴더가 설치 폴더다. token 폴더는 `.env`의 `CORE_SECRETS_DIR`가 있으면 그 값, 없으면 `../secrets`다. 상대 경로는 Compose와 같이 묶음 폴더 기준으로 푼다.
- **`.env` 읽기**: `.env`를 셸로 실행(`.`, `source`)하지 않는다. `sed`로 `KEY=` 줄의 값만 읽는다.
- **`install`**: token 파일이 없으면 token 폴더를 0700으로 만들고, `od -An -tx1 -N32 /dev/urandom`으로 만든 64자 값을 같은 폴더의 임시 파일에 쓴 뒤 0644로 바꾸고 이름을 옮긴다. `.env`가 없으면 떠 있는 `prometheus` 컨테이너의 Compose label `com.docker.compose.project.working_dir`로 그 묶음 폴더를 찾는다. 그 폴더가 같은 설치 폴더 안에 있고 `.env`가 있으면 복사한다(S3). 아니면 관리자 서버 주소를 묻고 `.env.example`을 복사해 주소 줄만 바꾼다(S1, S13). 이미 있는 파일은 덮어쓰지 않는다(S12).
- **`start`**: Docker 실행 확인(S19) → 체크섬 확인(S8) → 사전 검사(S9, S10) → 세 이미지가 없으면 `docker load` → 버전 비교 → `docker compose up -d` → 정상 상태 대기(S11) → Docker 자동 시작 확인(S18) → 버전과 커밋 출력. 사전 검사는 Docker가 token 자리에 폴더를 만들기 전에 실행한다.
- **버전 비교**: 묶음의 버전은 실행용 compose 파일의 이미지 태그, 떠 있는 버전은 `prometheus` 컨테이너의 OCI label `org.opencontainers.image.version`과 `revision`에서 읽는다. 둘이 다르면 두 이미지의 `Created` 시각을 비교한다. 묶음 쪽이 더 오래됐으면 `[ -t 0 ]`일 때만 확인을 묻고, 터미널이 아니면 바꾸지 않고 멈춘다(S5). 버전 태그는 `git describe` 값이라 크기를 비교할 수 없어 빌드 시각을 쓴다.
- **정상 상태 대기**: 5초마다 세 컨테이너의 `State.Health.Status`를 읽어 3분까지 기다린다. 넘으면 `healthy`가 아닌 컨테이너와 `docker compose logs <서비스>`를 보인다.
- **Docker 자동 시작 확인**: `systemctl`이 있으면 `systemctl is-enabled docker.service`가 `enabled`인지 본다. 아니면 경고와 `sudo systemctl enable docker`를 보이고 계속한다. `docker.socket`만 켜져 있으면 서버가 켜질 때 Docker가 뜨지 않으므로 `docker.service`를 본다. `systemctl`이 없으면 확인하지 못했다고 알린다.
- **`rotate-token`**: 사전 검사 → 새 token을 임시 파일에 써서 이름을 옮김 → `docker compose up -d --force-recreate alertmanager` → Alertmanager 정상 대기 → token 파일의 실제 경로(`pwd -P`)와 관리자 서버도 다시 띄우라는 안내
- **`status`**: 떠 있는 버전, 커밋, 세 컨테이너의 상태와 health, 묶음 버전과 다르면 그 사실

묶음은 다음처럼 바뀐다. 빌드 스크립트가 실행용 compose 파일을 만들 때 token 폴더 기본값 `./secrets`를 `../secrets`로 바꾼다. 개발용 `infrastructure/compose.yml`은 그대로 `./secrets`(`infrastructure/secrets/`)를 쓴다.

검토했지만 고르지 않은 방법은 다음과 같다.

- **운영 명령을 Node로 쓰고 sh와 bat는 감싸기만 함**: 로직이 한곳에 모이지만 코어 서버에 Node가 있어야 한다. 요구사항 목표와 맞지 않는다.
- **동사마다 파일을 따로 둠**: 파일 이름만 보고 할 일을 알 수 있지만 파일이 여덟 개가 되고, 공통 검사를 여러 파일이 나눠 쓴다(사람 확인: 2026-10-28, 파일 하나와 동사 작업을 고름).
- **떠 있는 버전 폴더를 폴더 이름 순서로 고름**: 폴더 이름은 운영자가 바꿀 수 있다. Compose가 컨테이너에 남기는 작업 폴더 label이 실제로 띄운 폴더를 정확히 가리킨다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `core-dev.bat` | 빌드 PC용 명령(새 파일) |
| `infrastructure/core-server.sh` | 코어 서버용 운영 명령(새 파일) |
| `infrastructure/tests/core-server.test.sh` | 운영 명령의 예외 동작 자동 시험(새 파일) |
| `scripts/build-core-bundle.mjs` | 묶음에 운영 명령 추가, token 기본값 변경, `--check`에서 자동 시험 실행 |
| `infrastructure/.env.example` | `CORE_SECRETS_DIR` 기본값 설명 |
| `.gitattributes` | `*.bat text eol=crlf` 추가 |
| `infrastructure/compose.yml`, `compose.yml` | 머리 주석의 실행 안내 |
| `docs/OPERATIONS.md` | 설치 순서, 운영 작업, 문제 해결, 준비물 |
| `docs/DEVELOPMENT.md`, `README.md` | 코어 실행, 설정 검사, 묶음 명령 |
| `docs/TESTING.md` | 종류별 기준에 운영 명령 시험 행 |
| `docs/ARCHITECTURE.md` | 코드 지도에 두 스크립트 |

표에 다 적지 못한 내용은 다음과 같다.

- `core-dev.bat`: 저장소 최상위로 이동해 작업을 실행하고, 알 수 없는 작업이면 쓸 수 있는 작업을 보인다. 한글 출력을 위해 `chcp 65001`을 쓴다.
- `core-server.test.sh`: 가짜 `docker`, `systemctl`을 `PATH` 앞에 두어 상황을 만든다.
- `build-core-bundle.mjs`: 운영 명령을 LF로 넣는다. 실행용 compose 파일의 token 폴더 기본값을 `../secrets`로 바꾸고, 바꿀 줄이 없으면 빌드를 멈춘다. token은 `install`이 만들므로 묶음의 `secrets/alertmanager_webhook_token.example`을 뺀다. 머리 주석의 사용법을 `core-server.sh` 기준으로 바꾼다.
- `.env.example`: 비워 두면 저장소에서는 이 파일 옆 `secrets/`, 묶음에서는 설치 폴더의 `secrets/`를 쓴다고 적는다.
- `.gitattributes`: cmd는 LF만 있는 배치 파일의 레이블과 `goto`를 잘못 읽는다.
- compose 주석: 서비스 정의는 그대로 두고 실행 안내만 `core-dev.bat start` 기준으로 바꾼다.
- `OPERATIONS.md`: 운영 명령과 설치 폴더 기준으로 바꾸고, 직접 실행하는 절차는 따로 둔다. 코어 서버 준비물에 Docker 자동 시작을 더한다.

### 데이터 모델 변경

해당 없음.

### 계약 변경

해당 없음. 통합 계약, 포트, 네트워크 이름, 데이터 volume 이름은 바뀌지 않는다.

묶음의 구성은 다음처럼 바뀐다.

```text
monitoring-core-<버전>/                      monitoring-core-<버전>/
  core-images.tar                             core-images.tar
  compose.yml   (token: ./secrets)      →     compose.yml   (token: ../secrets)
  .env.example                                .env.example
  secrets/alertmanager_webhook_token.example  core-server.sh
  licenses/, sources/                         licenses/, sources/
  SHA256SUMS                                  SHA256SUMS
```

## 위험과 되돌리기

- 묶음 폴더 안의 `secrets/`에 token을 둔 기존 서버: 새 묶음은 기본값이 `../secrets`라 token을 찾지 못한다. `start`의 사전 검사가 이 경우를 알아보고 옮기라고 안내한다(S10). `.env`에 `CORE_SECRETS_DIR`를 적은 서버는 영향이 없다.
- 서버의 셸이나 도구 차이(dash, bash, BusyBox): POSIX 문법과 공통 옵션만 쓰고, 자동 시험은 BusyBox `sh`에서, 수동 확인은 Debian 계열 `sh`(dash)에서 실행해 두 쪽을 본다.
- Windows 콘솔의 한글 깨짐: `chcp 65001` 뒤에 출력한다. 깨지면 영문 메시지로 바꾸는 대신 진행 중 발견에 쓰고 사람에게 묻는다.
- `Created` 비교가 틀림(같은 커밋을 다시 빌드한 경우 등): 버전이 같으면 비교하지 않는다. 다른 버전을 같은 시각에 빌드하는 경우는 없다고 보고, 틀리게 물어도 확인 질문이 하나 더 나올 뿐이다.
- 되돌리기: 이 변경을 revert하면 스크립트가 빠지고 묶음의 token 기본값이 `./secrets`로 돌아간다. 직접 실행하는 절차는 그대로 남아 있다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 운영 명령이 코어 서버에서 token 파일을 만들고 바꾸며, 운영자가 넣은 주소와 `.env`, Docker가 돌려주는 컨테이너 label을 읽는다.
- 잘못될 수 있는 것과 막는 방법:
  - `.env`나 입력에 셸 명령을 넣음(CWE-78): `.env`를 실행하지 않고 값만 읽는다. 주소는 `^[A-Za-z0-9][A-Za-z0-9_.-]*:[0-9]{1,5}$`와 포트 1~65535만 받는다. 모든 변수를 따옴표로 감싼다.
  - 형식이 틀린 주소를 그대로 씀(CWE-20): 위 검사에서 거부한다(S9).
  - 다른 폴더의 `.env`를 가져옴(CWE-22): Docker label이 가리키는 폴더를 `pwd -P`로 풀어, 상위 폴더가 설치 폴더와 같을 때만 복사한다.
  - token이 화면이나 다른 사용자에게 노출됨(CWE-532, CWE-732): token 값을 출력하지 않는다. 폴더는 0700, 파일은 0644(컨테이너 사용자 `nobody`가 읽어야 함)로 만든다. 폴더가 닫혀 있어 다른 사용자는 파일에 닿지 못한다. 임시 파일은 같은 폴더에서 `umask 077`로 만든다.
  - 예측할 수 있는 token(CWE-330): `/dev/urandom`에서 32바이트를 읽는다.
- 받아들이는 위험: 없음

## 테스트 계획

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1, C6 | 빈 Linux Docker에 설치 | `install`, `start` 뒤 세 컨테이너 `healthy` |
| C2 | 갱신과 되돌리기 | 새 폴더 `start` 뒤 기록 유지, 이전 폴더는 확인 뒤에만 바뀜 |
| C3 | token 바꾸기 | 받은 `Authorization` 값이 새 token과 같음 |
| C4 | 상태 보기 | `status`가 버전, 커밋, health, 버전 차이를 보임 |
| C5 | 운영 명령 자동 시험 | `node scripts/build-core-bundle.mjs --check` 통과 |
| C7 | 묶음 구성 | `core-dev.bat build` 뒤 묶음 검사 통과 |
| C8 | 문서 | `node scripts/check-docs.mjs` 오류 0 |
| C9 | 빌드 PC 명령 | `cmd`에서 네 작업과 잘못된 작업 이름 확인 |
| C10 | 자동 재시작 | Docker를 다시 시작하면 코어가 `healthy`로 돌아옴 |

표에 다 적지 못한 확인 방법은 다음과 같다.

- C1, C6: `docker:dind` 컨테이너에 네트워크 없이 묶음만 넣고 `sh core-server.sh install`, `start`를 실행한다. token 폴더가 0700, 파일이 0644이고 출력에 token 값이 없는지 본다.
- C2: 주석만 다른 두 묶음을 나란히 둔다. 새 폴더에서 주소 입력 없이 `install`, `start`를 실행하고 기록이 이어지는지 본다. 이전 폴더 `start`에서 `n`이면 그대로, `y`면 이전 버전이 된다.
- C3: 시험용 관리자 서버가 받은 값과 `rotate-token` 뒤의 파일 값을 대조한다.
- C5: 빌드 스크립트가 BusyBox `sh`로 `infrastructure/tests/core-server.test.sh`를 실행한다. 경우마다 종료 코드, 안내 문구, 바뀌지 않은 파일, token 자리에 폴더가 없음을 확인한다. 각 시험은 스크립트의 검사를 잠시 지워 실패하는 것을 한 번 본다.
- C7: 묶음에 `core-server.sh`가 있고 `SHA256SUMS`에 들어 있으며, 비밀값 검사를 통과한다.
- C8: 하위 에이전트에게 문서 리뷰도 맡긴다.
- C9: Windows의 `cmd`에서 `build`, `check`, `start`, `stop`과 없는 작업 이름을 실행하고, 한글 출력이 깨지지 않는지 본다.
- C10: `docker:dind` 컨테이너를 다시 시작한다. `docker compose stop`으로 멈춘 뒤 다시 시작하면 뜨지 않는 것도 본다.

`docker:dind`는 시험에만 쓰는 이미지라 묶음과 제품에 들어가지 않는다. 태그와 digest로 고정해 쓴다.

## 작업 순서

- [ ] 이 계획을 승인받는다.
- [ ] 운영 명령 자동 시험을 쓰고, 스크립트 없이 실패하는 것을 본다(C5).
- [ ] `core-server.sh`의 경로, `.env` 읽기, 사전 검사, `install`을 쓴다(C5, S1, S3, S9, S10, S12, S13).
- [ ] `start`, `rotate-token`, `status`와 Docker 자동 시작 확인을 쓴다(S2, S4~S8, S11, S14, S18, S19).
- [ ] 빌드 스크립트와 `.env.example`, `.gitattributes`를 바꾼다(C7).
- [ ] `core-dev.bat`을 쓰고 Windows에서 확인한다(C9).
- [ ] `docker:dind`에서 C1~C4, C6, C10을 수동 확인한다.
- [ ] 운영 가이드, 개발 가이드, README, 테스트·아키텍처 문서와 compose 주석을 고친다(C8).
- [ ] 전체 검증을 실행하고, 새 세션의 하위 에이전트에게 리뷰를 맡긴다.
- [ ] 요구사항을 구현됨으로 바꾸고 이 계획을 지운다.

## 진행 중 발견

없음.

## 남길 결정

없음. 스크립트 구성과 token 위치는 요구사항과 운영 가이드에 남기고, 되돌리기 어려운 계약은 바뀌지 않는다.
