# 0012. 감시 항목 이름·환경·확인 방식을 바꿔도 이어지는 장애 구현 계획

- 요구사항: [0012. 감시 항목 이름·환경·확인 방식을 바꿔도 이어지는 장애](0012-check-label-continuity.md)
- 상태: 검토 중

## 접근 방법

두 Alert 규칙(`MonitoringCheckDown`, `MonitoringCheckProbeFailed`)이 감시 항목과 주소의 id로만 묶어 판단한다. `X`는 `probe_success`와 `up`이다.

```promql
max by (check_id, check_target_id) (X{job="manager-blackbox"}) == 0
or
max by (check_id, check_target_id) (last_over_time(X{job="manager-blackbox"}[5m])) == 0
```

- `by (check_id, check_target_id)`: 결과의 label이 두 id뿐이라, 이름·환경·확인 방식·주소가 바뀌어도 Alert의 label 집합이 그대로다. Alert 규칙의 `severity`와 `alertname`이 더해진다. 값을 바꾸는 동안 옛 series와 새 series가 함께 있어도 `max`가 주소마다 하나로 합친다.
- `or` 앞의 식은 지금 값만 본다. stale로 끝난 옛 series는 들어가지 않으므로, 장애가 없을 때 값을 바꾼 직후 실패하면 바로 실패로 센다(S3).
- `or` 뒤의 식은 stale 표시를 건너뛴 최근 5분의 마지막 값을 본다. 옛 series가 끝나고 새 series가 처음 수집되기 전의 틈을 메운다(S9). 지금 값이 있는 주소는 그 값이 두 식에 함께 들어가고 `max`라서, 지금 값이 1이면 어느 쪽도 장애가 아니다. 그래서 살아난 주소는 바로 복구된다(S11).
- 창 5분은 가장 긴 수집 주기(240초)보다 길게 잡은 값이다(사람 확인: 2026-11-11).

annotation에는 label에 없는 값을 쓸 수 없으므로 이름, 환경, 주소, 확인 방식을 넣지 않는다. 관리자 서버가 이 값을 DB에서 채워 알림을 만든다.

검토했지만 고르지 않은 방법은 다음과 같다.

