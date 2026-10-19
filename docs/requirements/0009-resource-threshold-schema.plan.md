# 0009. 서버 리소스 임계값 알림의 DDL과 판단 계약 구현 계획

- 요구사항: [0009. 서버 리소스 임계값 알림의 DDL과 판단 계약](0009-resource-threshold-schema.md)
- 상태: 검토 중

## 접근 방법

참고용 DDL을 vendor별 SQL 파일로 `docs/reference-schema/`에 두고, 통합 계약에 판단 규칙과 그 파일의 링크를 쓴다. 이 앱의 migration(`backend/src/main/resources/db/migration/`)에는 넣지 않는다. 이 앱도 다른 웹도 새 테이블을 그대로 쓰지 않기 때문이다. 판단을 앱에 두는 결정은 [ADR 0008](../decisions/0008-resource-threshold-in-app.md)에 남긴다.

참고용 DDL은 기준 migration(`V1__baseline.sql`)의 `server_targets`, `monitored_resources`를 외래 키로 가리킨다. 그래서 통합 테스트는 빈 MariaDB와 PostgreSQL에 Flyway로 기준 migration을 적용한 뒤 참고용 SQL을 실행한다. 이 앱을 기동하지 않으므로 Entity 검증과 관계없다.

검토했지만 고르지 않은 방법은 다음과 같다.

- **V2 migration으로 넣음**: 기존 migration 테스트가 그대로 검증한다. 하지만 이 앱의 DB에 쓰지 않는 테이블이 생긴다. 요구사항 0008에서 걷어낸 것과 같은 종류다(사람 확인: 2026-10-19).
- **문서의 SQL 코드 블록으로만 둠**: 파일이 늘지 않는다. 하지만 실행해 확인할 수 없어 기준 migration이 바뀌면 조용히 어긋난다.
- **`backend/src/test/resources/`에 둠**: 테스트가 읽기 쉽다. 하지만 다른 웹의 개발자가 찾기 어렵고, 테스트용 데이터로 잘못 읽힌다.

## 바뀌는 부분

바뀌는 파일과 내용은 다음과 같다. `backend/src/main/`과 `infrastructure/`는 바뀌지 않는다.

| 위치 | 변경 |
|---|---|
| `docs/reference-schema/mariadb/resource-thresholds.sql` | 네 테이블의 MariaDB DDL(새 파일) |
| `docs/reference-schema/postgresql/resource-thresholds.sql` | 같은 논리 schema의 PostgreSQL DDL(새 파일) |
| `backend/src/test/java/kr/co/unidocs/monitoring/ReferenceSchemaIntegrationTest.java` | 새 통합 테스트(`@Tag("integration")`) |
| `docs/INTEGRATION.md` | 앱이 리소스 임계값을 판단하는 규칙, 읽을 series, 참고용 DDL 위치를 담은 절 추가 |
| `docs/ARCHITECTURE.md` | 지켜야 할 경계에 "리소스 임계값은 앱이 판단하고 Alert 규칙으로 만들지 않는다" 추가 |
| `docs/decisions/0008-resource-threshold-in-app.md` | 새 ADR |

### 데이터 모델 변경

이 앱의 Manager Database는 바뀌지 않는다. 참고용 DDL에 다음 네 테이블을 둔다. 작성 규칙(UTC `DATETIME(6)`/`TIMESTAMP(6)`, Boolean 표기, 허용 범위는 요청 검증에서 확인)은 `V1__baseline.sql`과 같다.

- `resource_thresholds`: 감시 Resource당 0~1행이다. 행이 없으면 임계값이 없는 상태다(S2).
  - `monitored_resource_id`: `monitored_resources` 외래 키, `ON DELETE CASCADE`, UNIQUE
  - `warning_percent`, `critical_percent`: NULL이면 그 단계를 판단하지 않는다. 1~100 정수, Warning < Critical
  - `duration_minutes`: 1~60, 처음 값 5
  - `created_at`, `updated_at`
- `server_resource_alert_settings`: 서버당 0~1행이다. 행이 없으면 재발송 주기는 60분이다. `check_notification_settings`와 같은 모양이다.
  - `server_id`: 기본 키, `server_targets` 외래 키, `ON DELETE CASCADE`
  - `repeat_interval_minutes`: 5~1440
  - `created_at`, `updated_at`
- `resource_alert_states`: Resource의 단계마다 넘기 시작했거나 발생 중일 때만 행이 있다. 정상이 되면 행을 지운다.
  - `monitored_resource_id`: `monitored_resources` 외래 키, `ON DELETE CASCADE`
  - `severity`: `WARNING` 또는 `CRITICAL`. `(monitored_resource_id, severity)` UNIQUE
  - `exceeded_since`: 넘기 시작한 시각(NOT NULL)
  - `resource_alert_event_id`: 발생했으면 발생 기록의 id, 대기 중이면 NULL
  - `last_notified_at`: 마지막으로 알림을 보낸 시각, 대기 중이면 NULL
  - `updated_at`
