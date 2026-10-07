# 개발 가이드

개발 환경을 준비하고, 코드를 쓰고, 변경을 검증하고, 리뷰를 거쳐 저장소에 반영하는 방법을 설명한다. 사람과 AI가 같은 절차를 따른다. AI에게만 해당하는 규칙은 [AGENTS.md](../AGENTS.md)에 있다.

## 준비

- 필요한 도구
  - JDK 21. Gradle은 `backend/gradlew` Wrapper를 쓴다. 셸의 `JAVA_HOME`이 다른 JDK를 가리키면 Gradle이 실행되지 않으므로 JDK 21로 맞춘다.
  - Node.js 22.13 이상. jsdom 29가 요구한다([ADR 0014](decisions/0014-frontend-test-tools.md)). 패키지 관리자는 npm이고 `frontend/package-lock.json`을 커밋한다.
  - Docker와 Docker Compose v2. 전체 구성 실행, Testcontainers 테스트, E2E 테스트에 필요하다
  - Bash. `scripts/verify`를 실행한다. Windows에서는 Git Bash를 쓴다
- 런타임 버전을 정한 파일: JDK는 `backend/build.gradle`의 Java toolchain(21)이다. Node.js는 아직 없다. `frontend/Dockerfile`은 `node:22-alpine`을 쓴다. 버전 파일을 만든다.
- Manager Database: Compose의 `local-db` profile MariaDB, 또는 외부 MariaDB·PostgreSQL
- 환경 변수: `.env.example`을 `.env`로 복사한다. 변수 이름과 예시는 `.env.example`에 있다. Compose로 띄우려면 `SPRING_DATASOURCE_URL`과 `SPRING_DATASOURCE_USERNAME`이 있어야 한다.

다음 명령으로 환경 변수 파일과 secret 파일을 복사하고 Frontend 의존성과 E2E용 Chromium을 설치한다.

```sh
cp .env.example .env

cd infrastructure/secrets
for name in manager_db_password manager_db_root_password app_secret_key \
            telegram_bot_token telegram_webhook_secret alertmanager_webhook_token; do
  cp "$name.example" "$name"
done

cd ../../frontend
npm ci
npm run test:e2e:install
```

`npm run test:e2e:install`은 E2E 테스트용 Chromium을 내려받는다. 처음 한 번, 그리고 `@playwright/test` version이 바뀌었을 때 실행한다. 인터넷 연결이 필요하다.

복사한 secret 파일에는 실제 값을 넣는다.

### 비밀 파일

노출되면 안 되는 값(비밀번호, token, 암호화 key)만 secret으로 둔다. 주소, port, timezone, DB 이름, 계정 이름은 노출돼도 문제가 없으므로 `.env`에 둔다.

`infrastructure/secrets/` 아래 실제 값 파일은 Git에 올리지 않는다. `.gitignore`가 `*.example`만 허용한다. 각 파일이 어느 container의 어떤 property로 들어가는지는 `compose.yml`의 `secrets`가 기준이다.

| 파일 | 쓰는 곳 | container 안 이름 | 용도 |
|---|---|---|---|
| `manager_db_password` | `manager-api`, `manager-db` | `spring.datasource.password` / `manager_db_password` | Manager Database 비밀번호(두 container가 같은 파일을 씀) |
| `manager_db_root_password` | `manager-db` | `manager_db_root_password` | `local-db` profile의 DB 관리자 비밀번호 |
| `app_secret_key` | `manager-api` | `monitoring-manager.secret-key` | 인증 정보를 암호화하는 key(바꾸면 저장된 값을 읽을 수 없음) |
| `telegram_bot_token` | `manager-api` | `monitoring-manager.telegram.bot-token` | Telegram Bot API 호출 |
| `telegram_webhook_secret` | `manager-api` | `monitoring-manager.telegram.webhook-secret` | Telegram webhook 요청 검증 |
| `alertmanager_webhook_token` | `manager-api`, `alertmanager` | `monitoring-manager.notification.alertmanager-webhook-token` / `alertmanager_webhook_token` | Alertmanager가 Manager webhook을 부를 때 쓰는 Bearer token(두 container가 같은 파일을 씀) |

비밀이 들어가는 방식은 실행 환경마다 다르다.