- `max without (check_name, environment)`([PR #43](https://github.com/subinggrae/server-monitor/pull/43)): `check_type`, `module`, `instance`가 남아 확인 방식과 주소를 바꿀 때 장애가 끊긴다.
- `last_over_time` 식만 쓰기(이슈 #22의 2026-11-11 결정 원문): 장애가 없을 때 값을 바꾼 직후 실패하면 옛 series의 마지막 성공 값이 5분 동안 남아 장애를 최대 5분 늦게 잡는다(사람 확인: 2026-11-12).
- SD label에서 이름·환경을 빼기, 관리자 서버에서 복구를 걸러내기: [ADR 0010](../decisions/0010-alert-labels-by-check-id.md)에 이유가 있다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/prometheus/rules/monitoring-checks.yml` | 두 규칙의 식을 위로 바꾼다. annotation에서 `check_name`, `instance`, `check_type`, `environment`를 뺀다. 파일 머리 주석의 label 목록을 고친다 |
| `infrastructure/prometheus/tests/monitoring-checks.test.yml` | 새 규칙 테스트. PR #43의 테스트를 바탕으로 확인 방식·주소 변경과 바뀐 label을 더한다 |
| `infrastructure/alertmanager/alertmanager.yml` | `group_by`를 `[alertname, check_id, environment]`에서 `[alertname, check_id]`로 바꾼다 |
| `backend/.../alert/AlertEventService.java` | 알림의 환경을 Alert label이 아니라 감시 항목의 DB 값으로 채운다 |
| `backend/.../alert/AlertEventServiceTest.java` | 새 단위 테스트(C3) |
| `backend/.../prometheus/BlackboxServiceDiscoveryService.java` | label 주석을 실제와 맞춘다. SD label은 그대로다 |
| `docs/INTEGRATION.md` | SD label 뜻, webhook 예시, Alert label 표, 묶음, 처리 순서, 복구 시점 |
| `docs/ADMIN-SERVER.md` | 관리자 서버가 이름·환경·주소를 DB에서 채우는 안내와 예시 |
| `docs/OPERATIONS.md` | 지우거나 끈 감시 항목·주소의 복구 시점 |
| `docs/ARCHITECTURE.md` | 지켜야 할 경계에 Alert label 규칙 |
| `docs/TESTING.md` | 규칙 테스트 범위에 Alert 규칙 |
| `docs/decisions/0010-alert-labels-by-check-id.md` | 새 ADR |

### 데이터 모델 변경

해당 없음

### 계약 변경

Alertmanager webhook 계약(INTEGRATION.md의 Alert)이 바뀐다. `BREAKING CHANGE`다.

| 항목 | 이전 | 새 값 |
|---|---|---|
| Alert label | `alertname`, `severity`, `check_id`, `check_target_id`, `check_name`, `check_type`, `module`, `environment`, `instance`, `job` | `alertname`, `severity`, `check_id`, `check_target_id` |
| `MonitoringCheckDown` annotation | `summary`: `{{ check_name }} 확인에 실패했습니다`, `description`: 주소, 확인 방식, 환경을 넣은 문장 | 이름·주소·확인 방식·환경이 없는 문장 |
| `MonitoringCheckProbeFailed` annotation | `description`에 주소 | 주소가 없는 문장 |
| `group_by` | `alertname`, `check_id`, `environment` | `alertname`, `check_id` |

HTTP SD 응답의 label은 바뀌지 않는다.

## 위험과 되돌리기

- webhook의 빠진 label을 읽던 관리자 서버가 깨진다: 운영에 배포한 코어가 없고(사람 확인: 2026-11-12), INTEGRATION.md와 ADMIN-SERVER.md에 DB에서 채우라고 적는다. 커밋에 `BREAKING CHANGE`를 쓴다.
- 식이 틀려 장애를 놓치거나 늦게 잡는다: 규칙 테스트가 S3, S9~S13을 확인하고, 바꾸기 전 규칙과 한쪽 식만 쓰는 규칙에서 실패하는지 확인한다.
- 지우거나 끈 주소의 장애가 최대 5분 늦게 복구되고 그 사이 재발송 알림이 한 번 더 갈 수 있다: 받아들인 위험이다(요구사항 S10).
- 되돌리기: 이 변경의 커밋을 되돌리고 코어를 다시 빌드해 배포한다. 데이터 마이그레이션은 없다. 되돌리면 label 집합이 다시 바뀌어 그때 이어지던 장애가 새 장애로 한 번 다시 생긴다.

### 보안 위협

해당 없음. webhook의 인증(Bearer token)과 받는 입력의 형식은 그대로이고, 관리자 서버가 읽는 label이 줄어들 뿐이다.

## 테스트 계획

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1, C4, C12 | 규칙 테스트 `monitoring-checks.test.yml`: 이름·환경·확인 방식·주소 변경 때 label과 Alert가 이어짐, 수집 틈 15초와 4분, 지우거나 끈 주소는 5분 뒤 복구, 살아난 주소는 바로 복구, 장애가 없을 때 값을 바꾼 뒤 실패하면 1분 뒤 장애, Alert label과 annotation | `core-dev.bat check` 통과. 저장소 밖 사본에서 바꾸기 전 규칙, `or` 앞의 식만, `or` 뒤의 식만, `without (check_name, environment)`로 바꾸면 실패 |
| C3 | 단위 테스트 `AlertEventServiceTest`: label에 다른 환경이 있어도 알림의 환경이 DB 값 | `cd backend && ./gradlew test --tests '*AlertEventServiceTest'` 통과. 구현 전에는 실패 |
| C5 | 설정 검사 | `core-dev.bat check`의 `amtool check-config` 통과 |
| C2, C5 | 개발 환경 수동 확인: 수집 주기 10초와 60초의 감시 항목(주소 둘 이상)을 실패시키고, 장애 중에 이름·환경과 확인 방식을 바꾼다 | Alertmanager API의 fingerprint와 `startsAt`이 그대로, 관리자 서버에 복구 webhook 0건, 장애 기록 수가 그대로, 한 감시 항목의 주소들이 한 요청으로 옴 |
| C6, C11, C13 | 문서 검사와 리뷰 | `node scripts/check-docs.mjs` 오류 0 |
| 전체 | 전체 검증 | `cd frontend && npm run check`, `cd backend && ./gradlew build`, `core-dev.bat check`, `node scripts/check-docs.mjs` 통과 |

## 작업 순서

- [ ] 규칙 테스트를 쓰고, 지금 규칙에서 실패하는지 확인한다
- [ ] Alert 규칙과 `group_by`를 바꾸고 `core-dev.bat check`로 확인한다. 저장소 밖 사본으로 테스트가 다른 식에서 실패하는지 확인한다
- [ ] `AlertEventServiceTest`를 쓰고 실패를 확인한 뒤 `AlertEventService`를 고친다
- [ ] `BlackboxServiceDiscoveryService`의 주석을 고친다
- [ ] 문서(INTEGRATION, ADMIN-SERVER, OPERATIONS, ARCHITECTURE, TESTING)를 고친다
- [ ] 개발 환경에서 C2, C5를 수동 확인하고 결과를 진행 중 발견에 적는다
- [ ] 전체 검증을 실행하고, 새 하위 에이전트에게 리뷰를 맡긴다
- [ ] 요구사항을 구현됨으로, ADR 0010을 승인으로 바꾸고 이 계획을 지운다

## 진행 중 발견

없음

## 남길 결정

- Alert 규칙은 감시 항목과 주소의 id로만 묶고, 값을 바꾸는 틈은 최근 5분의 마지막 값으로 메운다: [ADR 0010](../decisions/0010-alert-labels-by-check-id.md)
