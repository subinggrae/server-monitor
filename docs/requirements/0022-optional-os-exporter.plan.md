# 0022. 서버 사용량 수집(OS exporter)을 .env로 끔 구현 계획

- 요구사항: [0022. 서버 사용량 수집(OS exporter)을 .env로 끔](0022-optional-os-exporter.md)
- 상태: 진행 중

## 접근 방법

Prometheus 설정 템플릿의 `manager-os` 수집 블록 앞뒤에 ASCII 표시 줄을 둔다. 운영 명령이 펼칠 때 값이 `off`면 두 표시 줄 사이를 뺀다. 이 값은 설정 파일에 글자로 펼치지 않고 블록을 넣을지 뺄지만 정한다. 그래서 값이 YAML에 섞일 수 없다.

값 검사는 기존 숫자 값과 나눈다. `monitoring-settings.sh`에 켜고 끄는 값의 목록과 검사 함수를 따로 둔다. 기존 숫자 값의 목록(`MONITORING_SETTING_NAMES`)과 빌드 PC가 읽는 허용 범위 출력은 바꾸지 않는다. 오류 안내는 숫자 값과 같은 형식으로 쓴다. 형식은 이름, 적힌 값, 문제, 허용 값, 고치는 방법이다.

검토했지만 고르지 않은 방법은 다음과 같다.

- 숫자 값처럼 `1`/`0`으로 받기: 기존 검사를 그대로 쓸 수 있지만, 운영자가 읽기 어렵다(사람 확인: 2026-11-16).
- `off`일 때 HTTP SD 주소만 빼고 수집 블록은 남기기: 대상 없는 수집 블록이 남아 Prometheus 화면과 설정이 헷갈린다. 블록을 통째로 뺀다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/prometheus/prometheus.yml` | `manager-os` 블록 앞뒤에 표시 줄(`# BEGIN manager-os`, `# END manager-os`)과 그 뜻의 주석을 더한다 |
| `infrastructure/monitoring-settings.sh` | 켜고 끄는 값 `MONITORING_OS_EXPORTER`의 목록, 뜻, 검사 함수를 더한다 |
| `infrastructure/monitoring-server.sh` | 아래 운영 명령 변경 |
| `infrastructure/tests/monitoring-server.test.sh` | 아래 운영 명령 시험 |
| `monitoring-dev.bat` | `start`가 셸 환경 변수 `MONITORING_OS_EXPORTER`도 넘긴다 |
| `scripts/build-monitoring-bundle.mjs` | 펼친 설정 세 벌에 이 값을 비움, `off`, `on`으로 넣고 펼친 `prometheus.yml`과 `monitoring.env`가 값에 맞는지 본다 |
| `infrastructure/.env.example`, `docs/OPERATIONS.md`, `docs/INTEGRATION.md`, `docs/ADMIN-SERVER.md`, `docs/ARCHITECTURE.md`, `docs/DEVELOPMENT.md` | 이 값의 설명(아키텍처는 서버에서 바꾸는 값 목록에 더함) |

운영 명령 변경은 다음과 같다.

- `check_settings`가 켜고 끄는 값도 검사한다. 틀린 값은 숫자 값과 함께 한 번에 모두 보인다. 셸 환경 변수가 `.env`보다 먼저 쓰인다.
- `write_rendered`는 값이 `off`면 템플릿에서 두 표시 줄 사이를 빼고 펼친다. 템플릿에 표시 줄이 정확히 한 쌍 있는지 먼저 보고, 없으면 묶음을 다시 옮기라고 안내하고 멈춘다.
- `monitoring.env`에 `MONITORING_OS_EXPORTER`를 더한다. 값이 바뀌면 펼친 설정의 해시도 바뀌어 컨테이너를 다시 만든다.
- `show_settings`는 prometheus 컨테이너가 받은 값이 `off`면 "서버 사용량 수집 꺼짐"을 기본값과 다른 설정 값에 보인다.

운영 명령 시험은 다음을 본다.

- 값이 없거나 `on`이면 펼친 설정에 수집 블록이 있다.
- `off`면 수집 블록과 서버 감시 대상 목록 API 주소가 없다.
- 틀린 값 `yes`, `false`, `0`, `ON`, `o n`은 거부하고 펼치거나 띄우지 않는다.
- 셸 환경 변수가 `.env`보다 먼저 쓰인다.
- `status`가 꺼짐을 보인다.
- 값을 바꾸면 컨테이너를 다시 만든다.