```text
운영   Docker Secret       container 안 /run/secrets/<target> 파일로 들어간다
개발   환경 변수 fallback   파일이 없으면 .env나 shell 환경 변수 값을 쓴다
```

- `application.yml`의 `spring.config.import: optional:configtree:/run/secrets/`가 `/run/secrets` 아래 파일을 설정값으로 읽는다. 파일 이름이 곧 property 이름이다.
- config tree는 `application.yml`보다 우선한다. 그래서 `APP_SECRET_KEY`처럼 `application.yml`이 받는 환경 변수는 파일이 있으면 파일 값에 진다.
- OS 환경 변수 `SPRING_DATASOURCE_PASSWORD`는 config tree보다 우선한다. 빈 값이라도 넘기면 secret 파일을 덮어쓴다. 그래서 `compose.yml`은 이 변수를 넘기지 않는다.
- Docker 없이 Backend를 띄우면 비밀을 환경 변수(`SPRING_DATASOURCE_PASSWORD`, `APP_SECRET_KEY` 등)로 넣는다.
- Telegram 그룹의 chat id는 secret 파일에 두지 않는다. 알림 대상은 화면에서 등록한다.
- 새 secret은 `infrastructure/secrets/`에 `.example`과 함께 추가하고, `compose.yml`의 `secrets`에 target 이름을 property 이름으로 적는다.

## 명령

