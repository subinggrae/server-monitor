# 0020. 모니터링 코어를 모니터링 시스템으로 바꿈(이름 포함) 구현 계획

- 요구사항: [0020. 모니터링 코어를 모니터링 시스템으로 바꿈(이름 포함)](0020-rename-monitoring-system.md)
- 상태: 승인

## 접근 방법

파일 이름은 `git mv`로 바꿔 기록을 잇는다. 이름과 글은 한 번에 기계적으로 바꾸지 않고 종류별로 나눠 바꾼다. "코어"는 문장마다 조사(코어가/코어를 → 모니터링 시스템이/을)와 뜻(제품, 서버, 묶음)이 달라 일괄 치환하면 틀린 문장이 생기기 때문이다.

1. 이름(파일, 변수, 이미지, 폴더, label, 경로): 정해진 대응표대로 치환한다. 제품을 뜻하지 않는 `core`(아래 예외)는 건드리지 않는다.
2. 글: "코어 서버"는 "모니터링 서버"로, 제품을 뜻하는 "코어"는 "모니터링 시스템"으로 바꾼다. 조사를 맞추고, 같은 말이 한 문장에 거듭 나와 길어지면 문장을 다듬는다. 운영자에게 보이는 글은 [PRODUCT.md의 말투](../PRODUCT.md#말투)를 따르고 원문보다 길게 늘리지 않는다.

옛 이름을 찾거나 옮기는 코드는 넣지 않는다(요구사항의 하지 않는 것).

검토했지만 고르지 않은 방법은 다음과 같다.

- 운영 명령에서 옛 변수 이름을 함께 읽음: 정식 운영 서버가 없어 옮길 대상이 없다(사람 확인: 2026-11-16).
- compose 프로젝트 이름만 `monitoring-core`로 둠: 이미 설치한 서버의 볼륨을 이어 쓸 때만 필요하다. 지킬 기록이 없어 모두 바꾼다(사람 확인: 2026-11-16).

## 바뀌는 부분

이름 대응표는 다음과 같다. 요구사항의 표에 있는 이름은 그대로 따른다.

| 지금 | 바뀐 뒤 |
|---|---|
| `infrastructure/core-server.sh`, `core-settings.sh` | `infrastructure/monitoring-server.sh`, `monitoring-settings.sh` |
| `infrastructure/tests/core-server.test.sh` | `infrastructure/tests/monitoring-server.test.sh` |
| `core-dev.bat`, 그 안의 `CORE_DEV_*` | `monitoring-dev.bat`, `MONITORING_DEV_*` |
| `scripts/build-core-bundle.mjs`, 그 안의 상수 `CORE_*` | `scripts/build-monitoring-bundle.mjs`, 뜻에 맞는 이름(예: `CORE_DIR` → `INFRA_DIR`, `CORE_IMAGES` → `SYSTEM_IMAGES`) |
| 설정 변수 `CORE_*` 7개, 시험용 `CORE_WAIT_SECONDS`, `CORE_WAIT_INTERVAL`, `CORE_READY_TOOLS` | `MONITORING_*` |
| 펼친 설정 `rendered/core.env`, 그 안의 `CORE_CA_SHA256`, `CORE_CONFIG_SHA256` | `rendered/monitoring.env`, `MONITORING_CA_SHA256`, `MONITORING_CONFIG_SHA256` |
| 템플릿 자리 `@CORE_PROBE_TIMEOUT_SECONDS@` 등 | `@MONITORING_PROBE_TIMEOUT_SECONDS@` 등 |
| 빌드 인자 `CORE_VERSION`, `CORE_REVISION`, `CORE_COMMIT_TIME`, compose의 `x-core-build-args` | `MONITORING_VERSION`, `MONITORING_REVISION`, `MONITORING_COMMIT_TIME`, `x-build-args` |
| 이미지 label `monitoring-core.commit-time`, `org.opencontainers.image.title`의 `monitoring-core-*` | `monitoring-system.commit-time`, `monitoring-system-*` |
| 컨테이너 안 설정 경로 `/etc/core/` | `/etc/monitoring/` |
| 묶음의 이미지 파일 `core-images.tar` | `monitoring-system-images.tar` |
| 셸 함수와 변수 `core_*`, `cs_*`(값 검사 규칙), `core_exists`, `core_running` | `monitoring_*`, `ms_*`, `monitoring_exists`, `monitoring_running` |

바꾸지 않는 `core`: `scripts/check-docs.mjs`의 `--no-core`와 `CORE_DOCS`(기본 문서), 규칙 테스트의 windows_exporter `core` label, 지난 요구사항·ADR의 파일 이름과 본문.

| 위치 | 변경 |
|---|---|
| `infrastructure/compose.yml` | `name: monitoring-system`, 이미지 이름, 빌드 인자, 펼친 설정 경로, 컨테이너 안 경로, 변수 이름, 주석 |
| `infrastructure/.env.example` | 변수 이름과 주석 |
| `infrastructure/*/Dockerfile`, `blackbox/blackbox.yml`, `prometheus/prometheus.yml`, `alertmanager/alertmanager.yml`, `node-exporter/compose.yml`, `node-exporter/Dockerfile`, `.dockerignore`, `licenses/README.txt` | label, 빌드 인자, 경로, 템플릿 자리, 주석 |
| `infrastructure/monitoring-server.sh`, `monitoring-settings.sh` | 이름과 안내·오류 문구(동작은 그대로) |
| `infrastructure/tests/monitoring-server.test.sh` | 새 이름과 새 문구로 기대값을 고치고, 옛 변수 이름만 적은 `.env`의 값을 쓰지 않는지 보는 시험을 더한다(C2) |
| `monitoring-dev.bat`, `scripts/build-monitoring-bundle.mjs` | 이름, 묶음 폴더와 이미지 파일 이름, 안내 문구 |
| `.gitignore` | 주석과 경로 |
| `AGENTS.md` | 자주 쓰는 명령과 프로젝트 고유 규칙의 이름(지침 파일이라 보고에 따로 적음) |
| `README.md`, `docs/ARCHITECTURE.md`(그림 포함), `OPERATIONS.md`, `INTEGRATION.md`, `ADMIN-SERVER.md`, `DEVELOPMENT.md`, `TESTING.md`, `PRODUCT.md`, `LICENSES.md`, `WRITING.md`, `docs/reference-schema/*/*.sql` 주석 | 이름과 글 |
| `docs/TERMS.md` | 아래 TERMS.md 변경 |

TERMS.md 변경은 다음과 같다.

- "모니터링 시스템" 행을 더한다. 쓰지 않는 말은 모니터링 코어, 코어(제품의 뜻)이고, 근거에 이름 규칙(명령·변수는 `monitoring`, 제품 산출물은 `monitoring-system`)을 쓴다.
- "모니터링 서버" 행을 더한다. 쓰지 않는 말은 코어 서버다.
- "받는 그룹" 행을 "알림 받을 곳"으로 바꾸고 "설정 안 됨", "토픽 ID" 행을 지운다.
- "관리자 서버" 행의 뜻 설명에서 코어를 모니터링 시스템으로 고친다.

### 데이터 모델 변경

해당 없음

### 계약 변경

해당 없음. HTTP SD와 webhook 계약, 컨테이너 이름, 네트워크 이름은 그대로다. INTEGRATION.md와 ADMIN-SERVER.md는 글과 변수 이름(`MONITORING_SECRETS_DIR`)만 바뀐다. 관리자 서버는 모니터링 시스템의 `.env`를 읽지 않으므로 고칠 것이 없다.

## 위험과 되돌리기

- 이름을 빠뜨려 옛 이름과 새 이름이 섞임(예: compose 파일은 새 변수, 운영 명령은 옛 변수): `monitoring-dev.bat check`의 설정 검사와 운영 명령 시험, 빌드 스크립트의 값 목록 대조가 잡는다. 끝에 `git grep`으로 C1을 확인한다.
- 일괄 치환이 제품이 아닌 core나 지난 기록을 바꿈: 치환 범위에서 `docs/requirements/`, `docs/decisions/`, 예외 파일을 빼고, diff를 읽어 확인한다.
- 글을 바꾸며 문장이 어색해지거나 길어짐: 바꾼 문장을 diff로 읽는다. 문서 검사가 쓰지 않는 말("코어 서버", "모니터링 코어")을 경고한다.
- 개발 PC에 옛 이름으로 띄운 개발용 컨테이너가 남음: 이 PC는 작업 전에 옛 `core-dev.bat stop`으로 내린다. 다른 곳은 사람이 지운다(보고에 명령을 적음).
- 되돌리기: 커밋을 되돌리고 묶음을 다시 만든다. 새 이름으로 띄운 곳은 컨테이너와 볼륨을 지운 뒤 이전 묶음으로 띄운다.

### 보안 위협

해당 없음. 외부 입력(`.env`, 셸 환경 변수)을 읽고 검사하는 방법, token 위치와 권한, 포트 바인딩은 바뀌지 않고 이름만 바뀐다. 안전 검사(링크 거부 CWE-59, `.env`를 실행하지 않음)는 운영 명령 시험이 지금처럼 확인한다.

## 테스트 계획

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | 검색 | `git grep -n 코어 -- . ':!docs/requirements' ':!docs/decisions'`, `git grep -n -i core -- . ':!docs/requirements' ':!docs/decisions'`, `git ls-files \| grep -i core`가 예외(`--no-core`, `CORE_DOCS`, windows `core` label, TERMS의 쓰지 않는 말, 지난 요구사항·ADR 링크)만 보임 |
| C2 | 운영 명령 시험 | `monitoring-dev.bat check`의 운영 명령 시험 통과(옛 변수 이름 시험은 구현 전에 실패하는 것을 먼저 봄) |
| C3 | 수동 확인 | 아래 C3 절차대로 하면 compose 프로젝트 `monitoring-system`, 새 이미지 이름으로 뜨고 기록과 장애가 이어짐 |
| C4 | 실행 | `monitoring-dev.bat check` 통과, `monitoring-dev.bat start`에서 `http://127.0.0.1:9090/-/ready`가 200, `stop` 뒤 컨테이너 없음 |
| C5 | 문서 검사 | `node scripts/check-docs.mjs --render` 오류 0. 그림은 PNG로 직접 확인 |

C3 절차는 다음과 같다.

1. `docker:dind` 컨테이너를 Linux 모니터링 서버로 쓴다.
2. 서로 다른 커밋으로 만든 묶음 두 개를 그 안의 설치 폴더에 옮긴다.
3. 첫 묶음으로 `install`, `start`를 실행하고, 가짜 관리자 서버로 감시 대상과 장애를 만든다.
4. 둘째 묶음으로 `start`를 실행하고 기록, 장애, 설정 값이 이어지는지 본다.

## 작업 순서

- [x] 옛 `core-dev.bat stop`으로 개발용 컨테이너를 내린다(이미 내려가 있었다)
- [x] 옛 변수 이름 시험을 더하고 지금 운영 명령에서 실패하는지 확인한다
- [x] 파일 이름을 바꾸고 이름 대응표대로 코드와 설정을 고친다. 운영 명령과 개발 명령의 안내 문구를 고친다. `monitoring-dev.bat check`로 확인한다
- [x] 문서, TERMS.md, AGENTS.md, 그림을 고친다. `node scripts/check-docs.mjs --render`로 확인한다
- [ ] C1 검색, C4 실행, C3 수동 확인을 하고 진행 중 발견에 적는다
  - C4는 끝났다. 다음은 C1 검색과 C3 수동 확인이다
- [ ] 전체 검증을 실행하고 새 하위 에이전트에게 리뷰를 맡긴다
- [ ] 요구사항을 구현됨으로 바꾸고 이 계획을 지운다

## 진행 중 발견

- compose 프로젝트 이름만 바꾸고 볼륨을 남기면, Docker가 띄울 때마다 다른 프로젝트가 만든 볼륨이라고 경고하고, 새 프로젝트의 `docker compose down -v`가 그 볼륨을 지우지 않는다(2026-11-16, 시험용 compose 프로젝트로 확인). 그래서 이전 버전을 띄운 곳은 컨테이너와 볼륨을 함께 지워야 한다.
- 운영 명령의 `previous_env`는 요구사항 0017 전의 묶음 폴더(묶음 폴더에 `.env`를 두던 방식)를 찾는다. 그 폴더는 옛 이름 `monitoring-core-<버전>`이라 찾는 이름을 그대로 두었다. 동작을 바꾸지 않기 위해서다. 그 시험과 운영 가이드의 해당 안내도 옛 이름을 쓴다. C1의 예외다.
- 지난 요구사항 0013이 이름이 바뀐 INTEGRATION.md 제목(`#코어가-제공할-것`)으로 링크해 문서 검사가 실패했다. 본문은 그대로 두고 링크 주소만 새 제목으로 고쳤다.
- 일괄 치환이 `prometheus.yml` 주석의 "CPU 코어"(제품이 아님)도 바꿔 되돌렸다. 예외 목록에 적은 대로 diff를 읽어 찾았다.

## 남길 결정

없음. 이름 규칙은 TERMS.md의 "모니터링 시스템" 행에 남긴다. 커밋을 되돌리면 이전 이름으로 돌아간다.
