# 0012. 감시 항목 이름·환경을 바꿔도 이어지는 장애 구현 계획

- 요구사항: [0012. 감시 항목 이름·환경을 바꿔도 이어지는 장애](0012-check-label-continuity.md)
- 상태: 진행 중

## 접근 방법

요구사항에서 사람이 정한 방법을 그대로 쓴다. SD label은 그대로 두고, 장애를 만드는 두 Alert 규칙의 식에서 `check_name`, `environment`를 빼고, Alertmanager `group_by`에서 `environment`를 뺀다. 알림의 이름과 환경은 관리자 서버가 자기 DB 값으로 채우도록 [관리자 서버 만들기](../ADMIN-SERVER.md)와 [INTEGRATION.md](../INTEGRATION.md)에 적는다. 관리자 서버의 API와 웹은 다른 웹에서 만들므로 이 저장소의 Manager(`backend/`, `frontend/`)는 고치지 않는다(사람 확인: 2026-11-09).

```text
지금
  SD label(check_id, check_target_id, check_type, module, environment, check_name)
    → probe_success / up series(label 그대로)
    → Alert label = series label + alertname, severity   ← 이름·환경이 바뀌면 fingerprint가 바뀜
    → group_by [alertname, check_id, environment]        ← 환경이 바뀌면 묶음이 바뀜
    → Manager: 이름은 DB, 환경은 label을 먼저 씀

바꾼 뒤
  SD label(그대로)
    → probe_success / up series(그대로. 이름·환경이 바뀌면 새 series로 시작하는 것도 그대로)
    → max without (check_name, environment) (지금 값) == 0
      or max without (check_name, environment) (최근 5분 안의 마지막 값, last_over_time) == 0
    → Alert label = check_id, check_target_id, check_type, module, instance, job, alertname, severity
    → group_by [alertname, check_id]
    → Manager: 이름과 환경 모두 DB(환경 label이 없으면 지금 코드도 DB 값을 쓴다)
```

`max without (check_name, environment)`로 고른 이유는 다음과 같다.

- 두 label을 빼면 이름·환경을 바꾼 앞뒤의 series가 같은 label 집합이 되어, Alert의 label과 fingerprint가 바뀌지 않는다.
- 같은 평가 시점에 옛 series와 새 series가 함께 남는 경우가 생겨도 `max`가 하나로 합친다. 두 series는 같은 대상의 같은 확인이라 값이 같다. `max`이므로 둘 다 0일 때만 장애로 본다.
- `without`이라 남는 label(`check_id`, `check_target_id`, `check_type`, `module`, `instance`, `job`)은 지금과 같다. Manager가 쓰는 `check_id`, `check_target_id`, `instance`가 그대로 온다.

### 수집 틈

옛 series는 SD에서 빠지는 순간 stale로 끝나지만, 새 series는 새 target의 첫 수집 때 시작한다. 첫 수집 시점은 target마다 수집 주기 안에서 정해져, 그 사이에 평가가 있으면 `max without`의 결과가 비고 Alert가 끊긴다(진행 중 발견의 2026-11-09 C2). 그래서 두 규칙의 식을 다음처럼 바꾼다(사람 확인: 2026-11-09, 요구사항 S9~S11).

```promql
max without (check_name, environment) (probe_success{job="manager-blackbox"}) == 0
or
max without (check_name, environment) (last_over_time(probe_success{job="manager-blackbox"}[5m])) == 0
```