| 목적 | 명령 | 언제 쓰는가 |
|---|---|---|
| 빠른 검증 | `scripts/verify fast` | 커밋 전(Frontend 린트·컴포넌트 테스트·빌드와 Docker가 필요 없는 Backend 테스트) |
| 전체 검증 | `scripts/verify all` | 끝내기 전, 푸시와 병합 직전(빠른 검증의 Frontend 단계, `./gradlew build`, E2E 테스트) |
| 검증 profile 목록 | `scripts/verify list` | 지원하는 profile을 확인할 때 |
| 테스트 하나(Frontend) | `cd frontend && npx vitest run src/lib/api.test.ts` | 작업하는 동안 바꾼 부분만 확인할 때 |
| 테스트 하나(Backend) | `cd backend && ./gradlew test --tests 'kr.co.unidocs.monitoring.notification.AlertMessageFormatterTest'` | 작업하는 동안 바꾼 부분만 확인할 때 |
| Frontend 의존성 설치 | `cd frontend && npm ci` | 처음 받았을 때, `package-lock.json`이 바뀌었을 때 |
| 전체 실행 (개발용 DB 포함) | `docker compose --profile local-db up -d --build` | Compose 안의 MariaDB로 전체 구성을 띄울 때 |
| 전체 실행 (외부 DB) | `docker compose up -d --build` | `.env`에 외부 Manager Database를 지정했을 때 |
| Backend 개발 서버 | `cd backend && ./gradlew bootRun` | Backend만 따로 띄울 때([필요한 환경 변수](#backend와-frontend를-따로-실행)) |
| Frontend 개발 서버 | `cd frontend && npm run dev` | 화면을 직접 확인할 때(http://localhost:5173) |
| Frontend 빌드 결과 미리 보기 | `cd frontend && npm run preview` | 빌드한 화면을 확인할 때 |
| SNMP 테스트 장비 | `docker compose --profile snmp-test up -d snmp-test-agent` | 개발 환경에서 SNMP 흐름을 확인할 때(운영에서는 띄우지 않음) |
| Backend 테스트 | `cd backend && ./gradlew test` | Backend 테스트만 돌릴 때([종류별 기준](TESTING.md#종류별-기준)) |
| Frontend 컴포넌트 테스트 | `cd frontend && npm test` | 화면 코드를 고쳤을 때(Docker 필요 없음) |
| E2E 테스트 | `cd frontend && npm run test:e2e` | 화면과 API 흐름을 확인할 때(Docker 필요) |
| E2E용 Chromium 설치 | `cd frontend && npm run test:e2e:install` | 처음 한 번, `@playwright/test` version이 바뀌었을 때 |
| Frontend 린트·타입 검사 | `cd frontend && npm run lint`(타입 검사는 빌드에 포함) | 커밋 전(oxlint) |
| Frontend 빌드 | `cd frontend && npm run build` | 커밋 전(`tsc -b` 후 `vite build`) |
| Backend 빌드 | `cd backend && ./gradlew build` | 빌드 설정이나 의존성을 바꿨을 때(테스트도 함께 돎) |
| 문서 검사 | `node scripts/check-docs.mjs` | 문서를 바꿨을 때(그림을 바꿨다면 `--render`를 붙여서) |

- `scripts/verify all`과 E2E 테스트에는 Docker와 E2E용 Chromium이 필요하다.
- E2E 테스트의 첫 실행은 image build 때문에 몇 분 걸린다. 화면을 `127.0.0.1:18081`에 띄우며, 이 port가 이미 쓰이면 환경 변수 `E2E_WEB_PORT`로 바꾼다.
- `scripts/verify`는 `frontend/node_modules`가 없으면 멈추고 `npm ci`를 먼저 실행하라고 알린다.

Compose로 띄운 뒤 쓰는 주소는 다음과 같다. 모든 port는 `127.0.0.1`에만 열린다.

| 주소 | 설명 |
|---|---|
| http://localhost:8081 | Monitoring Manager 화면 |
| http://localhost:8080/actuator/health | Backend 상태 |
| http://localhost:9090 | Prometheus |
| http://localhost:9093 | Alertmanager |

### 명령 이름

새 명령을 더할 때는 다음 이름을 쓴다. 이미 있는 이름은 바꾸지 않는다. 사람의 습관과 `scripts/verify`가 그 이름에 기대고 있다.

| 목적 | npm 스크립트 | Gradle |
|---|---|---|
| 전체 검증 | `check` | `check` |
| 테스트 | `test` | `test` |
| 종류별 테스트 | `test:unit`, `test:integration`, `test:e2e` | `integrationTest` |
| watch 모드로 다시 실행 | `test:watch` | 없음 |
| 린트, 타입 검사 | `lint`, `typecheck` | 없음 |
| 빌드 | `build` | `build` |

- 전체 검증은 정적 검사, 단위 테스트, 컴포넌트 테스트를 차례로 실행한다. E2E처럼 브라우저나 서버가 필요한 검사는 넣지 않고 따로 실행한다. 이 저장소의 `scripts/verify all`은 E2E까지 실행하고, E2E 없이 도는 것은 `scripts/verify fast`다.
- 테스트 명령은 한 번 실행하고 끝나야 한다. 기준은 [TESTING.md의 결정적 실행](TESTING.md#결정적-실행)에 있다.
- 여러 명령을 묶는 명령은 pre·post 훅에 기대지 않고 명시적으로 차례대로 실행한다. 패키지 관리자마다 훅을 실행하는지가 다르다.
- 기존 명령을 묶기만 하는 명령을 더하는 일이 위험 영역인지는 [AGENTS.md의 작업 흐름](../AGENTS.md#작업-흐름)을 따른다.

### Backend와 Frontend를 따로 실행

다음 명령으로 Backend와 Frontend 개발 서버를 따로 띄운다.

```sh
cd backend
SPRING_DATASOURCE_URL=jdbc:mariadb://localhost:13306/monitoring_manager \
SPRING_DATASOURCE_USERNAME=manager \
SPRING_DATASOURCE_PASSWORD=... \
APP_SECRET_KEY=local-dev-secret-key \
./gradlew bootRun

cd frontend
npm run dev
```

개발 서버는 `/api` 요청을 http://localhost:8080 으로 넘긴다. Backend 주소는 `VITE_API_PROXY_TARGET`으로 바꾼다.

### Manager Database 연결

`manager-api`를 지정한 Manager Database에 연결하고 Flyway migration을 적용하는 절차다. 외부 DB를 쓰면 DB 계정을 만들 권한이 필요하다.

1. `.env`가 없으면 `.env.example`을 복사한다.
2. DB 위치에 맞게 `.env`의 `SPRING_DATASOURCE_URL`과 `SPRING_DATASOURCE_USERNAME`을 정한다. MariaDB와 PostgreSQL 예시는 `.env.example`에 있다. URL 안에 비밀번호를 넣지 않는다.
3. 비밀번호를 `infrastructure/secrets/manager_db_password`에 넣는다. Compose 안의 MariaDB를 쓰면 `manager_db_root_password`도 넣는다.
4. Compose 안의 MariaDB를 쓰면 `docker compose --profile local-db up -d --build`, 외부 DB를 쓰면 `docker compose up -d --build`로 실행한다.
5. `curl -s http://localhost:8080/actuator/health`로 Backend 상태를 확인한다.

| 증상 | 확인할 것 |
|---|---|
| `docker compose`가 `SPRING_DATASOURCE_URL이 필요합니다`로 멈춤 | `.env`에 `SPRING_DATASOURCE_URL`과 `SPRING_DATASOURCE_USERNAME`이 있는지 확인한다 |
| 인증 실패로 기동하지 않음 | `manager_db_password` 파일 값과 DB 계정 비밀번호가 같은지, compose나 shell에서 `SPRING_DATASOURCE_PASSWORD`를 넘기지 않는지 확인한다 |
| Flyway 또는 schema validate 실패 | 이미 적용된 migration 파일이 바뀌지 않았는지, 두 제품 디렉터리에 같은 version이 있는지 확인한다 |
| host에서 `manager-db`에 붙을 수 없음 | 3306이 아니라 `MANAGER_DB_PORT`(기본 13306)로 접속한다 |

환경 변수 `SPRING_DATASOURCE_PASSWORD`는 secret 파일을 덮어쓴다. 해결할 수 없을 때 연락할 곳은 아직 정하지 않았다. 담당 팀을 정해야 한다.

## 코드 위치

디렉터리별 책임은 [ARCHITECTURE.md의 코드 지도](ARCHITECTURE.md#코드-지도)에 있다.

## 코드 작성

다음에 코드를 고칠 사람이 빨리 읽고 안전하게 고칠 수 있게 쓴다. 읽는 사람이 빨리 이해하지 못하는 코드가 복잡한 코드다. 리뷰도 이 기준으로 한다.

- 지금 요구사항만 푼다. 요청하지 않은 기능, 설정, 옵션을 더하지 않는다. 앞으로 필요할지 모르는 경우에 대비해 추상화하지 않고, 한 번만 쓰는 helper나 래퍼를 만들지 않는다.
- 입력 검증은 사용자 입력과 외부 API 응답처럼 신뢰 경계에서 한다. 내부 코드와 프레임워크가 보장하는 값에는 일어날 수 없는 경우를 위한 방어 코드를 더하지 않는다.
- 새 함수, 컴포넌트, 유틸리티를 만들기 전에 같은 일을 하는 코드가 이미 있는지 찾는다. 있으면 그 코드를 쓴다. 그대로 쓰려면 기존 코드를 고쳐야 할 때, 그 코드가 요구사항 범위 안이면 동작 변경보다 먼저 리팩터링 커밋으로 나눠 고친다. 범위 밖이면 복사해 쓰지 않고 작업을 맡긴 사람에게 묻는다.
- 다른 규칙이 없으면 주변 코드의 구조, 이름, 오류 처리 방식을 따른다.
- 이름만 보고 값이나 동작의 뜻을 알 수 있게 짓는다. 뜻을 알 수 없는 한 글자 이름을 쓰지 않는다.
- 버그는 원인을 고친다. 경고나 검사를 끄거나, 제한값을 늘리거나, 특정 입력만 통과시키는 분기를 넣어 증상을 가리지 않는다. 원인을 고치지 못했으면 그 이유를 [PR 설명](#pr-설명)에 쓴다.
- 바꾼 코드에 린트와 정적 분석의 새 경고를 남기지 않는다. 경고와 복잡도는 쌓이면 이후 변경을 느리게 한다. 검사 도구는 [TESTING.md의 자동 검사](TESTING.md#자동-검사)에 있다.

## 의존성

런타임 의존성을 바꾸는 변경은 [AGENTS.md의 작업 흐름](../AGENTS.md#작업-흐름)에서 위험 영역이다. 의존성 하나가 저장소 전체와 개발자 PC의 자격 증명에 접근할 수 있기 때문이다.

- 라이선스는 [LICENSES.md의 분류](LICENSES.md#분류)를 따른다.
- 의존성을 바꾸면 [TESTING.md의 자동 검사](TESTING.md#자동-검사)의 의존성 검사를 실행한다.

## 에이전트 실행 환경

AI 에이전트에게 비밀값 파일을 읽지 말라고 지시하는 것만으로는 막지 못한다. 에이전트 설정이 막는다.

- 에이전트 설정: `.claude/settings.json`. Claude Code가 `.env`, `.env.*`(`.env.example` 제외), `*.pem`, `*.key`를 읽지 못하게 막고, `git push`, `git reset --hard`, `rm -rf`는 실행 전에 확인받는다. `infrastructure/secrets/`의 실제 값 파일은 이 설정이 막지 않는다.
- 읽기 거부 설정은 에이전트의 파일 도구와 `cat` 같은 명령에 적용된다. 스크립트가 파일을 직접 여는 것까지는 막지 못하므로, 더 막아야 하면 에이전트의 sandbox나 dev container를 쓴다.
- 에이전트가 쓰는 환경에 운영 자격 증명과 운영 DB 접속 정보를 두지 않는다.
- 권한 확인을 끄는 모드는 격리한 컨테이너나 VM에서만 쓴다.

## 코드 안의 글

코드 주석, 문서 주석, 로그·오류 메시지는 코드와 함께 남아 다음 사람이 읽는다. 한국어로 쓸 때는 문장, 문장 부호, 용어, 표기를 [WRITING.md의 한국어](WRITING.md#한국어)에 맞춘다. 테스트 이름은 [TESTING.md의 위치와 이름](TESTING.md#위치와-이름)에 있다.

- 코드 안의 글(코드 주석, 문서 주석, 로그·예외 메시지)의 언어: 한국어. 기존 코드 주석은 합니다체가 많다.
- 기존 파일을 고칠 때는 그 파일의 언어와 문체를 따른다.
- 이벤트 이름, 오류 코드, 설정 키처럼 검색하거나 프로그램이 읽는 값은 영어로 쓰고 바꾸지 않는다.

### 코드 주석

- 코드만 읽어서는 알 수 없는 것을 쓴다. 왜 이렇게 했는지, 지켜야 할 제약, 자명하지 않은 동작이다.
- 코드가 무엇을 하는지 되풀이하지 않는다. 이름으로 설명할 수 있으면 주석 대신 이름을 고친다. 예: `delay(300)`에 주석을 다는 대신 `ANIMATION_DELAY_MS` 상수를 쓴다.
- 리뷰에서 설명해야 했던 코드는 이름을 고치거나 주석을 남긴다. 리뷰 대화에만 남긴 설명은 다음에 코드를 읽는 사람이 보지 못한다.
- 변경 이력을 쓰지 않는다. 예: `수정함`, `새로 추가`, `예전에는 500을 돌려줬다`. 이력은 커밋 메시지에 쓴다.
- 이번 변경에서 바꾸지 않은 코드에 주석을 더하지 않는다.
- 이번 변경으로 틀리거나 부족해진 주석은 바꾼 줄 밖에 있어도 같은 변경에서 고친다. 예: 함수를 더해 파일 첫 줄의 설명이 파일 전체를 설명하지 못하게 됐을 때. 주석과 어긋난 코드는 다음 사람을 잘못 이끈다.
- 할 일은 `TODO: 이슈 - 설명` 형식으로 쓴다. 사람 이름 대신 이슈를 가리킨다. 이슈 트래커가 없으면 요구사항 번호(예: `요구사항 0003`)를 쓴다. 예: `TODO: #123 - 결제 대행사 v2 API로 바꾼다`
- 주석 처리한 코드를 남기지 않는다. 지운 코드는 VCS 이력에 있다.

### 문서 주석

JSDoc, TSDoc, Javadoc 같은 문서 주석은 코드를 읽지 않고도 호출할 수 있게 쓴다.

- 공개 API와, 이름만으로 쓰임을 알 수 없는 함수, 타입, 속성에 쓴다. 이름으로 충분하면 쓰지 않는다.
- 첫 문장에 하는 일을 쓴다. 예: `주문을 취소하고 결제를 환불한다.`
- 인자와 반환값은 이름에 없는 정보가 있을 때만 쓴다. 단위, 범위, 빈 값의 뜻, 던지는 오류다.
- 타입이 코드에 있으면 문서 주석에 타입을 다시 쓰지 않는다.
- 한 파일 안에서는 같은 문체로 쓴다.

### 오류 메시지와 로그

- **사용자 오류 메시지**: 화면이나 API 응답으로 사용자에게 보이는 메시지다.
  - 무엇이 잘못됐는지와 사용자가 어떻게 하면 되는지 쓴다. 예: `저장하지 못했습니다. 잠시 후 다시 시도해 주세요.`
  - 원인마다 다른 메시지를 쓴다. 원인을 모르면 추측하지 않고 무엇을 하지 못했는지만 쓴다.
  - 사용자를 탓하지 않는다. stack trace, 내부 코드, 개발 용어 대신 사용자가 아는 말로 쓴다.
  - 사용자에게 보이는 다른 글과 같은 문체로 쓴다. 문체는 [PRODUCT.md의 말투](PRODUCT.md#말투)에 있다.
- **로그와 예외 메시지**: 운영자와 개발자가 문제를 찾을 때 읽는다.
  - 실제로 확인한 조건만 쓴다. 확인하지 않은 원인을 쓰지 않는다. 예: `이미 지워진 디렉터리` 대신 `디렉터리를 지우지 못함`
  - 메시지는 고정된 문장으로 두고, 값은 이름을 붙여 따로 넘긴다. 예: `console.warn('주문 취소 실패', { orderId })`. 로그 라이브러리가 있으면 그 메시지 템플릿이나 구조화 필드를 쓴다. 값을 메시지 문자열에 이어 붙이지 않는다.
  - 예외를 다시 던질 때는 원인 예외를 함께 넘긴다.
  - 비밀값과 개인정보를 남기지 않는다. 범위는 [TESTING.md의 점검 항목](TESTING.md#점검-항목)과 같다.
  - 일어날 수 없는 경우에 오류 처리와 로그를 더하지 않는다.

로그 레벨은 다음 기준으로 고른다.

| 레벨 | 쓰는 때 |
|---|---|
| ERROR | 지금 요청이나 작업이 실패했고 사람이 대응해야 한다 |
| WARN | 지금은 동작하지만 자주 생기면 문제가 된다 |
| INFO | 시작, 종료, 상태 변화처럼 운영에 필요한 사건 |
| DEBUG | 개발할 때만 필요한 세부 정보로, 운영 환경에서는 남기지 않는다 |

## 변경 반영 단계

커밋, 푸시, 리뷰, 병합, 릴리스는 서로 다른 단계다. 단계마다 통과 기준과 결정하는 사람이 다르다.

| 단계 | 하는 일 | 통과 기준 | 결정 |
|---|---|---|---|
| 로컬 검증 | [명령](#명령) 표의 테스트와 검사 가운데 바뀐 부분에 해당하는 것 실행 | 모두 통과 | 작업자 |
| 변경 확인 | diff와 VCS 상태 확인 | 의도한 파일만 바뀜, 문서 갱신 | 작업자 |
| 커밋 | 하나의 의도로 변경을 묶고 메시지 작성 | [커밋](#커밋) 규칙 | 작업자 |
| 리뷰 | 다른 사람이 변경을 검토 | 리뷰어 승인 | 사람 |
| 병합 | 기본 브랜치에 넣음 | 리뷰 승인, 병합 직전 재검증 | 사람 |
| 원격 검증 | CI가 같은 검사를 다시 실행 | CI 없음: 병합 직전에 로컬에서 `scripts/verify all` | 병합하는 사람 |
| 릴리스 | 버전 표시와 배포 | 릴리스 절차(아직 정하지 않음) | 사람 |

작업자는 사람일 수도 있고 AI일 수도 있다. AI는 사람이 지시할 때만 푸시, PR 생성, 병합, 릴리스를 한다.

예외로 AI는 [AGENTS.md의 단계별 규칙](../AGENTS.md#단계별-규칙)에 따라 토픽 브랜치를 푸시하고 draft PR을 연다.

### Git

| 단계 | 명령 |
|---|---|
| 최신 코드 받기 | 기본 브랜치에서 `git fetch --prune`, `git pull --rebase` |
| 병합된 브랜치 정리 | `git branch --merged main`으로 확인하고 `git branch -d 브랜치-이름` |
| 토픽 브랜치 만들기 | `git switch -c 브랜치-이름` |
| 변경 확인 | `git status`, `git diff`, `git diff --check` |
| 커밋 | `git add -p`, `git commit` |
| PR | 토픽 브랜치를 푸시하고 PR을 연다 |
| 병합 | 리뷰 승인과 로컬 전체 검증 후 병합한다 |

- 원격 저장소: GitHub `origin` (https://github.com/subinggrae/server-monitor)
- 토픽 브랜치 이름: `종류/짧은-이름`. 종류는 [커밋](#커밋)의 종류를 쓴다. 예: `feat/짧은-이름`, `fix/짧은-이름`, `docs/짧은-이름`
- 변경 하나에 토픽 브랜치 하나를 쓴다. 브랜치는 며칠 안에 병합한다. 리뷰를 반영하는 동안에는 같은 브랜치에 커밋을 더한다.
- 원격 브랜치는 병합할 때 호스팅의 브랜치 삭제 옵션으로 지운다. 로컬 브랜치는 다음 토픽 브랜치를 만들기 전에 `git branch -d`로 지운다. 기본 브랜치는 지우지 않는다.
- `git branch -d`가 거부한 브랜치는 지우지 않고 보고한다. squash나 리베이스로 병합하면 병합된 브랜치도 거부된다. `git branch -D`는 사람이 PR 병합을 확인한 뒤에만 쓴다.
- 기본 브랜치(`main`)에 직접 커밋하거나 푸시하지 않는다.
- PR 만들기: `git push -u origin HEAD` 후 `gh pr create --draft`. `gh`는 GitHub CLI이고, 처음 한 번 `gh auth login`이 필요하다. `gh`가 없으면 GitHub 웹 화면에서 draft PR을 연다.
- PR 설명은 [PR 설명](#pr-설명) 규칙을 따른다.
- PR 템플릿(`.github/pull_request_template.md`)은 결과 보고에서 다음 단계를 빼고, 요구사항·계획·ADR·이슈 링크를 관련 문서 항목에 쓴 형식이다. 한쪽 항목을 바꾸면 다른 쪽도 고친다.
- 저장소 설정에서 기본 브랜치를 보호한다. 리뷰 승인 1명 이상, 강제 푸시 금지를 켠다. CI를 붙이면 필수 검사 통과도 켠다.

## 커밋

요약은 [Conventional Commits](https://www.conventionalcommits.org/ko/v1.0.0/) 형식(`종류: 설명`)으로 쓴다. 메시지는 요약 한 줄, 빈 줄, 본문, 빈 줄, 꼬리말 순서다. 다음은 이 저장소의 실제 커밋이다.

```text
test(frontend): Check 입력 화면 규칙 테스트 추가

종류를 바꾸면 대상을 비우고 Port 칸은 Port가 필요한 종류에서만 보인다.
수정할 때 남은 대상의 id를 그대로 보내야 Prometheus 기록이 끊기지 않는다.
이 규칙과 오류 표시는 화면에만 있어 Backend 테스트로 확인할 수 없었다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

| 종류 | 쓰는 때 |
|---|---|
| `feat` | 사용자가 쓰는 기능을 추가하거나 바꿀 때 |
| `fix` | 버그를 고칠 때 |
| `docs` | 문서만 바꿀 때 |
| `refactor` | 동작을 바꾸지 않고 코드 구조를 바꿀 때 |
| `test` | 테스트만 추가하거나 고칠 때 |
| `perf` | 성능을 개선할 때 |
| `build` | 빌드 설정이나 의존성을 바꿀 때 |
| `ci` | CI 설정을 바꿀 때 |
| `chore` | 그 밖의 관리 작업 |

- 종류는 영어 소문자로 쓴다. 바뀌는 범위가 분명하면 괄호로 붙인다. 예: `fix(frontend): msw를 2로 낮춰 npm 10의 npm ci 실패 해결`
- 설명은 무엇을 바꿨는지 한국어로 50자 안팎으로 쓰고 명사형으로 끝낸다. 마침표를 찍지 않는다.
- "수정", "버그 수정", "작업"처럼 무엇이 바뀌었는지 알 수 없는 설명은 쓰지 않는다.
- 호환성을 깨는 변경은 종류 뒤에 `!`를 붙이고 본문에 `BREAKING CHANGE: 무엇이 깨지는가`를 쓴다. 받던 입력을 새 검증으로 거부하는 변경도 포함한다. 다만 API를 이 저장소의 화면만 호출하고 같은 변경에서 화면도 고쳤으면 `!`를 붙이지 않고 본문에 바뀐 동작을 쓴다. 호출하는 쪽은 [ARCHITECTURE.md의 지켜야 할 경계](ARCHITECTURE.md#지켜야-할-경계)에서 확인한다.
- 본문에는 diff만 봐서는 알 수 없는 이유와 영향을 쓴다. 코드를 다시 설명하지 않는다.
- 본문은 평서형으로 쓴다. diff에 없는 변경을 쓰지 않는다.
- 꼬리말 키는 영어로 쓴다. 예: `Refs: #123`, `Fixes #123`, `Co-authored-by: 이름 <메일>`
- 하나의 커밋에는 하나의 의도만 담는다. 리팩터링과 동작 변경은 나눈다.
- 커밋하기 전에 [명령](#명령) 표의 빠른 검증(`scripts/verify fast`)이 통과해야 한다. 푸시와 병합 전에는 전체 검증(`scripts/verify all`)을 실행한다.
- 변경이 수백 줄을 넘으면 나눌 수 있는지 먼저 검토한다. 생성된 코드와 기계적인 이름 변경은 예외다.
- AI 사용 표시: AI 도구가 붙이는 `Co-Authored-By` 꼬리말을 그대로 둔다. 기존 커밋이 이 꼬리말을 남겼다.

## 리뷰

### PR 설명

리뷰어는 diff를 열기 전에 PR 설명을 읽는다. 형식은 [PR 템플릿](../.github/pull_request_template.md)을 쓴다. PR 템플릿에 이유, 검증, 확인하지 못한 것, 리뷰할 곳에 해당하는 절이 없으면 템플릿의 절 뒤에 더한다.

- 제목은 커밋 요약과 같은 형식으로 쓴다.
- 변경 항목의 첫 문장만 읽어도 무엇을 바꿨는지 알 수 있게 쓴다. 이유에는 diff로 알 수 없는 배경과 고르지 않은 방법을 쓴다.
- 설명에 쓴 변경이 diff에 모두 있고, diff의 변경이 설명에 모두 있는지 확인한다. 하지 않은 변경을 했다고 쓰는 것이 AI가 쓴 설명에서 가장 흔한 오류다. 리뷰를 반영해 코드를 바꾸면 설명을 최종 상태에 맞게 고치고, 리뷰를 반영하며 바꾼 이력은 댓글에 쓴다.
- 검증에는 실행한 명령과 결과를 쓴다. 테스트를 더하지 않았으면 이유를 쓴다. 이 변경 전부터 실패하던 검사는 따로 적는다. 실행하지 못한 검증은 확인하지 못한 것에 쓴다.
- 리뷰할 곳에는 어떤 의견을 원하는지 쓴다. 예: `캐시를 비우는 시점이 맞는지 봐 주세요.` 리뷰어가 어디에 집중할지 알 수 있다.
- 화면이 바뀌었으면 바뀌기 전과 후의 스크린샷을 붙인다.
- 합니다체로 쓴다. 바꾼 내용은 현재형(`추가합니다`), 검증 결과는 과거형(`통과했습니다`)으로 쓴다. 항목은 목록으로 짧게 쓴다.

### 리뷰 코멘트

- 코드를 말하고, 코드를 쓴 사람을 말하지 않는다.
- 고쳐야 하는 이유를 쓴다. 고칠 방법이 여럿이면 답을 정해 주기보다 질문이나 제안으로 쓴다.
- 반영해야 하는 정도를 코멘트 앞에 표시한다. 표시가 없으면 작성자는 모든 코멘트를 반드시 고칠 것으로 읽는다. `P1`(꼭 반영), `P2`(적극 고려), `P3`(웬만하면 반영), `P4`(반영하지 않아도 됨), `P5`(사소한 의견)
- 해요체로 쓴다. 예: `P3: 목록이 비어 있으면 여기서 오류가 나요. 앞에서 걸러 주면 어떨까요?`
- 리뷰에서 한 설명이 다음에 코드를 읽는 사람에게도 필요하면, 코드의 이름을 고치거나 주석으로 남기도록 요청한다.
- AI가 리뷰하면 발견한 문제를 심각한 순서로, 파일과 줄을 붙여 먼저 쓴다. 심각한 것만 골라 쓰지 않고 모두 쓴 뒤 정도로 구분한다. 문제가 없으면 없다고 쓴다.
