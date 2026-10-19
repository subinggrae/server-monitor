# 아키텍처

이 시스템이 어떤 부분으로 나뉘고, 각 부분이 어디에 있으며, 어떤 경계를 지켜야 하는지 설명한다. 처음 코드를 고치는 사람이 "이 기능은 어디를 고쳐야 하는가"를 빨리 찾게 하는 것이 목적이다.

## 한눈에 보기

Monitoring Manager는 사용자가 화면에서 등록한 Check와 서버를 Manager Database에 저장한다. Prometheus는 HTTP SD로 그 목록을 가져가 Blackbox Exporter로 서버와 서비스가 살아 있는지 확인하고, 서버에 설치한 OS exporter(node_exporter, windows_exporter)에서 CPU, Memory, Storage를 수집한다. Alertmanager가 보낸 Alert는 Manager가 받아 기록한 뒤 Telegram 그룹으로 보낸다. 사용자 설정의 기준은 Manager Database이고, `infrastructure/`의 설정 파일은 배포할 때만 바뀐다.

Prometheus, Blackbox Exporter, Alertmanager는 모니터링 코어로 묶어 앱과 따로 배포한다. Manager(화면, API, DB)는 [통합 계약](INTEGRATION.md)으로 코어에 붙는 앱 하나이고, 다른 앱도 같은 계약으로 붙을 수 있다. 결정과 이유는 [ADR 0007](decisions/0007-core-app-contract.md)에 있다.

![사용자 설정은 Manager API를 거쳐 Manager DB에 저장되고, Prometheus는 HTTP SD로 그 목록을 가져가 수집한다](assets/architecture-overview.svg)

## 실행 단위

따로 실행되거나 배포되는 단위와 그 기술, 통신 방식은 다음과 같다. 코어(`prometheus`, `alertmanager`, `blackbox-exporter`)는 `infrastructure/compose.yml`, 앱은 `compose.yml`에 있다. 코어가 `monitoring` 네트워크를 만들고 앱은 그 네트워크에 붙으므로 코어를 먼저 띄운다. 호스트에 여는 포트는 모두 `127.0.0.1`에만 묶는다.

| 단위 | 역할 | 기술 | 통신 |
|---|---|---|---|
| `manager-web` | 사용자 화면과 `/api/` 프록시 | React, Vite, Nginx | 브라우저에서 HTTP(호스트 8081), `/api/` 프록시 |
| `manager-api` | 설정 관리, HTTP SD 목록 제공, Alert 수신과 Telegram 전송 | Spring Boot, Java 21 | REST(호스트 8080), JDBC, Prometheus HTTP API, Telegram Bot API, OS exporter `/metrics`(연결 시험과 리소스 찾기) |
| Manager Database | Manager의 설정과 Alert·전송 기록 저장 | MariaDB 또는 PostgreSQL | JDBC |
| `prometheus` | HTTP SD로 대상을 받아 수집하고 Alert 규칙을 평가 | Prometheus | 앱의 HTTP SD 호출, exporter 수집, Alertmanager로 Alert 전송 |
| `alertmanager` | Alert를 묶어 앱 webhook 하나로 보냄 | Alertmanager | Bearer token을 붙인 webhook |
| `blackbox-exporter` | ICMP, TCP, HTTP, HTTPS 가용성 확인 | Blackbox Exporter | Prometheus가 probe 요청 |
| `os-test-node-exporter` | 개발 환경에서 OS exporter 수집을 확인하는 테스트 대상 | node_exporter | `os-test` profile에서만 뜬다 |

표에 다 적지 못한 내용은 다음과 같다.