- `or` 뒤의 식이 최근 5분 안의 마지막 값을 본다. range vector는 stale 표시를 건너뛰므로 옛 series의 마지막 실패 값이 남는다. 그래서 수집 틈 동안 Alert와 `for`의 시작 시각이 이어진다.
- `or` 앞의 식은 지금 값만 본다. 사람이 정한 `max without (...) (last_over_time(...[5m])) == 0` 하나만 쓰면, 장애가 없을 때 이름·환경을 바꾼 뒤 5분 동안 옛 series의 마지막 성공 값(1)이 남아, 새 series가 실패해도 `max`가 1이 되어 장애가 늦게 잡힌다(요구사항 S3과 어긋남). 앞의 식이 이 경우를 잡는다. 사람이 받아들인 영향(지우거나 끈 대상의 복구가 최대 5분 늦어짐) 밖의 지연을 만들지 않으려는 구현 방법의 선택이다.
- 대상이 살아나면 다음 평가에서 복구된다. 값은 0과 1뿐이고 지금 값은 두 식에 모두 들어가므로, 지금 값이 1이면 두 식의 `max`가 모두 1이 되어 어느 쪽도 장애가 아니다. 지금 값이 있는 대상을 `unless`로 뒤의 식에서 빼는 것도 검토했지만, 결과가 같아 넣지 않는다(리뷰 지적, 2026-11-09).
- W는 5분이다(사람 확인: 2026-11-09). 감시 항목·주소를 지우거나 끄면, 주소나 확인 방식(`check_type`, `module`)을 바꾸면 옛 값의 Alert가, Blackbox Exporter가 멈추면 마지막 값이 실패였던 `MonitoringCheckDown`이, 마지막 실패 값이 5분 창에서 빠질 때 복구로 끝난다. 확인 방식을 바꾸면 그동안 옛 Alert와 새 Alert가 함께 firing일 수 있다. 끈 감시 항목을 5분 안에 다시 켜고 대상이 여전히 실패하면 같은 장애(같은 `startsAt`)로 이어진다(요구사항 S10).
- 남는 위험: 틈은 옛 target의 마지막 수집부터 새 target의 첫 수집까지라, 두 시점이 각각 수집 주기 안에서 정해져 최대 수집 주기 두 개(480초)까지 길어진다. 틈이 5분을 넘으면 끊긴다. 두 시점이 주기 안에 고르게 퍼진다고 보면, 수집 주기가 150초 이하면 끊기지 않고, 180초에서 약 6%, 200초에서 약 13%, 240초에서 약 28%의 확률로 끊긴다(계산한 추정이고 실행해 확인하지 않음). 사람이 받아들였다(2026-11-09).

검토했지만 고르지 않은 방법은 다음과 같다.

- SD label에서 `check_name`, `environment`를 뺌: 장애와 series가 모두 이어지지만, 사용자의 Grafana에서 감시 항목을 이름으로 보거나 환경으로 거를 수 없다. 사람이 고르지 않았다(요구사항 배경).
- Prometheus relabel(`labeldrop`)로 두 label을 series에서 뺌: SD label을 빼는 것과 결과가 같아 같은 이유로 고르지 않는다.
- `max by (check_id, check_target_id, check_type, module, instance, job)`처럼 남길 label을 적음: 결과는 같지만, 나중에 SD label이 늘면 규칙도 함께 고쳐야 한다. 빼는 label을 적는 쪽이 요구사항이 정한 범위(이름과 환경만 뺀다)를 그대로 드러낸다.
- annotation에서 `query` 템플릿으로 지금 `check_name`을 읽어 summary에 넣음: annotation은 fingerprint에 들어가지 않아 장애는 이어진다. 하지만 이름이 Manager Database가 아니라 Prometheus의 SD 값에서 오고, 템플릿이 복잡해지며, Alert에 이름을 기대지 않는다는 이번 결정과 어긋난다.

### 장애 내용 문구