- `resource_alert_events`: 발생 한 번이 한 행이다. Resource나 서버를 지워도 남도록 외래 키를 걸지 않는다(`alert_events`와 같은 이유).
  - `server_id`, `monitored_resource_id`, `resource_type`, `resource_key`, `display_name`: 지운 뒤에도 무엇이 문제였는지 알 수 있게 발생 때의 값을 남긴다
  - `severity`, `threshold_percent`, `usage_percent`: 발생 때의 단계, 임계값, 사용률
  - `fired_at`(NOT NULL), `resolved_at`(복구 전에는 NULL)
  - `created_at`

상태 전이는 다음과 같다. 화살표 옆은 요구사항의 시나리오다.

```text
(행 없음) --넘음--> 대기(exceeded_since) --지속 시간 경과(S3)--> 발생(event id, last_notified_at)
대기 --내려감(S4) 또는 값 없음(S12)--> (행 지움)
발생 --내려감, 값 올림, 단계 끔(S5, S7, S8)--> (행 지움, 기록의 resolved_at)
발생 --값 없음(S12)--> 발생 유지
발생 --재발송 주기 경과(S6)--> 발생(last_notified_at 갱신)
```

### 계약 변경

[INTEGRATION.md](../INTEGRATION.md)에 앱이 리소스 임계값을 판단하는 절을 더한다. 코어와 앱 사이에 오가는 것(HTTP SD, webhook, Prometheus 조회 API, series)은 바뀌지 않는다. 더하는 내용은 요구사항의 S2~S9, S12, S13을 판단 규칙으로 옮긴 것과 참고용 DDL의 링크다.

## 위험과 되돌리기

- 참고용 DDL이 기준 migration과 어긋남: 기준 migration의 `server_targets`, `monitored_resources`를 바꾸면 `ReferenceSchemaIntegrationTest`가 실패한다. 되돌릴 때는 새 파일 셋과 문서의 절을 지우면 되고, 데이터 이전은 없다.
- 도입하는 쪽이 테이블 이름을 바꿈: 참고용 DDL의 외래 키도 함께 바꿔야 한다. INTEGRATION.md의 새 절에 이 점을 쓴다.
- 다른 웹이 판단 규칙을 다르게 만듦: 규칙을 시나리오 단위로 적고, 상태 전이를 DDL 주석과 같은 말로 쓴다.

### 보안 위협

해당 없음. 이 저장소에 새 입력, API, 비밀값, 신뢰 경계가 생기지 않는다. 참고용 DDL에는 인증 정보를 저장하는 열이 없다.

## 테스트 계획

완료 기준마다 확인할 테스트는 다음과 같다. 통합 테스트는 Docker가 있어야 한다.

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | `ReferenceSchemaIntegrationTest`: 두 DB에 기준 migration과 참고용 DDL을 적용하면 네 테이블이 생기고, 두 DB의 테이블·열 이름과 NULL 허용이 같다 | `cd backend && ./gradlew integrationTest --tests '*ReferenceSchemaIntegrationTest'` 통과 |
| C2 | `ReferenceSchemaIntegrationTest`: Resource나 서버를 지우면 설정과 판단 상태만 지워지고 발생 기록은 남는다 | 같은 명령 통과 |
| C3 | 이 앱의 소스가 바뀌지 않음 | `git diff main --stat -- backend/src/main/` 출력 없음 |
| C4 | 문서 검사와 코어 diff | `node scripts/check-docs.mjs` 오류 0, `git diff main --stat -- infrastructure/` 출력 없음, 하위 에이전트 문서 리뷰 |
| C5 | 전체 검증 | DEVELOPMENT.md의 전체 검증 명령 통과 |

## 작업 순서

- [ ] ADR 0008과 이 계획을 승인받는다.
- [ ] `ReferenceSchemaIntegrationTest`를 쓰고, 참고용 SQL이 없어 실패하는 것을 본다(C1, C2).
- [ ] 참고용 SQL 두 파일을 써서 테스트를 통과시킨다.
- [ ] INTEGRATION.md에 판단 규칙 절을, ARCHITECTURE.md에 경계를 더한다(C4).
- [ ] 전체 검증을 실행하고, 새 세션의 하위 에이전트에게 리뷰를 맡긴다(C3~C5).
- [ ] 요구사항을 구현됨으로, ADR 관련 줄에 PR 링크를 쓰고 이 계획을 지운다.

## 진행 중 발견

없음

## 남길 결정

- 리소스 임계값은 앱이 판단한다: [ADR 0008](../decisions/0008-resource-threshold-in-app.md)
