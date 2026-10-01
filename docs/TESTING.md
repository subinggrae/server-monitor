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

Frontend에는 아직 자동 테스트가 없다. 화면 동작은 정적 검사와 실제 화면 확인으로만 검증한다. 컴포넌트 테스트와 E2E 테스트를 도입할지는 아직 정하지 않았다. 도구와 대상 흐름을 정해야 한다.

## 종류별 기준

| 종류 | 책임 | 쓰는 경우 | 쓰지 않는 경우 | 명령 |
|---|---|---|---|---|
| 정적 검사 | 타입 오류, 문법 오류, 금지 패턴 | 항상. 별도 테스트를 쓰지 않는다 | 해당 없음 | `cd frontend && npm run lint`, `cd frontend && npm run build` |
| 단위 | 분기가 있는 순수 로직: 계산, 변환, 검증 규칙, 외부 HTTP 호출 처리 | 조건이 둘 이상이거나 경계값이 있는 로직. 예: 암호화, 설정 바인딩, 메시지 형식, Telegram·Prometheus client | 단순 전달, getter, 프레임워크 설정, mock만 검사하게 되는 코드 | `scripts/verify fast` |
| 통합 | 실제 Manager Database와 함께 동작하는 API: 저장·조회, migration, SD 응답, webhook | API 엔드포인트, schema, 도메인 경계, 인증 정보 비노출 | 단위 테스트로 충분한 순수 로직 | `scripts/verify all` 또는 `cd backend && ./gradlew test` |

스냅숏 테스트는 오류 메시지나 직렬화 결과처럼 짧고 리뷰할 수 있는 출력에만 쓴다.

### 두 DB 제품에서 같은 검증

통합 테스트는 Testcontainers로 MariaDB와 PostgreSQL container를 각각 띄워 같은 검증을 실행한다. 한쪽에서만 동작하는 SQL을 쓰거나 migration을 한쪽에만 추가하면 다른 쪽 테스트가 실패한다. 그래서 Docker가 필요하다.

- 검증 내용은 `AbstractManagerDatabaseTest` → `AbstractMonitoringApiTest` → `AbstractNotificationApiTest` → `AbstractSnmpApiTest`로 이어지는 abstract class에 쓴다.
- `MariaDbManagerDatabaseTest`와 `PostgresManagerDatabaseTest`가 이 class를 상속하고 DB container만 바꾼다.
- 새 통합 테스트는 해당 영역의 abstract class에 추가한다. 두 제품에서 자동으로 함께 돈다.

### 검증 profile

`scripts/verify`가 검증 진입점이다.

| profile | 실행하는 것 | Docker |
|---|---|---|
| `fast` | Frontend lint와 build, Backend의 `common`, `config`, `notification`, `prometheus` package 테스트 | 필요 없다 |
| `all` | Frontend lint와 build, `./gradlew build`(통합 테스트 포함) | 필요하다 |

root package(`kr.co.unidocs.monitoring`)의 테스트만 Testcontainers로 Docker를 쓴다. 하위 package의 테스트는 Docker 없이 돈다. `fast`는 이 차이로 테스트를 고른다.

## 버그 수정과 회귀 테스트

버그를 고칠 때마다 테스트를 추가하지는 않는다. 다음 질문으로 판단한다.

> 같은 문제가 다시 생겼을 때 자동으로 발견할 가치가 있는가?

- **추가한다**: 로직 오류, 경계값 오류, 데이터 처리 오류처럼 코드가 바뀌면 다시 생길 수 있는 문제. 버그를 재현하는 테스트를 먼저 쓰고, 고치기 전에 실패하는지 확인한 뒤 고친다.
- **추가하지 않는다**: 오타, 문구 변경, 일회성 설정 실수, 외부 서비스 장애처럼 코드로 재발을 막을 수 없거나 막을 가치가 없는 문제.
- 추가하지 않았다면 이유를 결과 보고에 한 줄로 적는다.

## 위치와 이름

- Backend 테스트는 `backend/src/test/java/` 아래, 대상 class와 같은 package에 `*Test.java`로 둔다. Docker가 필요한 통합 테스트는 root package에 둔다.
- Frontend 테스트 위치는 아직 정하지 않았다. 테스트 도구를 정할 때 함께 정해야 한다.
- 테스트 method 이름은 검사하는 동작을 영어 camelCase 문장으로 쓴다. 예: `neverLeaksBotTokenInErrorMessage`, `returnsUnavailableWhenPrometheusFails`

## CI

CI 없음. 공유 반영 전에 로컬에서 `scripts/verify all`로 전체 테스트를 실행한다.
