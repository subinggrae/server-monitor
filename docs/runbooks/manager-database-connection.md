# Manager Database 연결

## Runbook 정보

| 항목 | 내용 |
|---|---|
| Runbook ID | `RUN-001` |
| 목적 / 기대 결과 | manager-api가 지정한 Manager Database에 연결되고 Flyway Migration이 적용된 상태 |
| 사용 도구 | Docker Compose, 텍스트 편집기 |
| 필요한 권한 | 배포 서버 shell 접근, Manager Database 계정 생성 권한(외부 DB를 쓸 때) |
| 담당자 | 미정 |
| 마지막 수정일 | 2026-09-28 |
| 에스컬레이션 | 미정 |

## 절차

1. `.env`가 없으면 `.env.example`을 복사합니다.

   ```bash
   cp .env.example .env
   ```

2. DB 위치에 맞게 `.env`의 `SPRING_DATASOURCE_URL`과 `SPRING_DATASOURCE_USERNAME`을 정합니다.
   MariaDB와 PostgreSQL 예시는 `.env.example`에 있습니다.
   URL 안에 비밀번호를 넣지 않습니다.

3. 비밀번호를 `infrastructure/secrets/manager_db_password`에 넣습니다.
   Compose 안의 MariaDB를 쓰면 `manager_db_root_password`도 넣습니다.

4. 실행합니다.

   Compose 안의 MariaDB를 쓸 때:

   ```bash
   docker compose --profile local-db up -d --build
   ```

   외부 DB를 쓸 때:

   ```bash
   docker compose up -d --build
   ```

5. Backend 상태를 확인합니다.

   ```bash
   curl -s http://localhost:8080/actuator/health
   ```

## 오류 처리

- `docker compose`가 `SPRING_DATASOURCE_URL이 필요합니다`로 멈춤 → `.env`에 `SPRING_DATASOURCE_URL`과 `SPRING_DATASOURCE_USERNAME`이 있는지 확인합니다.
- 인증 실패로 기동하지 않음 → `manager_db_password` 파일 값과 DB 계정 비밀번호가 같은지 확인합니다.
  compose나 shell에서 `SPRING_DATASOURCE_PASSWORD`를 넘기고 있지 않은지도 확인합니다. 환경변수가 secret 파일을 덮어씁니다.
- Flyway 또는 Schema validate 실패 → 이미 적용된 Migration 파일이 바뀌지 않았는지, 두 제품 디렉터리에 같은 version이 있는지 확인합니다.
- 호스트에서 `manager-db`에 붙을 수 없음 → `MANAGER_DB_PORT`(기본 13306)로 접속합니다. 3306이 아닙니다.
- 해결할 수 없는 경우 → 미정
