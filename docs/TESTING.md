# 테스트

이 프로젝트에서 무엇을 어떤 테스트로 검증하는지 정한다. 테스트를 쓰거나 고치기 전에 읽는다.

## 원칙

- 테스트의 가치는 개수가 아니라 "실패했을 때 알고 싶은 회귀를 알려주는가"로 판단한다. 커버리지는 참고 지표이고 목표가 아니다.
- 사용자가 쓰는 방식에 가까운 테스트를 우선한다. 내부 상태, private 함수, 호출 횟수처럼 구현 세부를 검사하는 테스트는 리팩터링만 해도 깨진다.
- 하나의 동작은 그 동작을 확인할 수 있는 가장 낮은 수준에서 한 번만 검사한다.
- 상위 수준 테스트가 잡은 버그를 하위 수준 테스트가 놓쳤다면 하위 수준 테스트를 추가한다.
- 테스트를 지우거나, 건너뛰게 하거나, 기대값을 느슨하게 바꿔서 통과시키지 않는다. 테스트가 틀렸다고 판단하면 이유를 보고한다.

## 테스트 범위

![테스트 종류별로 검증하는 범위](assets/test-scope.svg)

화면에만 있는 규칙은 컴포넌트 테스트가, 화면과 API의 계약은 E2E 테스트가 맡는다. 도구를 고른 이유는 [ADR 0014](decisions/0014-frontend-test-tools.md)에 있다.

## 종류별 기준

| 종류 | 책임 | 쓰는 경우 | 쓰지 않는 경우 | 명령 |
|---|---|---|---|---|
| 정적 검사 | 타입 오류, 문법 오류, 금지 패턴 | 항상. 별도 테스트를 쓰지 않는다 | 해당 없음 | `cd frontend && npm run lint`, `cd frontend && npm run build` |
| 단위 | 분기가 있는 순수 로직: 계산, 변환, 검증 규칙, 외부 HTTP 호출 처리 | 조건이 둘 이상이거나 경계값이 있는 로직. 예: 암호화, 설정 바인딩, 메시지 형식, Telegram·Prometheus client | 단순 전달, getter, 프레임워크 설정, mock만 검사하게 되는 코드 | `scripts/verify fast` |
| 컴포넌트 | 화면에만 있는 규칙과 API 호출 처리. API 응답은 테스트가 만든다 | 입력에 따라 칸이나 보내는 값이 바뀌는 화면, Backend 오류를 보여 주는 방식, `lib/api.ts`의 요청·오류 처리 | 분기 없이 값을 그대로 보여 주는 화면, 스타일과 배치 | `cd frontend && npm test` |
| 통합 | 실제 Manager Database와 함께 동작하는 API: 저장·조회, migration, SD 응답, webhook | API 엔드포인트, schema, 도메인 경계, 인증 정보 비노출 | 단위 테스트로 충분한 순수 로직 | `scripts/verify all` 또는 `cd backend && ./gradlew test` |
| E2E | 화면과 API의 계약. 실제 Backend와 빈 Manager Database에 브라우저로 연결한다 | 운영자가 매일 쓰는 핵심 흐름. 지금은 Check 관리, Server SNMP 설정, Telegram 그룹과 알림 연결 | 컴포넌트·통합 테스트로 확인할 수 있는 분기와 예외. 수집 상태(OK·CRITICAL)와 알림 전송 | `cd frontend && npm run test:e2e` |

스냅숏 테스트는 오류 메시지나 직렬화 결과처럼 짧고 리뷰할 수 있는 출력에만 쓴다.

### 두 DB 제품에서 같은 검증

통합 테스트는 Testcontainers로 MariaDB와 PostgreSQL container를 각각 띄워 같은 검증을 실행한다. 한쪽에서만 동작하는 SQL을 쓰거나 migration을 한쪽에만 추가하면 다른 쪽 테스트가 실패한다. 그래서 Docker가 필요하다.

- 검증 내용은 `AbstractManagerDatabaseTest` → `AbstractMonitoringApiTest` → `AbstractNotificationApiTest` → `AbstractSnmpApiTest`로 이어지는 abstract class에 쓴다.
- `MariaDbManagerDatabaseTest`와 `PostgresManagerDatabaseTest`가 이 class를 상속하고 DB container만 바꾼다.
- 새 통합 테스트는 해당 영역의 abstract class에 추가한다. 두 제품에서 자동으로 함께 돈다.

### 컴포넌트 테스트

