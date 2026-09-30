# ADR-0013: Manager Database를 제품별 Flyway Migration으로 이식 가능하게 둔다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

Manager Database를 어디에 둘지 정하지 않았습니다.
Docker Compose 안의 MariaDB, AWS RDS MariaDB, AWS RDS PostgreSQL, 사내 DB 모두 가능해야 합니다.

MariaDB와 PostgreSQL은 기본키 생성 방식 같은 DDL이 다릅니다.

## Options Considered

### Option 1: 한 제품을 정하고 그 제품의 기능을 쓴다

- 장점: Migration이 한 벌입니다.
- 단점: 다른 제품으로 옮기려면 코드와 Schema를 고쳐야 합니다.
- 주요 trade-off: 단순함과 배포 선택지를 맞바꿉니다.

### Option 2: Hibernate `ddl-auto=update`로 Schema를 만든다

- 장점: Migration을 작성하지 않아도 됩니다.
- 단점: Schema 변경을 통제할 수 없고 운영 데이터가 위험합니다.
- 주요 trade-off: 편의와 안전성을 맞바꿉니다.

### Option 3: 제품별 Flyway Migration을 두고 `ddl-auto=validate`만 쓴다

- 장점: 제품을 바꿔도 애플리케이션 코드는 그대로입니다. Entity와 Schema가 어긋나면 기동할 때 실패합니다.
- 단점: Migration을 두 벌 관리합니다.
- 주요 trade-off: 작성 비용이 두 배입니다.

## Decision

Option 3을 선택합니다.

- 연결 정보는 환경변수와 secret 파일로 받습니다. `application.yml`에 DB 주소를 적지 않습니다.
- MariaDB와 PostgreSQL JDBC Driver를 모두 포함합니다.
- Migration은 `db/migration/mariadb/`와 `db/migration/postgresql/`에 둡니다.
  `spring.flyway.locations`의 `{vendor}` 자리표시자로 JDBC URL에 맞는 디렉터리를 고릅니다.
- 특정 제품에만 있는 SQL 기능을 업무 로직에 쓰지 않습니다.

## Consequences

- 긍정적인 영향: `.env`의 JDBC URL만 바꿔 DB를 옮길 수 있습니다.
- 부정적인 영향 / 감수하는 trade-off: Migration은 항상 두 곳에 함께 추가해야 합니다.
  이미 적용된 Migration은 고치지 않고 새 version을 만듭니다.
- 향후 고려사항: 없음

## Confidence

High

`MariaDbManagerDatabaseTest`와 `PostgresManagerDatabaseTest`가 두 제품에서 같은 검증을 실행합니다.

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 7, 8
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "Manager Database 이식성"
- 관련 ADR: 없음