### 데이터 모델 변경

해당 없음

### 계약 변경

서버 감시 대상 목록 API(`GET /api/prometheus/sd/os-exporter`)는 운영자가 서버 사용량 수집을 켰을 때만 불린다. 경로와 형식은 그대로다. 통합 계약에 이 사실과, 이 API를 만들지 않는 관리자 서버라면 운영자가 끈다는 안내를 더한다.

## 위험과 되돌리기

- 표시 줄 사이를 빼다 YAML이 깨짐: `off`로 펼친 설정을 빌드 PC의 설정 검사에서 promtool로 확인한다.
- 템플릿을 고치다 표시 줄이 사라지거나 둘이 됨: 운영 명령이 표시 줄 수를 확인하고 멈춘다. 빌드 PC의 설정 검사도 `off`로 펼쳐 본다.
- 되돌리기: 커밋을 되돌린다. `.env`에 남은 `MONITORING_OS_EXPORTER`는 이전 버전이 읽지 않으므로 그대로 둬도 된다(이전 버전은 늘 수집).

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 없음. 운영자가 `.env`로 바꾸는 값이 하나 늘어난다.
- 잘못될 수 있는 것과 막는 방법: 값에 YAML이나 셸 명령을 넣는 것(CWE-20, CWE-78) → `on`, `off`만 받고, 값을 설정 파일에 글자로 펼치지 않는다. `.env`는 지금처럼 셸로 실행하지 않고 읽는다. 틀린 값 거부 시험을 둔다.
- 받아들이는 위험: 없음

## 테스트 계획

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | 운영 명령 시험, 빌드 PC의 설정 검사 | `monitoring-dev.bat check` 통과(`off`로 펼친 설정이 promtool을 통과하고 수집 블록이 없음) |
| C2 | 운영 명령 시험 | 틀린 값과 셸 환경 변수 시험 통과(구현 전에는 실패) |
| C3 | 운영 명령 시험 | `status` 시험 통과(구현 전에는 실패) |
| C4 | 수동 확인 | 서버 감시 대상 목록 API에 `404`를 돌려주는 가짜 관리자 서버로 `on`과 `off`를 띄워 Prometheus 로그를 비교 |
| C5 | 문서 검사, 리뷰 | `node scripts/check-docs.mjs` 오류 0 |
| C6 | 모니터링 시스템 검사 | `monitoring-dev.bat check` 통과 |

## 작업 순서

- [x] 운영 명령 시험을 더하고 지금 운영 명령에서 실패하는지 확인한다
- [x] 템플릿, 값 검사, 운영 명령, 개발 명령을 고치고 운영 명령 시험으로 확인한다
- [x] 빌드 PC의 설정 검사를 고치고 `monitoring-dev.bat check`로 확인한다
- [x] 문서를 고친다
- [x] C4를 수동 확인하고 진행 중 발견에 적는다
- [ ] 전체 검증을 실행하고 새 하위 에이전트에게 리뷰를 맡긴다
- [ ] 요구사항을 구현됨으로 바꾸고 이 계획을 지운다

## 진행 중 발견

- 새 운영 명령 시험 15개 가운데 13개가 구현 전에 실패했다(통과 175, 실패 13). 통과한 둘은 값이 없거나 `on`인 지금 동작이다. 구현 뒤 통과 188, 실패 0이었다(`c6f9061`).
- 작업 사본의 `prometheus.yml`은 줄 끝이 CRLF다. 운영 명령은 지금처럼 CR을 지운 뒤 표시 줄을 찾으므로 영향이 없다.
- C4(`c6f9061`): 서버 감시 대상 목록 API에 `404`를 돌려주는 가짜 관리자 서버(`manager-api`)로 개발용 모니터링 시스템을 띄웠다. 값이 없으면(on) Prometheus 로그에 `Unable to refresh target groups … config=manager-os err="server returned HTTP status 404 Not Found"`가 30초마다 남았다(70초에 3건). 셸 환경 변수 `MONITORING_OS_EXPORTER=off`로 다시 띄우자 컨테이너를 다시 만들었고, 70초 동안 오류가 0건이었다. 펼친 설정에 `manager-os`가 없고, 주소 감시 대상은 두 경우 모두 `up`이었다.

## 남길 결정

없음. 서버에서 바꾸는 값 목록은 아키텍처의 지켜야 할 경계에 더한다.