Vitest와 jsdom에서 Testing Library로 화면을 그리고, API 응답은 MSW로 만든다. Docker와 Backend가 필요 없다.

- 테스트가 처리하지 않은 요청을 보내면 그 테스트는 실패한다. 화면이 부르는 API는 모두 테스트에서 응답을 정한다.
- 요소는 사용자가 보는 이름(label, role, 문구)으로 찾는다. CSS class나 component 내부 상태로 찾지 않는다.
- 공통 준비 코드는 `frontend/src/test/`에 있다. 화면은 `renderWithProviders`로 그린다.
- jsdom은 실제 브라우저가 아니다. layout, focus 이동처럼 브라우저에 기대는 동작은 E2E에서 확인한다.

### E2E 테스트

Playwright가 `frontend/e2e/compose.yml`로 E2E 전용 MariaDB, Backend, Frontend container를 띄우고 Chromium으로 흐름을 실행한다. 끝나면 결과와 관계없이 container를 내린다.

- 실행마다 빈 Database로 시작한다. 흐름은 이름에 고유 값을 붙여 실행 순서와 관계없이 통과하게 쓴다.
- 운영용 `compose.yml`과 secret 파일을 쓰지 않는다. Telegram, Prometheus, exporter에는 연결하지 않는다. 그래서 E2E에서 Bot Token이 없다는 안내와 수집 상태 UNKNOWN이 보이는 것은 정상이다.
- 개발용 구성과 Compose project 이름, image tag, port가 겹치지 않는다. port를 바꾸는 방법은 [DEVELOPMENT.md의 명령](DEVELOPMENT.md#명령)에 있다.
- 고정 시간 대기와 재시도를 쓰지 않는다. 저장이 끝나기를 기다려야 하면 화면 문구나 API 응답을 기다린다.
- 실패하면 screenshot과 trace가 `frontend/test-results/`에 남는다. `npx playwright show-trace <trace.zip>`으로 연다.
- 실행이 중간에 끊겨 container가 남았다면 다음 실행이 먼저 지운다. 직접 지울 때는 `docker compose -p monitoring-manager-e2e down -v`를 쓴다.

### 검증 profile

`scripts/verify`가 검증 진입점이다.

| profile | 실행하는 것 | Docker |
|---|---|---|
| `fast` | Frontend lint, 컴포넌트 테스트, build, root package를 뺀 Backend 하위 package 테스트 전체 | 필요 없다 |
| `all` | Frontend lint, 컴포넌트 테스트, build, `./gradlew build`(통합 테스트 포함), E2E 테스트 | 필요하다. E2E용 Chromium도 필요하다 |

root package(`kr.co.unidocs.monitoring`)의 테스트만 Testcontainers로 Docker를 쓴다. 하위 package의 테스트는 Docker 없이 돈다. `fast`는 이 차이로 테스트를 고른다.

## 버그 수정과 회귀 테스트

버그를 고칠 때마다 테스트를 추가하지는 않는다. 다음 질문으로 판단한다.

> 같은 문제가 다시 생겼을 때 자동으로 발견할 가치가 있는가?

- **추가한다**: 로직 오류, 경계값 오류, 데이터 처리 오류처럼 코드가 바뀌면 다시 생길 수 있는 문제. 버그를 재현하는 테스트를 먼저 쓰고, 고치기 전에 실패하는지 확인한 뒤 고친다.
- **추가하지 않는다**: 오타, 문구 변경, 일회성 설정 실수, 외부 서비스 장애처럼 코드로 재발을 막을 수 없거나 막을 가치가 없는 문제.
- 추가하지 않았다면 이유를 결과 보고에 한 줄로 적는다.

## 위치와 이름

- Backend 테스트는 `backend/src/test/java/` 아래, 대상 class와 같은 package에 `*Test.java`로 둔다. Docker가 필요한 통합 테스트는 root package에 둔다.
- Frontend 컴포넌트 테스트는 대상 파일 옆에 `*.test.ts`나 `*.test.tsx`로 둔다. `describe`에는 대상 이름을 쓴다.
- E2E 테스트는 `frontend/e2e/`에 흐름별 `*.spec.ts`로 둔다.
- 테스트 method와 `it`·`test` 이름은 검사하는 동작을 영어 camelCase 문장으로 쓴다. 예: `neverLeaksBotTokenInErrorMessage`, `sendsExistingTargetIdsWhenEditing`

## CI

CI 없음. 공유 반영 전에 로컬에서 `scripts/verify all`로 전체 테스트를 실행한다.
