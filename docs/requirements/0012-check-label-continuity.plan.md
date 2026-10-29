# 0012. 감시 항목 이름·환경을 바꿔도 이어지는 장애 구현 계획

- 요구사항: [0012. 감시 항목 이름·환경을 바꿔도 이어지는 장애](0012-check-label-continuity.md)
- 상태: 검토 중

## 접근 방법

요구사항에서 사람이 정한 방법을 그대로 쓴다. SD label은 그대로 두고, 장애를 만드는 두 Alert 규칙의 식에서 `check_name`, `environment`를 빼고, Alertmanager `group_by`에서 `environment`를 뺀다. 알림의 이름과 환경은 Manager가 Manager Database 값으로 채운다.

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
    → max without (check_name, environment) (...) == 0
    → Alert label = check_id, check_target_id, check_type, module, instance, job, alertname, severity
    → group_by [alertname, check_id]
    → Manager: 이름과 환경 모두 DB
```

`max without (check_name, environment)`로 고른 이유는 다음과 같다.

- 이름·환경을 바꾸는 순간 Prometheus는 옛 target을 지우며 옛 series에 stale 표시를 남기고, 새 label 집합의 series를 시작한다. stale series는 instant vector에서 빠지므로, 두 label을 뺀 결과는 같은 label 집합의 값 하나로 끊김 없이 이어진다. 이 동작은 요구사항 배경의 시험 규칙으로 개발 환경에서 확인했다(fingerprint와 `startsAt` 유지, `ALERTS` series에 빈 구간 없음).
- 같은 평가 시점에 옛 series와 새 series가 함께 남는 경우가 생겨도 `max`가 하나로 합친다. 두 series는 같은 대상의 같은 확인이라 값이 같다. `max`이므로 둘 다 0일 때만 장애로 본다.
- `without`이라 남는 label(`check_id`, `check_target_id`, `check_type`, `module`, `instance`, `job`)은 지금과 같다. Manager가 쓰는 `check_id`, `check_target_id`, `instance`가 그대로 온다.

검토했지만 고르지 않은 방법은 다음과 같다.

- SD label에서 `check_name`, `environment`를 뺌: 장애와 series가 모두 이어지지만, 사용자의 Grafana에서 감시 항목을 이름으로 보거나 환경으로 거를 수 없다. 사람이 고르지 않았다(요구사항 배경).
- Prometheus relabel(`labeldrop`)로 두 label을 series에서 뺌: SD label을 빼는 것과 결과가 같아 같은 이유로 고르지 않는다.
- `max by (check_id, check_target_id, check_type, module, instance, job)`처럼 남길 label을 적음: 결과는 같지만, 나중에 SD label이 늘면 규칙도 함께 고쳐야 한다. 빼는 label을 적는 쪽이 요구사항이 정한 범위(이름과 환경만 뺀다)를 그대로 드러낸다.
- annotation에서 `query` 템플릿으로 지금 `check_name`을 읽어 summary에 넣음: annotation은 fingerprint에 들어가지 않아 장애는 이어진다. 하지만 이름이 Manager Database가 아니라 Prometheus의 SD 값에서 오고, 템플릿이 복잡해지며, Alert에 이름을 기대지 않는다는 이번 결정과 어긋난다.

### 정하지 않은 것

- **summary 문구와 장애 목록의 이름**: 지금 `MonitoringCheckDown`의 summary는 `check_name` label 값을 넣은 "(이름) 확인에 실패했습니다"다. 식에서 `check_name`을 빼면 annotation에서도 쓸 수 없다. summary는 Manager가 `alert_events.summary`에 저장하고, Telegram 메시지 본문(`AlertMessageFormatter`)과 장애 목록 화면의 `내용` 열(`AlertListPage.tsx`, `alert.summary ?? alert.alertName`)이 보인다. summary에서 이름을 빼면 장애 목록에서 감시 항목 이름이 보이지 않는다. 이 계획은 summary를 `instance` label 값을 넣은 "(주소) 확인에 실패했습니다"로 바꾸는 것까지만 정했다. 장애 목록에 이름을 보일지는 사람이 정한다. 보이기로 하면 요구사항에 동작과 완료 기준을 더하고 아래 작업 순서의 해당 작업을 한다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/prometheus/rules/monitoring-checks.yml` | 두 규칙의 `expr`, `MonitoringCheckDown`의 annotation, 파일 머리 주석의 label 목록 |
| `infrastructure/prometheus/tests/monitoring-checks.test.yml` | 두 Alert 규칙의 `promtool` 테스트(새 파일) |
| `infrastructure/alertmanager/alertmanager.yml` | `group_by`에서 `environment`를 빼고 주석을 고침 |
| `backend/.../alert/AlertEventService.java` | `environmentOf`는 DB 값만 쓰고, `alertNameOf`의 `check_name` 대체와 두 label 상수를 지움 |
| `backend/.../alert/AlertEventServiceTest.java` | Mockito 단위 테스트(새 파일) |
| `backend/.../notification/AlertMessageFormatterTest.java` | 단위 테스트(새 파일) |
| `backend/.../prometheus/BlackboxServiceDiscoveryService.java` | `toTarget`의 주석을 실제 동작에 맞춤(코드는 그대로) |
| `docs/INTEGRATION.md` | SD label 표의 `check_name` 뜻, SD 응답 규칙의 #22 줄, webhook 예시, Alert label 표, 묶음 설명 |
| `docs/OPERATIONS.md` | 장애 수신 API의 예시, 운영 작업의 새 버전으로 갱신에 1회성 영향 |
| `docs/ARCHITECTURE.md` | 지켜야 할 경계의 SD label 줄에 Alert 규칙과 `group_by`가 이름·환경을 쓰지 않는다는 경계를 더함 |
| `AlertEventResponse`, `AlertListPage.tsx` | 장애 목록에 이름을 보이기로 정했을 때만 고침 |