식에서 `check_name`을 빼면 annotation에도 이름을 쓸 수 없다. 그래서 `MonitoringCheckDown`의 summary는 `instance` label 값을 넣은 "(주소) 확인에 실패했습니다"로 바꾼다. 이 저장소의 Manager는 summary를 `alert_events.summary`에 저장하고 장애 목록 화면의 내용 칸과 최근 장애 표에 보이므로, 이 변경 뒤에는 두 곳에서 감시 항목 이름이 빠진다. 2026-10-29에는 장애 목록 응답과 화면에 이름을 더하기로 했지만(요구사항 S6~S8), 관리자 서버의 API와 웹은 다른 웹에서 만들기로 해 이번에 하지 않는다(사람 확인: 2026-11-09). 관리자 서버가 장애 목록에 이름을 보이려면 기록의 `check_id`로 자기 DB의 지금 이름을 읽으라고 [관리자 서버 만들기](../ADMIN-SERVER.md)에 적는다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/prometheus/rules/monitoring-checks.yml` | 두 규칙의 `expr`, `MonitoringCheckDown`의 annotation, 파일 머리 주석의 label 목록 |
| `infrastructure/prometheus/tests/monitoring-checks.test.yml` | 두 Alert 규칙의 `promtool` 테스트(새 파일) |
| `infrastructure/alertmanager/alertmanager.yml` | `group_by`에서 `environment`를 빼고 주석을 고침 |
| `docs/INTEGRATION.md` | SD label 표의 `check_name` 뜻, SD 응답 규칙의 #22 줄, webhook 예시, 처리 순서의 이름·환경, Alert label 표, 묶음 설명 |
| `docs/ADMIN-SERVER.md` | 장애 수신 API와 저장할 데이터에 이름·환경을 DB에서 채우라는 안내 |
| `docs/OPERATIONS.md` | 관리자 서버 화면에서 바꾸는 값에 지우거나 끈 감시 항목의 복구 시점 |
| `docs/ARCHITECTURE.md` | 지켜야 할 경계의 SD label 줄에 Alert 규칙과 `group_by`가 이름·환경을 쓰지 않는다는 경계를 더함 |
| `docs/TESTING.md` | 테스트 종류 표의 규칙 테스트에 Alert 규칙을 더함 |

`backend/`와 `frontend/`는 바꾸지 않는다.

Alert 규칙의 전후는 다음과 같다. `for: 1m`, `severity`, 규칙 이름, `MonitoringCheckProbeFailed`의 annotation은 그대로다.

| 항목 | 지금 | 바꾼 뒤 |
|---|---|---|
| `MonitoringCheckDown` `expr` | `probe_success{job="manager-blackbox"} == 0` | 위 수집 틈의 식 |
| `MonitoringCheckProbeFailed` `expr` | `up{job="manager-blackbox"} == 0` | 위 수집 틈의 식에서 `probe_success`를 `up`으로 바꾼 식 |
| `MonitoringCheckDown` summary | (`check_name`) 확인에 실패했습니다 | (`instance`) 확인에 실패했습니다 |
| `MonitoringCheckDown` description 끝의 괄호 | (`check_type` / `environment`) | (`check_type`) |
| `group_by` | `alertname`, `check_id`, `environment` | `alertname`, `check_id` |

### 데이터 모델 변경

해당 없음. `alert_events`의 열과 값의 뜻은 그대로다. 새로 받는 장애의 `summary` 값만 위 문구로 바뀐다. 이미 저장한 기록은 바꾸지 않는다.

### 계약 변경

- Alertmanager webhook(`POST /api/integrations/alertmanager/webhook`, 공개 API): 요청의 `alerts[].labels`에서 `check_name`, `environment`가 빠진다. 앱이 받는 요청이 바뀌므로 다른 앱이 두 label을 읽고 있었다면 깨진다. 그래서 공개 API의 호환성을 깨는 변경으로 다루고, 그 커밋은 `fix!`와 `BREAKING CHANGE:`로 쓴다([DEVELOPMENT.md의 커밋](../DEVELOPMENT.md#커밋)). annotation `summary`, `description`의 문구도 바뀐다.
- 한 요청에 담기는 Alert의 묶음: `alertname`, `check_id`로 묶는다. 한 감시 항목의 환경은 하나라 묶음의 크기는 지금과 같고, 환경을 바꿀 때 묶음이 갈리지 않는다.
- HTTP SD 두 곳: 바뀌지 않는다. `BlackboxServiceDiscoveryServiceTest`가 지금 label을 그대로 확인한다.
- `ALERTS`, `ALERTS_FOR_STATE` series의 label에서 두 label이 빠진다. 앱은 이 series를 읽지 않는다(코드 검색으로 확인). 사용자가 Grafana에서 이 series를 이름으로 거르고 있었다면 깨진다.
- ADR 기준 판단: 이 변경은 코어와 앱 사이의 계약(webhook payload)이고, 다른 앱이라는 바깥 클라이언트가 기댄다. 되돌려 두 label을 다시 넣으면 다시 장애가 끊기므로, Alert label에 사람이 바꾸는 값을 넣지 않는다는 원칙 자체가 다시 고르기 어려운 결정이다. 그래서 [ADR 0010](../decisions/0010-alert-labels-without-editable-values.md) 초안을 남긴다.

## 위험과 되돌리기

- 배포 때 이어지던 장애가 새 장애로 다시 생김(S4): Alert의 label 집합이 한 번 바뀌므로 피할 수 없다. 갱신하면 Alertmanager 컨테이너가 다시 만들어지는데, Alertmanager는 Alert를 메모리에만 두므로 옛 Alert를 잊고, 새 Prometheus는 옛 label의 Alert를 만들지 않는다. 그래서 옛 장애의 복구 webhook은 오지 않고, 관리자 서버의 옛 장애 기록은 장애 중으로 남는다. 새 Alert는 `for: 1m`을 처음부터 센 뒤 생겨, 받는 그룹은 약 2분 뒤 새 장애 알림만 받는다(진행 중 발견의 2026-11-09 갱신 확인). 다만 아직 운영에 배포한 코어가 없어 이 영향은 생기지 않으므로, 운영 가이드에 안내를 넣지 않는다(사람 확인: 2026-11-09, 요구사항 S4).
- 이 저장소의 Manager와 함께 쓸 때: Manager는 고치지 않는다. 지금 Manager는 이름을 DB에서 읽고, `environment` label이 없으면 환경도 DB 값을 쓴다(`AlertEventService.environmentOf`를 읽어 확인). 그래서 알림은 동작하고, 장애 목록과 최근 장애 표의 내용 칸에서 이름만 빠진다(위 장애 내용 문구).
- `max without`이 다른 대상의 series를 합침: 남는 label에 `check_target_id`와 `instance`가 있어 대상이 다르면 합쳐지지 않는다. 규칙 테스트에 한 감시 항목의 대상 두 개가 각각 Alert가 되는 경우를 넣는다.
- 바깥 앱이나 사용자 Grafana가 webhook·`ALERTS`의 `check_name`, `environment`를 읽음: 이 저장소 밖이라 확인할 수 없다. INTEGRATION.md에 바뀐 label을 적고, 커밋을 `BREAKING CHANGE`로 표시해 갱신하는 쪽이 알 수 있게 한다.
- 되돌리기: 코어는 [운영 가이드의 이전 버전으로 되돌리기](../OPERATIONS.md#운영-작업)대로 이전 묶음 폴더에서 띄운다. 되돌리는 시점에 이어지는 장애가 있으면 label 집합이 바뀌어 배포 때와 같은 영향이 생긴다. 다만 운영에 배포한 코어가 아직 없어 운영에는 이 변경 전 묶음이 없으므로, 이 변경 전으로 되돌릴 일은 없다(사람 확인: 2026-11-09의 사실에서 판단). 데이터 이동은 없다.

### 0013 계획과 겹치는 부분

0013([#33](https://github.com/subinggrae/server-monitor/issues/33))도 `infrastructure/`와 `docs/OPERATIONS.md`를 고친다. 2026-10-29에 0013 요구사항을 읽어 판단했고, 0013 계획은 작성 중이라 읽지 않았다.

| 파일 | 0012 | 0013(요구사항 기준) | 주의 |
|---|---|---|---|
| `monitoring-checks.yml` | `expr`, annotation | 바꾸지 않음(`for: 1m`을 열지 않음) | 0013의 바뀌지 않는 것에 "장애 메시지 문구"가 있다. 0012가 summary를 바꾸므로 나중에 병합하는 쪽이 0013 문서의 이 줄을 확인한다 |
| `alertmanager.yml` | `group_by` | 바꾸지 않음 | 없음 |
| `scripts/build-core-bundle.mjs` | 바꾸지 않음(새 테스트 파일은 지금 반복이 읽음) | 여러 값으로 펼친 설정 검사를 더함 | 0013이 규칙 테스트 반복을 바꾸면 새 `monitoring-checks.test.yml`도 도는지 병합 뒤 `core-dev.bat check` 출력으로 확인한다 |
| `docs/OPERATIONS.md` | 언제 무엇을 바꾸나의 관리자 서버 화면에서 바꾸는 값(복구 시점 문단) | 언제 무엇을 바꾸나, 운영 작업, 문제 해결 | 언제 무엇을 바꾸나 절을 둘 다 고쳐 텍스트 충돌이 날 수 있다 |
| `docs/ARCHITECTURE.md` | 지켜야 할 경계 한 줄 | 지켜야 할 경계(새 값) | 같은 목록이라 충돌할 수 있다 |
| `docs/decisions/` | ADR 0010 | ADR을 쓰면 같은 번호를 고를 수 있음 | 나중에 병합하는 쪽이 번호를 다음 번호로 바꾸고 링크를 고친다 |

병합 순서는 어느 쪽이 먼저여도 된다. 나중에 병합하는 브랜치가 기본 브랜치를 받아 충돌을 풀고, `core-dev.bat check`와 `node scripts/check-docs.mjs`를 다시 실행한다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 없음. webhook은 지금처럼 Bearer token으로 호출하는 쪽을 확인한다. 알림의 환경 값이 webhook label 대신 Manager Database에서 오므로, webhook 요청의 값에 기대는 곳이 하나 준다.
- 잘못될 수 있는 것과 막는 방법: label이 빠진 요청을 관리자 서버가 처리하다 실패하거나 빈 값을 보냄(CWE-20) → INTEGRATION.md와 ADMIN-SERVER.md에 이름·환경을 label이 아니라 DB에서 채우라고 적는다. 이 저장소의 Manager가 두 label 없는 요청을 처리하는지는 C1, C2 수동 확인에서 Manager 로그와 기록으로 본다.
- 받아들이는 위험: 없음

## 테스트 계획

완료 기준마다 테스트와 기대 결과는 다음과 같다.

- **C1** 수동 확인
  - 방법: 요구사항 배경과 같은 절차로, 장애가 이어지는 TCP 감시 항목의 이름을 바꾼다.
  - 기대 결과: 바꾸기 전후 Alertmanager `GET /api/v2/alerts`의 `MonitoringCheckDown` fingerprint와 `startsAt`이 같다. 바꾼 뒤 5분 넘게 Manager 로그와 `alert_events`에 RESOLVED가 없고 장애 기록이 하나다.
- **C2** 수동 확인: C1과 같은 절차로 환경을 바꾼다. 기대 결과는 C1과 같다.
- **C4** `infrastructure/prometheus/tests/monitoring-checks.test.yml`
  - 경우: 실패가 이어지는 대상의 `check_name`, `environment`가 중간에 바뀌어도 바뀐 30초 뒤 Alert가 firing이다. Alert label에 두 label이 없고 annotation이 바뀐 문구다. 한 감시 항목의 대상 두 개는 Alert 두 개다. 성공 값이 오면 Alert가 없다. `MonitoringCheckProbeFailed`도 같다.
  - 명령: `core-dev.bat check` 통과. 규칙을 바꾸기 전에는 바뀐 30초 뒤 firing 경우가 실패한다(새 series가 pending이라).
- **C12** `monitoring-checks.test.yml`
  - 경우: 5초 간격 입력으로 옛 series를 stale로 끝내고 15초 넘게 뒤에 새 series를 시작한다(틈 15초와 4분). 두 경우 모두 Alert가 firing이고 `ALERTS_FOR_STATE`가 0이다. stale로 끝나고 새 series가 없는 대상은 4분 뒤 firing, 5분 넘어 Alert 없음. 실패하다 성공한 대상은 성공 값의 평가에서 Alert 없음. 성공 중에 이름을 바꾸고 바로 실패한 대상은 1분 뒤 firing. `MonitoringCheckProbeFailed`도 같은 경우를 모두 넣는다.
  - 명령: `core-dev.bat check` 통과. 지금 규칙에서는 틈과 삭제 경우가 실패한다. 둘째 묶음만 쓰는 식에서는 성공 중 이름 변경 경우가 실패한다.
- **C5** `amtool check-config`(`core-dev.bat check`에 포함)와 수동 확인
  - 기대 결과: 검사가 통과한다. 대상 두 개가 모두 실패하는 감시 항목에서 Manager가 받은 한 요청에 Alert 두 개가 있다.
- **C6** 리뷰와 문서 검사
  - 기대 결과: `node scripts/check-docs.mjs`에 오류가 없다. 리뷰어가 문서의 label 표와 예시를 규칙 파일, SD 코드와 맞춰 본다.
- **C13** 리뷰와 문서 검사
  - 기대 결과: INTEGRATION.md와 OPERATIONS.md에 지우거나 끈 감시 항목·주소의 장애가 최대 5분 뒤 복구된다고 적혀 있다.
- **C11** 리뷰와 문서 검사
  - 기대 결과: `node scripts/check-docs.mjs`에 오류가 없다. ADMIN-SERVER.md와 INTEGRATION.md의 장애 수신 설명에 이름·환경을 `check_id`로 찾은 DB 값으로 채우라는 문장이 있다.
- **바뀌지 않는 것** 기존 `BlackboxServiceDiscoveryServiceTest`가 SD label을 그대로 확인한다: 전체 검증의 `cd backend && ./gradlew build` 통과. `backend/`를 바꾸지 않으므로 `./gradlew integrationTest`는 실행하지 않는다.

규칙 테스트는 `interval: 15s`(운영 `evaluation_interval`과 같음)로 쓴다. 옛 series는 바꾼 시점에 stale 표시로 끝내고 새 series는 그 시점부터 시작하게 입력한다. `promtool` 입력 표기에서 `stale`을 쓸 수 있는지는 구현할 때 Prometheus 문서와 실행으로 확인한다(아직 확인하지 않음).

## 작업 순서

- [x] `monitoring-checks.test.yml`을 쓰고 지금 규칙으로 `core-dev.bat check`가 실패하는지 본다(C4)
- [x] `monitoring-checks.yml`의 식·annotation·머리 주석과 `alertmanager.yml`의 `group_by`·주석을 바꾼다. `core-dev.bat check` 통과(C4, C5). 커밋은 `fix!`와 `BREAKING CHANGE:`
- [x] INTEGRATION.md, ADMIN-SERVER.md, OPERATIONS.md, ARCHITECTURE.md, TESTING.md를 고치고 문서 검사를 통과시킨다(C6, C11)
- [x] 개발 환경에서 C1, C2, C5의 수동 확인을 하고 결과와 코어 변경 뒤 Manager의 장애 기록·알림 모습을 진행 중 발견에 적는다
- [x] 이름·환경을 바꿀 때 생기는 수집 틈을 메우는 방법을 사람이 정한다(2026-11-09, 접근 방법의 수집 틈)
- [x] 틈, 삭제, 복구, 성공 중 이름 변경의 규칙 테스트를 더하고 지금 규칙에서 실패하는지 본다(C12)
- [x] 두 규칙의 식을 바꾸고 `core-dev.bat check`를 통과시킨다. INTEGRATION.md, OPERATIONS.md에 바뀐 복구 시점을 적는다(C12, C13)
- [x] 개발 환경에서 C1, C2를 다시 확인한다. 수집 주기 30초 이상을 포함해 환경을 여러 번 바꾼다
- [ ] 전체 검증(`cd frontend && npm run check`, `cd backend && ./gradlew build`, `core-dev.bat check`)과 새 세션의 리뷰

## 진행 중 발견

- 2026-10-29 기준으로 `AlertEventService`와 `AlertMessageFormatter`의 테스트는 없다. webhook 통합 테스트(`AlertmanagerWebhookIntegrationTest`)는 token 확인과 기록만 본다. 2026-11-09에 Manager를 고치지 않기로 해 C3, C7의 테스트도 쓰지 않는다.
- `infrastructure/prometheus/tests/`에는 recording rule 테스트(`server-resources.test.yml`)만 있고 Alert 규칙 테스트는 없다(요구사항 0008의 C1에도 적혀 있다). C4의 테스트는 새 파일이다.
- 2026-11-09 수동 확인. 이 브랜치의 코어와 Manager 이미지를 따로 띄우고, 닫힌 포트 두 개(`manager-db:1`, `manager-db:2`)를 확인하는 TCP 감시 항목을 수집 주기 10초, 재발송 주기 5분으로 만들었다. 가짜 Telegram 서버로 실제 메시지를 받았다.
  - C5: Manager 로그에 `group_key={}:{alertname="MonitoringCheckDown", check_id="1"} status=firing count=2`로 두 대상의 Alert가 한 요청에 왔다. Alert label에 `check_name`, `environment`가 없고 summary는 `manager-db:1 확인에 실패했습니다`였다.
  - C1(02:08:36 UTC에 이름을 바꿈): 원래 `probe_success` series는 02:08:44에 끊기고 새 이름의 series가 시작했다. 두 Alert의 fingerprint(`0ec192097c1b3195`, `7838356f964841d3`)와 `startsAt`이 그대로였고 `ALERTS_FOR_STATE`에 빈 구간이 없었다. 02:13:16 webhook은 firing 두 건뿐이었고 장애 기록도 두 건 그대로였다. 이때 간 재발송 메시지 제목은 새 이름이었다.
  - C2(02:14:00 UTC에 환경을 바꿈): 대상 1은 이어졌지만 대상 2는 끊겼다. 대상 2의 옛 series 마지막 sample은 02:14:05.785, 새 series 첫 sample은 02:14:17.335였고, 그 사이 02:14:16.731의 규칙 평가에 값이 없어 Alert가 한 번 사라졌다. 다음 평가부터 `for: 1m`을 다시 세어 `startsAt`이 02:15:31로 새로 잡혔다(fingerprint는 같음). 그 사이에 Alertmanager의 전송 시점이 없어 Manager에 복구 webhook은 오지 않았지만, 02:18:16 webhook으로 Manager에 장애 기록 3번이 새로 생기고 옛 기록 2번은 FIRING으로 남았다. 재발송 메시지에는 새 이름과 `환경: Staging`이 보였다.
  - 원인: Prometheus는 SD에서 빠진 target의 series를 바로 stale로 끝내고, 새 label의 target은 자기 수집 주기 안의 정해진 시점에 처음 수집한다. 이 틈(최대 수집 주기 하나, 10~240초)에 규칙 평가(15초마다)가 있으면 `max without`의 결과가 비어 Alert가 끊긴다. 수집 주기가 길수록 거의 늘 끊긴다. 요구사항 배경의 시험 규칙이 이어진 것은 틈이 평가 시점에 걸리지 않았기 때문으로 보인다. 지금 `monitoring-checks.test.yml`은 새 series가 stale 표시와 같은 시점에 시작해 이 틈을 확인하지 않는다. 5초 간격 입력으로 옛 series를 2m5s에 stale로, 새 series를 2m20s부터 넣은 임시 테스트는 지금 규칙에서 실패했다.
  - 고칠 방법 후보: 규칙에 `keep_firing_for`를 두어 틈 동안 Alert를 유지함(모든 복구가 그만큼 늦어짐), 식을 `last_over_time(...[W])`로 바꿔 stale 표시를 건넘(삭제·주소 변경 때 W만큼 늦게 복구됨), 틈은 받아들이고 문서에 적음. 사람이 둘째(W 5분)를 골랐다(2026-11-09). 정한 식은 접근 방법의 수집 틈에 있다.
- 2026-11-09 갱신 확인(리뷰 지적). 548839c의 코어로 장애를 만들고(fingerprint `ab96a65f2b01cd6c`, Manager 기록 1번 FIRING), 04:24:27 UTC에 같은 volume으로 50b1d12의 코어 이미지로 바꿔 `docker compose up -d`를 실행했다. 세 코어 컨테이너가 다시 만들어졌다. 04:26:31에 새 fingerprint `0ec192097c1b3195`의 firing webhook이 와서 기록 2번과 새 장애 알림이 생겼다. 04:32까지 옛 fingerprint의 복구 webhook은 오지 않았고, 기록 1번은 FIRING으로 남았다. 복구 알림은 0건이었다.
- 2026-11-09 다시 확인(커밋 8cf4fa3의 이미지). 닫힌 포트를 확인하는 TCP 감시 항목 두 개를 만들었다. 1번은 수집 주기 10초에 주소 두 개, 2번은 60초에 주소 두 개다. 네 대상이 장애가 된 뒤 03:29:4x, 03:31:4x, 03:33:4x, 03:35:4x UTC에 두 감시 항목의 환경, 이름, 환경, 이름·환경(처음 값)을 차례로 바꿨다.
  - C1, C2: 네 Alert의 fingerprint와 `startsAt`(03:28:01, 03:28:31)이 끝까지 그대로였고, `ALERTS_FOR_STATE`에 빈 평가가 없었다. 지금 값만 보는 식에 대상이 빠진 평가가 60초 대상에서 세 번(03:30:31, 03:34:31, 03:36:16) 있었지만, 그때도 이어졌다. 03:28~03:44의 webhook 여덟 건은 모두 firing이었고, Manager 장애 기록은 네 건 그대로였다. 재발송 메시지에는 보낸 시점의 이름과 환경이 보였다.
  - S10: 2번 감시 항목의 주소 4를 03:47:09에 껐다. 마지막 sample은 03:46:19.994, 마지막 firing 평가는 03:51:16, 복구 시각은 03:51:31이었다. 복구 webhook과 `[RESOLVED]` 알림은 다음 묶음 전송(03:54:15)에 갔다.

## 남길 결정

- Alert label과 Alertmanager `group_by`에는 사용자가 바꾸는 값(감시 항목 이름, 환경)을 쓰지 않고, 앱이 알림의 이름과 환경을 자기 DB에서 채운다: [ADR 0010](../decisions/0010-alert-labels-without-editable-values.md)(승인)
