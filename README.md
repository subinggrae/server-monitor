# Monitoring Manager

Prometheus 기반 모니터링을 웹 UI에서 관리하는 도구입니다.
운영자가 YAML이나 PromQL을 직접 고치지 않고 가용성 Check, 서버 SNMP Resource, Telegram 알림을 화면에서 설정합니다.

## 시작하기

### 요구 환경

- JDK 21 (Gradle은 Wrapper를 씁니다)
- Node.js 22
- Docker와 Docker Compose v2
- Bash (`scripts/verify` 실행용. Windows에서는 Git Bash)
- Manager Database: Compose의 `local-db` profile MariaDB, 또는 외부 MariaDB / PostgreSQL

### 설치

```text
cp .env.example .env

cd infrastructure/secrets
for name in manager_db_password manager_db_root_password app_secret_key \
            telegram_bot_token telegram_webhook_secret alertmanager_webhook_token; do
  cp "$name.example" "$name"
done

cd ../../frontend
npm ci
```

복사한 secret 파일에 실제 값을 넣어 주세요.
각 파일의 용도는 [infrastructure/README.md](infrastructure/README.md)에 있습니다.

### 실행

전체 구성을 Docker로 띄웁니다. `local-db`는 개발용 MariaDB를 함께 띄우는 profile입니다.

```text
docker compose --profile local-db up -d --build
```

| 주소 | 설명 |
| --- | --- |
| http://localhost:8081 | Monitoring Manager 화면 |
| http://localhost:8080/actuator/health | Backend 상태 |
| http://localhost:9090 | Prometheus |
| http://localhost:9093 | Alertmanager |

모든 포트는 `127.0.0.1`에만 열립니다.

Backend와 Frontend를 따로 띄울 때는 다음과 같이 실행합니다.

```text
cd backend
SPRING_DATASOURCE_URL=jdbc:mariadb://localhost:13306/monitoring_manager \
SPRING_DATASOURCE_USERNAME=manager \
SPRING_DATASOURCE_PASSWORD=... \
APP_SECRET_KEY=local-dev-secret-key \
./gradlew bootRun

cd frontend
npm run dev
```

개발 서버는 http://localhost:5173 에서 뜨고, `/api` 요청을 http://localhost:8080 으로 넘깁니다.
Backend 주소는 `VITE_API_PROXY_TARGET`으로 바꿀 수 있습니다.

## 검증

지원되는 검증 확인:

```text
scripts/verify list
```

빠른 로컬 검증:

```text
scripts/verify fast
```

완료 전 전체 필수 검증:

```text
scripts/verify all
```

`fast`는 Frontend lint와 build, Docker가 필요 없는 Backend 테스트를 실행합니다.
`all`은 `./gradlew build`까지 실행합니다.
Manager Database 테스트가 Testcontainers로 MariaDB와 PostgreSQL을 띄우므로 Docker가 필요합니다.

## 프로젝트 구조

```text
backend/          Spring Boot 애플리케이션 (manager-api). 설정 저장, HTTP SD, Alert 수신, Telegram 전송
frontend/         React SPA (manager-web)
infrastructure/   Prometheus, Alertmanager, Blackbox, SNMP 배포용 설정과 secret 파일
docs/             Architecture, Design, ADR, Runbook, 문서 작성 규칙
scripts/verify    검증 진입점
compose.yml       전체 Docker Compose 구성
```

## 관련 문서

- Agent 작업 안내: [AGENTS.md](AGENTS.md)
- 개발 규칙: [docs/rules/documentation-ko.md](docs/rules/documentation-ko.md) (문서 작성)
- Architecture: [docs/architecture/architecture.md](docs/architecture/architecture.md)
- Design: [docs/design/design.md](docs/design/design.md)
- Decisions / ADR: [docs/decisions/ADR-decisions/](docs/decisions/ADR-decisions/)
- 운영 / 배포: [docs/runbooks/](docs/runbooks/), secret 파일은 [infrastructure/README.md](infrastructure/README.md)

## 도움 / 관리

- 담당 팀 또는 문의 위치: 미정