`backend/...`는 `backend/src/main/java/kr/co/unidocs/monitoring/`(테스트는 `backend/src/test/java/kr/co/unidocs/monitoring/`)다.

Alert 규칙의 전후는 다음과 같다. `for: 1m`, `severity`, 규칙 이름, `MonitoringCheckProbeFailed`의 annotation은 그대로다.

| 항목 | 지금 | 바꾼 뒤 |
|---|---|---|
| `MonitoringCheckDown` `expr` | `probe_success{job="manager-blackbox"} == 0` | `max without (check_name, environment) (probe_success{job="manager-blackbox"}) == 0` |
| `MonitoringCheckProbeFailed` `expr` | `up{job="manager-blackbox"} == 0` | `max without (check_name, environment) (up{job="manager-blackbox"}) == 0` |
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

- 배포 때 이어지던 장애가 한 번 복구됐다가 다시 생김(S4): Alert의 label 집합이 한 번 바뀌므로 피할 수 없다. 옛 Alert는 Prometheus가 더 보내지 않아 Alertmanager가 복구로 끝내고, 새 Alert는 `for: 1m`을 처음부터 센 뒤 생긴다. 받는 그룹은 복구 알림과 약 1분 반 뒤의 새 장애 알림을 받는다. 운영 가이드의 새 버전으로 갱신에 이 영향과 장애가 적은 시간에 갱신하라는 안내를 적는다. 사람이 받아들였다(요구사항 S4, 2026-10-29).
- 코어와 Manager의 배포 순서: Manager가 먼저 바뀌어도 옛 코어의 label을 쓰지 않을 뿐이고, 코어가 먼저 바뀌어도 지금 Manager는 `environment` label이 없으면 DB 값을 쓴다(`AlertEventService.environmentOf`). 어느 순서로 배포해도 알림은 동작한다.
- `max without`이 다른 대상의 series를 합침: 남는 label에 `check_target_id`와 `instance`가 있어 대상이 다르면 합쳐지지 않는다. 규칙 테스트에 한 감시 항목의 대상 두 개가 각각 Alert가 되는 경우를 넣는다.
- 바깥 앱이나 사용자 Grafana가 webhook·`ALERTS`의 `check_name`, `environment`를 읽음: 이 저장소 밖이라 확인할 수 없다. INTEGRATION.md에 바뀐 label을 적고, 커밋을 `BREAKING CHANGE`로 표시해 갱신하는 쪽이 알 수 있게 한다.
- 장애 목록의 `내용` 열에서 이름이 빠짐: 정하지 않은 것에 따른다.
- 되돌리기: 코어는 [운영 가이드의 이전 버전으로 되돌리기](../OPERATIONS.md#운영-작업)대로 이전 묶음 폴더에서 띄운다. 이때도 label 집합이 바뀌므로 배포 때와 같은 1회성 영향이 한 번 더 생긴다. Manager 변경은 옛 코어와도 동작하므로 되돌리지 않아도 된다. 데이터 이동은 없다.

### 0013 계획과 겹치는 부분

0013([#33](https://github.com/subinggrae/server-monitor/issues/33))도 `infrastructure/`와 `docs/OPERATIONS.md`를 고친다. 2026-10-29에 0013 요구사항을 읽어 판단했고, 0013 계획은 작성 중이라 읽지 않았다.

| 파일 | 0012 | 0013(요구사항 기준) | 주의 |
|---|---|---|---|
| `monitoring-checks.yml` | `expr`, annotation | 바꾸지 않음(`for: 1m`을 열지 않음) | 0013의 바뀌지 않는 것에 "장애 메시지 문구"가 있다. 0012가 summary를 바꾸므로 나중에 병합하는 쪽이 0013 문서의 이 줄을 확인한다 |
| `alertmanager.yml` | `group_by` | 바꾸지 않음 | 없음 |
| `scripts/build-core-bundle.mjs` | 바꾸지 않음(새 테스트 파일은 지금 반복이 읽음) | 여러 값으로 펼친 설정 검사를 더함 | 0013이 규칙 테스트 반복을 바꾸면 새 `monitoring-checks.test.yml`도 도는지 병합 뒤 `core-dev.bat check` 출력으로 확인한다 |
| `docs/OPERATIONS.md` | 장애 수신 API, 운영 작업의 새 버전으로 갱신 | 언제 무엇을 바꾸나, 운영 작업, 문제 해결 | 운영 작업 목록의 같은 줄 근처를 둘 다 고칠 수 있어 텍스트 충돌이 날 수 있다 |
| `docs/ARCHITECTURE.md` | 지켜야 할 경계 한 줄 | 지켜야 할 경계(새 값) | 같은 목록이라 충돌할 수 있다 |
| `docs/decisions/` | ADR 0010 | ADR을 쓰면 같은 번호를 고를 수 있음 | 나중에 병합하는 쪽이 번호를 다음 번호로 바꾸고 링크를 고친다 |

병합 순서는 어느 쪽이 먼저여도 된다. 나중에 병합하는 브랜치가 기본 브랜치를 받아 충돌을 풀고, `core-dev.bat check`와 `node scripts/check-docs.mjs`를 다시 실행한다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 없음. webhook은 지금처럼 Bearer token으로 호출하는 쪽을 확인한다. 알림의 환경 값이 webhook label 대신 Manager Database에서 오므로, webhook 요청의 값에 기대는 곳이 하나 준다.
- 잘못될 수 있는 것과 막는 방법: label이 빠진 요청을 처리하다 실패하거나 빈 값을 보냄(CWE-20) → 없는 label은 `null`로 다루고 이름·환경은 DB 값만 쓴다. `AlertEventServiceTest`에 두 label이 없는 요청과 옛 label이 남은 요청을 넣는다.
- 받아들이는 위험: 없음

## 테스트 계획

완료 기준마다 테스트와 기대 결과는 다음과 같다.

- **C1** 수동 확인
  - 방법: 요구사항 배경과 같은 절차로, 장애가 이어지는 TCP 감시 항목의 이름을 바꾼다.
  - 기대 결과: 바꾸기 전후 Alertmanager `GET /api/v2/alerts`의 `MonitoringCheckDown` fingerprint와 `startsAt`이 같다. 바꾼 뒤 5분 넘게 Manager 로그와 `alert_events`에 RESOLVED가 없고 장애 기록이 하나다.
- **C2** 수동 확인: C1과 같은 절차로 환경을 바꾼다. 기대 결과는 C1과 같다.
- **C3** `AlertEventServiceTest`
  - 경우: label에 옛 환경이 있어도 알림의 환경은 DB 값이다. 두 label이 없어도 이름과 환경은 DB 값이다.
  - 명령: `cd backend && ./gradlew test --tests '*AlertEventServiceTest'` 통과. 구현 전에는 옛 환경 경우가 실패한다.
- **C3** `AlertMessageFormatterTest`
  - 경우: 메시지 제목에 알림의 이름, `환경:` 줄에 알림의 환경이 보인다.
  - 명령: `cd backend && ./gradlew test --tests '*AlertMessageFormatterTest'` 통과
- **C4** `infrastructure/prometheus/tests/monitoring-checks.test.yml`
  - 경우: 실패가 이어지는 대상의 `check_name`, `environment`가 중간에 바뀌어도 바뀐 30초 뒤 Alert가 firing이다. Alert label에 두 label이 없고 annotation이 바뀐 문구다. 한 감시 항목의 대상 두 개는 Alert 두 개다. 성공 값이 오면 Alert가 없다. `MonitoringCheckProbeFailed`도 같다.
  - 명령: `core-dev.bat check` 통과. 규칙을 바꾸기 전에는 바뀐 30초 뒤 firing 경우가 실패한다(새 series가 pending이라).
- **C5** `amtool check-config`(`core-dev.bat check`에 포함)와 수동 확인
  - 기대 결과: 검사가 통과한다. 대상 두 개가 모두 실패하는 감시 항목에서 Manager가 받은 한 요청에 Alert 두 개가 있다.
- **C6** 리뷰와 문서 검사
  - 기대 결과: `node scripts/check-docs.mjs`에 오류가 없다. 리뷰어가 문서의 label 표와 예시를 규칙 파일, SD 코드와 맞춰 본다.
- **C7** `AlertEventServiceTest`
  - 경우: `check_id`의 감시 항목이 없는 복구 Alert는 기록되고 알림이 비어 있다.
  - 명령: C3과 같은 명령으로 통과
- **바뀌지 않는 것** 기존 `BlackboxServiceDiscoveryServiceTest`, `AlertmanagerWebhookIntegrationTest`: `cd backend && ./gradlew test`, `./gradlew integrationTest` 통과
규칙 테스트는 `interval: 15s`(운영 `evaluation_interval`과 같음)로 쓴다. 옛 series는 바꾼 시점에 stale 표시로 끝내고 새 series는 그 시점부터 시작하게 입력한다. `promtool` 입력 표기에서 `stale`을 쓸 수 있는지는 구현할 때 Prometheus 문서와 실행으로 확인한다(아직 확인하지 않음).

## 작업 순서

- [ ] `AlertEventServiceTest`, `AlertMessageFormatterTest`를 쓰고 옛 환경 label 경우가 실패하는지 본다(C3, C7)
- [ ] `AlertEventService`의 `environmentOf`를 DB 값만 쓰게 바꾸고 `check_name` 대체를 지운다. 테스트 통과
- [ ] `monitoring-checks.test.yml`을 쓰고 지금 규칙으로 `core-dev.bat check`가 실패하는지 본다(C4)
- [ ] `monitoring-checks.yml`의 식·annotation·머리 주석과 `alertmanager.yml`의 `group_by`·주석을 바꾼다. `core-dev.bat check` 통과(C4, C5). 커밋은 `fix!`와 `BREAKING CHANGE:`
- [ ] `BlackboxServiceDiscoveryService`의 주석을 고친다
- [ ] INTEGRATION.md, OPERATIONS.md, ARCHITECTURE.md를 고치고 문서 검사를 통과시킨다(C6)
- [ ] 장애 목록에 이름을 보이기로 했으면: 요구사항을 고쳐 승인받고, 테스트를 먼저 쓴 뒤 응답과 화면을 고치고 화면 검사를 실행한다
- [ ] 개발 환경에서 C1, C2, C5의 수동 확인을 하고 결과를 진행 중 발견에 적는다
- [ ] 전체 검증(`cd frontend && npm run check`, `cd backend && ./gradlew build`, `./gradlew integrationTest`, `core-dev.bat check`)과 새 세션의 리뷰

## 진행 중 발견

- 요구사항 C7의 확인 방법은 "기존 `AlertEventService` 테스트"지만, 2026-10-29 기준으로 `AlertEventService`와 `AlertMessageFormatter`의 테스트는 없다. webhook 통합 테스트(`AlertmanagerWebhookIntegrationTest`)는 token 확인과 기록만 본다. 그래서 C3, C7의 테스트를 새로 쓴다.
- `infrastructure/prometheus/tests/`에는 recording rule 테스트(`server-resources.test.yml`)만 있고 Alert 규칙 테스트는 없다(요구사항 0008의 C1에도 적혀 있다). C4의 테스트는 새 파일이다.

## 남길 결정

- Alert label과 Alertmanager `group_by`에는 사용자가 바꾸는 값(감시 항목 이름, 환경)을 쓰지 않고, 앱이 알림의 이름과 환경을 자기 DB에서 채운다: [ADR 0010](../decisions/0010-alert-labels-without-editable-values.md)(초안)
