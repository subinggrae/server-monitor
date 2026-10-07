# 0013. Manager Database를 제품별 Flyway Migration으로 이식 가능하게 둔다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md 규칙 7, 8), [TESTING.md의 두 DB 제품에서 같은 검증](../TESTING.md#두-db-제품에서-같은-검증)

## 배경과 문제

Manager Database를 어디에 둘지 정하지 않았다. Docker Compose 안의 MariaDB, AWS RDS MariaDB, AWS RDS PostgreSQL, 사내 DB 모두 가능해야 한다.

MariaDB와 PostgreSQL은 기본키 생성 방식 같은 DDL이 다르다.

## 검토한 선택지

- **한 제품을 정하고 그 제품의 기능을 쓴다**: Migration이 한 벌이다 / 다른 제품으로 옮기려면 코드와 schema를 고쳐야 한다. 단순함과 배포 선택지를 맞바꾼다.
- **Hibernate `ddl-auto=update`로 schema를 만든다**: Migration을 작성하지 않아도 된다 / schema 변경을 통제할 수 없고 운영 데이터가 위험하다. 편의와 안전성을 맞바꾼다.
- **제품별 Flyway Migration을 두고 `ddl-auto=validate`만 쓴다**: 제품을 바꿔도 애플리케이션 코드는 그대로다. Entity와 schema가 어긋나면 기동할 때 실패한다 / Migration을 두 벌 관리한다. 작성 비용이 두 배다.

## 결정

제품별 Flyway Migration을 두고 `ddl-auto=validate`만 쓴다.

- 연결 정보는 환경 변수와 secret 파일로 받는다. `application.yml`에 DB 주소를 적지 않는다.
- MariaDB와 PostgreSQL JDBC Driver를 모두 포함한다.
- Migration은 `db/migration/mariadb/`와 `db/migration/postgresql/`에 둔다. `spring.flyway.locations`의 `{vendor}` 자리표시자로 JDBC URL에 맞는 디렉터리를 고른다.
- 특정 제품에만 있는 SQL 기능을 업무 로직에 쓰지 않는다.

## 결과

- 좋아지는 점: `.env`의 JDBC URL만 바꿔 DB를 옮길 수 있다.
- 감수할 점: Migration은 항상 두 곳에 함께 추가해야 한다. 이미 적용된 Migration은 고치지 않고 새 version을 만든다.
- 확신도: 높음. `MariaDbManagerDatabaseTest`와 `PostgresManagerDatabaseTest`가 두 제품에서 같은 검증을 실행한다.

## 확인 방법

원래 기록에 없다.