- 개발용 Manager Database는 `local-db` profile의 `manager-db`(MariaDB)다. 운영에서는 외부 MariaDB나 PostgreSQL을 쓴다.
- 코어가 부르는 앱 주소는 `infrastructure/.env`의 `MONITORING_APP_ADDRESS` 하나다. Prometheus와 Alertmanager가 시작할 때 `infrastructure/render-config.sh`가 설정 템플릿에 펼친다.
- OS exporter는 이 시스템이 띄우지 않는다. 감시 대상 서버에 사람이 설치하고, Prometheus가 HTTP SD로 받은 주소를 직접 수집한다. Linux 서버용 배포 묶음은 `infrastructure/node-exporter/compose.yml`이고, 설치 안내는 [README](../README.md#os-exporter-설치)에 있다.

외부 시스템은 다음과 같다.

- **Telegram**: `manager-api`가 Bot API로 알림을 보낸다. 그룹의 `/register` 명령은 Telegram이 `manager-api`의 webhook으로 보낸다. 이 webhook은 외부에서 접근 가능한 HTTPS 주소(`TELEGRAM_WEBHOOK_PUBLIC_URL`)가 있을 때만 쓴다.
- **감시 대상**: 사용자가 등록한 서버와 서비스다. Blackbox Exporter가 살아 있는지 확인하고, 서버에 설치한 OS exporter를 Prometheus가 수집한다. `manager-api`도 연결 시험과 리소스 찾기에서 OS exporter의 `/metrics`를 한 번 읽는다.
- **Grafana**: 코어와 앱 어디에도 없다. `manager-api`는 화면에 보일 링크 주소(`GRAFANA_URL`)만 내려준다.

## API와 데이터 모델

API 명세와 데이터 모델의 원본 위치는 다음과 같다.

- API 명세: 없다. 컨트롤러 코드(`*Controller`)가 기준이다. 오류는 `ApiExceptionHandler`가 ProblemDetail 형식으로 돌려준다.
- 코어와 앱 사이의 계약: [INTEGRATION.md](INTEGRATION.md)
- 데이터 모델: JPA Entity와 Flyway migration `backend/src/main/resources/db/migration/mariadb/`, `backend/src/main/resources/db/migration/postgresql/`. 테이블마다 지금 모양은 vendor별 `V1__baseline.sql`에 있다([ADR 0006](decisions/0006-baseline-migration.md)). Hibernate는 schema를 만들지 않고 Entity와 맞는지만 확인한다(`ddl-auto: validate`).

## 코드 지도

디렉터리와 주요 모듈의 책임은 다음과 같다.

| 위치 | 책임 |
|---|---|
| `backend/` 패키지 `alert` | Alertmanager webhook을 받아 Alert를 기록한다 |
| `backend/` 패키지 `check` | Blackbox 기반 가용성 Check(ICMP, TCP, HTTP, HTTPS)와 확인 대상 |
| `backend/` 패키지 `server` | 사용자가 등록한 서버 자산과 OS, 서버의 수집 상태 |
| `backend/` 패키지 `exporter` | 서버별 OS exporter 수집 설정, 연결 시험, `/metrics` 읽기와 해석 |
| `backend/` 패키지 `resource` | exporter에서 찾은 Resource와 사용자가 고른 감시 Resource |
| `backend/` 패키지 `prometheus` | HTTP SD 목록과 PromQL 조회 |
| `backend/` 패키지 `notification` | Alert를 Telegram 메시지로 만들어 보내고 결과를 기록한다 |
| `backend/` 패키지 `common` | 오류 응답(`exception`) |
| `backend/` 패키지 `config` | 환경 변수 바인딩, bean 등록, 비밀값 읽기(`ManagerSecrets`) |
| `backend/` 패키지 `system` | 화면에 필요한 시스템 정보 |
| `frontend/src/pages` | 라우트마다 하나인 화면 |
| `frontend/src/components` | 앱 공통 컴포넌트와 도메인별(`checks`, `servers`, `notifications`) 폼과 섹션 |
| `frontend/src/components/ui` | 버튼, 다이얼로그, 입력, 표 같은 기본 UI |
| `frontend/src/lib` | API 호출과 query 정의(`api.ts`), 표시 형식(`format.ts`) |
| `frontend/e2e` | 전체 실행 환경에 실행하는 E2E, 화면 검사, 성능 검사(Playwright) |
| `infrastructure/` | 모니터링 코어(`compose.yml`, 설정을 펼치는 `render-config.sh`), Prometheus 설정 템플릿과 규칙(규칙 테스트는 `prometheus/tests/`), Alertmanager, Blackbox 설정, node_exporter 배포 묶음(`node-exporter/`), 비밀값 예시 파일 |

`notification` 안에서 `routing`은 알림을 받을 그룹을 찾고, `telegram`은 그룹 관리와 Bot API 호출을 맡는다.

## 지켜야 할 경계

코드를 고칠 때 지켜야 할 경계는 다음과 같다.

- 사용자가 화면에서 바꾼 설정 때문에 `infrastructure/`의 설정 파일과 Alert 규칙을 만들거나 고치지 않는다. Check 대상과 수집 대상 서버는 HTTP SD로, 알림 대상은 Manager Database로 전달한다. Manager는 파일을 쓰지 않는다.
- 알림을 받을 Telegram 그룹(chat id 등)은 Manager Database가 정한다. Alert 규칙, Alertmanager 설정, Prometheus label에 넣지 않는다.
- Bot Token은 Manager Database에 저장하지 않고 API 응답에도 넣지 않는다. Manager Database에는 암호화해 저장하는 인증 정보가 없다.
- PromQL은 `prometheus` 패키지 안에서만 만든다.
- 서버가 살아 있는지는 감시 항목(Blackbox)이 판단하고 알린다. 서버 화면은 OS exporter 수집 상태(`up{job="manager-os"}`)만 보인다. 수집 실패를 서버 장애로 보이지 않는다.
- 서버 자원 값은 `infrastructure/prometheus/rules/server-resources.yml`의 공통 recording rule(`manager:resource_usage_percent`, `manager:resource_used_bytes`, `manager:resource_size_bytes`, label `server_id`, `resource_type`, `resource_key`)만 읽는다. OS별 계산은 그 파일에만 둔다. 결정과 이유는 [ADR 0005](decisions/0005-common-resource-recording-rules.md)에 있다.
- `manager-os` job은 keep 규칙으로 위 recording rule이 읽는 series만 저장한다. 다른 지표가 필요해지면 keep 목록과 규칙을 함께 고친다. 결정과 이유는 [ADR 0004](decisions/0004-os-exporter-collection.md)에 있다.
- Check 대상 하나가 HTTP SD entry 하나다. 대상의 id와 SD label은 Prometheus time series의 이름표이므로, 바꾸면 쌓아 온 time series가 끊긴다.
- Check와 OS exporter 수집 서버의 수집 주기(10~240초)는 Manager Database에 두고, SD entry의 `__scrape_interval__` label로 Prometheus에 전한다. `__`로 시작하는 label은 relabel 뒤에 지워져 time series 이름표가 되지 않는다. 그래서 `manager-blackbox`, `manager-os` job은 `prometheus.yml`의 `scrape_interval`을 쓰지 않는다. `prometheus.yml`의 `scrape_timeout`은 수집 주기 하한(10초)보다 길게 두지 않는다. timeout이 주기보다 긴 대상을 Prometheus가 수집하지 않기 때문이다. 결정과 이유는 [ADR 0002](decisions/0002-scrape-interval-via-http-sd.md)에 있다.
- Alert가 이어지는 동안 Telegram 알림을 다시 보내는 주기(5~1440분)는 Check마다 Manager Database에 둔다. Alertmanager는 이어지는 Alert를 `group_interval`(5분)마다 webhook으로 다시 보내고, Manager가 마지막 성공 전송 뒤 그 Check의 주기가 지났는지 보고 보낸다. 그래서 `alertmanager.yml`의 `repeat_interval`(4분)은 `group_interval`보다 짧게 두고, 재발송 주기 하한은 `group_interval`보다 짧게 두지 않는다. `repeat_interval`을 `group_interval`과 같게 두면 Alertmanager가 한 번씩 건너뛰어 10분마다 보낸다. 결정과 이유는 [ADR 0001](decisions/0001-alert-repeat-in-manager.md)에 있다.
- Manager Database는 MariaDB와 PostgreSQL을 함께 지원한다. Flyway migration은 두 vendor 디렉터리에 같은 version으로 둔다. 접속 URL이 vendor를 정하고, 지원하지 않는 DB면 기동에 실패한다.
- 테이블과 열 이름은 JPA Entity 어노테이션과 기준 migration에만 둔다. 네이티브 SQL을 쓰지 않는다. 도입하는 쪽이 이름을 바꾸는 절차는 [INTEGRATION.md의 테이블과 열 이름 바꾸기](INTEGRATION.md#테이블과-열-이름-바꾸기)에 있고, `DatabaseNameLocationTest`가 지킨다.
- 코어 설정 파일에 앱 주소를 직접 적지 않는다. 앱 주소는 `MONITORING_APP_ADDRESS`로만 받는다. 코어와 앱 사이에 오가는 것은 [INTEGRATION.md](INTEGRATION.md)에 적은 것뿐이다.
- API를 호출하는 쪽은 다음과 같다. 받던 입력을 거부하거나 응답을 바꾸는 변경이 호환성을 깨는지 이 범위로 판단한다.
  - 화면용 `/api/*`: 이 저장소의 화면만 호출한다.
  - `/api/prometheus/sd/blackbox`, `/api/prometheus/sd/os-exporter`: Prometheus가 호출한다(`infrastructure/prometheus/prometheus.yml`). 공개 API로 보고, 계약은 [INTEGRATION.md](INTEGRATION.md)에 있다.
  - `/api/integrations/alertmanager/webhook`: Alertmanager가 호출한다. 공개 API로 보고, 계약은 [INTEGRATION.md](INTEGRATION.md)에 있다.
  - `/api/integrations/telegram/webhook`: Telegram이 호출한다. 공개 API로 본다.
- 신뢰 경계는 다음과 같다.
  - 신뢰할 수 없는 입력은 화면용 REST API의 요청, 두 webhook의 요청, Prometheus와 Telegram의 응답, OS exporter의 `/metrics` 응답으로 들어온다. Manager는 `/metrics`를 경로를 고정해 `GET`으로만 읽고, 리다이렉트를 따르지 않으며, 크기와 시간에 한도를 둔다. 응답 본문은 화면과 로그에 넣지 않는다.
  - OS exporter는 인증 없이 열린다. 대상 서버의 방화벽에서 Prometheus와 Monitoring Manager만 접근하게 한다.
  - 두 webhook은 각각 Bearer token과 Telegram secret header로 호출하는 쪽을 확인한다.
  - 데이터가 외부로 나가는 곳은 Telegram Bot API(알림 메시지)다.

## 공통 관심사

여러 모듈에 걸친 처리 방식은 다음과 같다.

- **인증·권한**: 화면용 API에는 인증과 권한 확인이 없다. 포트를 `127.0.0.1`에만 묶어 노출을 줄인다. Alertmanager webhook은 `AlertmanagerWebhookController`가 Bearer token을, Telegram webhook은 `TelegramWebhookController`가 secret header를 확인한다.
- **입력 검증**: 요청 DTO의 Bean Validation과 서비스 코드에서 검증한다. 필드 오류는 ProblemDetail의 `fieldErrors`로 돌려주고, 화면은 그 오류를 해당 입력 칸 아래에 보인다.
- **오류 처리**: `ApiExceptionHandler`가 ProblemDetail로 응답한다. 기본 오류 응답에 메시지와 stack trace를 넣지 않는다(`server.error.include-message`, `include-stacktrace`가 `never`). Prometheus 조회가 실패하면 예외 대신 `unavailable` 상태를 돌려준다.
- **비밀값**: `infrastructure/secrets/`의 파일을 Docker secret으로 넣고, Spring이 `configtree:/run/secrets/`로 읽는다. 파일이 없으면 환경 변수로 받는다. `ManagerSecrets`가 한곳에서 꺼내고, 로그에는 설정 여부만 남긴다.
- **로그**: logback 설정 파일이 없고 `application.yml`에서 패키지 로그 레벨만 정한다. 접속기록과 감사 로그는 없다. 받은 Alert는 `alert_events`, Telegram 전송 결과는 `notification_deliveries` 테이블에 남는다.
- **시간대**: Manager Database에는 UTC로 저장한다. 화면은 브라우저 시간대로 보이고, Telegram 메시지의 시각에만 `MANAGER_TIME_ZONE`을 쓴다.
- **설정**: 노출돼도 되는 값은 `.env`, 비밀값은 `infrastructure/secrets/`에 둔다. 설정 키는 `application.yml`의 `monitoring-manager.*`에 바인딩한다.
