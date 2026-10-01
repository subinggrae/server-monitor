# 아키텍처

이 시스템이 어떤 부분으로 나뉘고, 각 부분이 어디에 있으며, 어떤 경계를 지켜야 하는지 설명한다. 처음 코드를 고치는 사람이 "이 기능은 어디를 고쳐야 하는가"를 빨리 찾게 하는 것이 목적이다.

## 한눈에 보기

운영자는 `manager-web` 화면에서 Check, 서버, Telegram 그룹을 등록한다. `manager-api`는 설정을 Manager Database에 저장하고 Prometheus에 HTTP Service Discovery(SD)로 수집 대상을 알려 준다. Prometheus는 exporter로 수집하고, Alertmanager가 보낸 Alert는 `manager-api`가 받아 Telegram으로 보낸다. Check를 추가하거나 지워도 `prometheus.yml`을 고치거나 Prometheus를 다시 시작하지 않는다.

![설정은 Manager Database 하나에 모이고, Prometheus는 manager-api의 SD로 대상을 받아 exporter로 수집한다](assets/architecture-overview.svg)

상세 동작은 영역마다 따로 정리했다.

| 영역 | 문서 |
|---|---|
| Blackbox 가용성 Check, SD label, 상태 계산 | [checks.md](architecture/checks.md) |
| 서버 CPU·Memory·Storage SNMP 수집 | [snmp-resources.md](architecture/snmp-resources.md) |
| Alertmanager에서 Telegram까지 알림 경로 | [notifications.md](architecture/notifications.md) |

## 실행 단위

`compose.yml`이 모든 실행 단위를 정의한다. host에 여는 port는 모두 `127.0.0.1`에만 묶인다.

| 단위 | 역할 | 기술 | 통신 |
|---|---|---|---|
| `manager-web` | 운영자 화면. 설정 입력과 현재 상태 조회 | React 19, Vite, TanStack Query, Nginx | host `8081`. `/api`를 Nginx가 `manager-api:8080`으로 넘긴다 |
| `manager-api` | 설정 저장, SD 응답, 상태 조회, Alert 수신, Telegram 전송, SNMP Discovery | Spring Boot 4, Java 21, JPA, Flyway | host `8080`. Manager Database는 JDBC, Prometheus·exporter·Telegram은 HTTP, 장비는 SNMP |
| Manager Database | Manager의 모든 설정과 Alert 이력 저장 | MariaDB 또는 PostgreSQL. `local-db` profile이면 `manager-db`(MariaDB 11.4) | host `13306`(`MANAGER_DB_PORT`) |
| `prometheus` | metric 수집, Alert 규칙 평가 | Prometheus v3.14.0. 보존 기간 15일 | host `9090`. `manager-api`의 SD를 주기적으로 조회 |
| `alertmanager` | Alert 묶기, 중복 제거, Manager로 전달 | Alertmanager v0.34.1 | host `9093`. `manager-api` webhook 하나로 보낸다 |
| `blackbox-exporter` | ICMP·TCP·HTTP·HTTPS 가용성 확인 | Blackbox Exporter v0.28.0 | host `9115` |
| `snmp-exporter` | CPU·Memory·Storage 수집 | SNMP Exporter v0.30.1 | host에 열지 않는다. Docker network 안에서만 부른다 |
| `snmp-test-agent` | 개발 환경에서 SNMP 흐름을 확인하는 테스트 장비 | `polinux/snmpd`. `snmp-test` profile | 운영에서는 띄우지 않는다 |
| Telegram Bot API | 운영자에게 알림 전송 | 외부 서비스 | `manager-api`만 호출한다 |

아직 구성하지 않은 단위가 있다.

- **mysqld_exporter**: Database Target metric을 수집할 예정이다. `compose.yml`에 service가 없고 `MYSQLD_EXPORTER_URL` 설정만 있다.
- **Grafana**: 상세 시계열 조회와 분석을 맡을 예정이다. `infrastructure/grafana/provisioning/`에 빈 디렉터리만 있다. 남은 일은 Prometheus를 기본 datasource로 등록하는 `provisioning/datasources/prometheus.yml` 작성, `provisioning/dashboards/` 아래 서버 가용성·리소스·DB 기본 dashboard 추가, `compose.yml`에 `grafana` service 추가다.

## 코드 지도

| 위치 | 책임 |
|---|---|
| `backend/` | `manager-api`. Spring Boot 애플리케이션 |
| `backend/src/main/resources/db/migration/{mariadb,postgresql}/` | 제품별 Flyway migration. schema의 기준 |
| `frontend/` | `manager-web`. React SPA와 Nginx 설정(`nginx.conf`) |
| `infrastructure/prometheus/` | Prometheus 설정과 Alert 규칙(`rules/`) |
| `infrastructure/alertmanager/` | Alertmanager 설정 |
| `infrastructure/blackbox/` | Blackbox Exporter module 정의 |
| `infrastructure/snmp/test-agent/` | 테스트용 SNMP 장비의 `snmpd.conf` |
| `infrastructure/grafana/` | Grafana provisioning 자리. 아직 비어 있다 |
| `infrastructure/secrets/` | Docker Secret 파일. `*.example`만 Git에 있다 |
| `compose.yml` | 전체 Docker Compose 구성 |
| `scripts/verify` | 검증 진입점 |

Backend는 기능 중심으로 package를 나눈다. 각 package의 `package-info.java`에 역할과 제약이 있다. 새 기능을 만들기 전에 해당 package의 설명을 먼저 읽는다.

| Backend package (`kr.co.unidocs.monitoring.`) | 책임 |
|---|---|
| `config` | 설정 바인딩, bean 등록, Manager Database 연결 설정 |
| `common.exception` | API 오류 응답 |
| `common.security` | 인증 정보 암호화 |
| `system` | 애플리케이션 자체 정보 API |
| `server` | 모니터링 대상 서버 |
| `snmp` | SNMP 설정, 장비 직접 조회, exporter auth config |
| `resource` | CPU·Memory·Storage 발견, 선택, 현재 값 |
| `check` | Blackbox 가용성 Check |
| `prometheus` | HTTP SD 응답, PromQL 조회 |
| `alert` | Alertmanager webhook 수신과 Alert 이력 |
| `notification` | 전송과 결과 기록. `routing`은 Check와 Telegram 그룹 연결, `telegram`은 그룹 관리와 Bot API 호출 |
| `database`, `database.provider` | Database Target. 아직 `package-info.java`만 있다 |
| `threshold` | Threshold. 아직 `package-info.java`만 있다 |
| `grafana` | Grafana 링크 생성. 아직 `package-info.java`만 있다 |

| Frontend 위치 (`frontend/src/`) | 책임 |
|---|---|
| `components/ui/` | button, input, table, section, dialog, tabs 같은 공통 component |
| `components/checks/`, `components/servers/`, `components/notifications/` | 화면 전용 Dialog와 section |
| `components/` | Layout, PageHeader, StatusDot, Toolbar 같은 화면 공통 요소 |
| `pages/` | Router에 연결되는 화면 |
| `lib/api.ts` | Backend 호출, 타입, query key(`queryKeys`) |
| `index.css` | 디자인 token |
| `App.tsx` | Router와 TanStack Query 설정 |
| `test/` | 컴포넌트 테스트 공통 준비 코드. 테스트 파일은 대상 옆에 `*.test.ts`나 `*.test.tsx`로 둔다 |

E2E 테스트와 E2E 전용 Compose 구성은 `frontend/e2e/`에 있다. 기준은 [TESTING.md의 E2E 테스트](TESTING.md#e2e-테스트)에 있다.

Query key는 `lib/api.ts`의 `queryKeys`에 모은다. Mutation이 끝나면 관련 query를 invalidate한다.

## 지켜야 할 경계

### 설정 파일과 Manager Database

- `infrastructure/`의 파일은 시스템 초기 배포용 설정이다. 사용자가 화면에서 서버, DB, Threshold, Telegram 그룹을 바꿔도 Manager는 이 파일과 Alert 규칙을 만들거나 고치지 않는다. 사용자 설정의 기준은 Manager Database다.
- 수집 대상은 HTTP SD로만 Prometheus에 전달한다. `file_sd_configs`와 `targets/*.yml`을 쓰지 않는다. ([ADR 0001](decisions/0001-prometheus-http-service-discovery.md))
- Check마다 다른 응답 코드 조건이나 timeout을 주려고 `blackbox.yml`을 만들어 내지 않는다. 외부 시스템의 제약을 코드로 숨기지 않는다. ([ADR 0004](decisions/0004-fixed-blackbox-modules.md))
- 알림 대상은 Manager Database가 정한다. Alertmanager의 receiver는 Manager webhook 하나이고, Prometheus label과 Alert 규칙에 Telegram chat id 같은 routing 정보를 넣지 않는다. Telegram 그룹을 추가해도 `alertmanager.yml`은 그대로다. ([ADR 0005](decisions/0005-alertmanager-single-webhook-routing.md))

### Prometheus label

- SD label의 `check_id`를 없애거나 이름을 바꾸지 않는다. Manager는 Alert에 실려 온 이 label로 Check를 찾는다.
- label을 더하거나 지우면 series 식별자가 바뀌어 time series가 끊긴다. 이전 데이터는 보존 기간(15일)이 지나면 사라진다.
- label에 description, 긴 오류 메시지, HTTP 응답 본문, 인증 정보, 알림 대상 정보를 넣지 않는다.

### 도메인 분리

- Monitoring Check는 Server에 속하지 않는다. `monitoring_checks`에는 `server_id`가 없고, Check 쪽 table과 `server_targets` 사이에 FK가 없다. Server를 지워도 Check와 Target은 남는다. Server 상태를 Blackbox Check로 계산하지 않는다. ([ADR 0003](decisions/0003-separate-check-from-server.md))
- Manager Database와 Database Target을 섞지 않는다. Manager Database는 Manager가 자기 설정을 저장하는 DB이고 `config` package가 연결을 맡는다. Database Target은 사용자가 모니터링하려는 DB이고 `database` package가 맡는다. Manager는 Database Target에 JPA로 붙지 않는다. 둘을 섞으면 사용자 DB를 설정 저장소로 잘못 쓰거나, Manager Database 제품을 바꿀 때 Database Target 코드까지 영향을 받거나, 서로 다른 인증 정보 보관 방식을 같게 다루게 된다.
- Database Target은 Server 없이 단독으로 등록할 수 있어야 한다(AWS RDS 등). 아직 구현하지 않았다.

### PromQL과 Prometheus

- PromQL은 `prometheus` package 안에서만 만든다. Frontend는 `GET /api/checks/{id}/status` 같은 업무 단위 API만 호출하고 Prometheus를 직접 부르지 않는다.
- Prometheus에 연결하지 못해도 설정 조회 API는 실패하지 않는다. 상태만 `UNKNOWN`이 된다. `PrometheusQueryService`는 예외를 밖으로 던지지 않고, 연결 실패 로그는 5분에 한 번만 남긴다.
- Manager는 Grafana 기능을 다시 만들지 않는다. Manager는 설정, 현재 장애 상태, 알림 이력, 간단한 요약을 맡고, 상세 시계열 그래프·장기 추세·여러 metric 조합·자유로운 dashboard는 Grafana가 맡는다. Manager 화면은 대상별 Grafana dashboard 링크만 제공하고 dashboard를 자동으로 만들지 않는다.

### 인증 정보

- DB 비밀번호, SNMP community, Telegram Bot Token, `APP_SECRET_KEY`를 API 응답, log, SD 응답, Prometheus label에 넣지 않는다. 마스킹한 값도 넣지 않는다. 인증 정보를 받는 API는 쓰기 전용이다.
- Bot Token은 Manager Database에 저장하지 않고 화면에서 입력받지 않는다. SNMP community는 `APP_SECRET_KEY`로 암호화해 저장한다. ([ADR 0010](decisions/0010-snmp-credentials-via-exporter-auth-config.md))
- 비밀은 Docker Secret 파일로 넣고 Spring Boot config tree로 읽는다. `compose.yml`의 `environment`에 비밀을 넣지 않는다. 비밀 파일을 읽는 코드를 따로 만들지 않는다. ([ADR 0012](decisions/0012-secrets-via-docker-secret-config-tree.md))

### SNMP

- 대상 서버에 Agent를 설치하지 않는다. 가용성은 Blackbox Exporter, 서버 Resource는 SNMP로 모니터링 서버 쪽에서 가져온다. ([ADR 0009](decisions/0009-agentless-snmp-resource-monitoring.md))
- Resource Discovery는 `manager-api`가 장비에 직접 SNMP로 한다. `snmp_exporter`를 Discovery API처럼 쓰지 않는다.
- Manager가 만드는 exporter 설정은 `snmp-runtime` volume의 auth config 하나다. 이 volume을 host에 bind하지 않는다.

### Manager Database 이식성

- Manager Database 제품과 위치를 코드에 고정하지 않는다. `application.yml`에 DB 주소를 적지 않는다.
- 한 제품에만 있는 SQL 기능을 업무 로직에 쓰지 않는다.
- Flyway migration은 `mariadb`와 `postgresql`에 같은 version으로 함께 추가하고, 이미 적용된 migration은 고치지 않는다.
- Hibernate `ddl-auto`는 `validate`만 쓴다. `update`로 바꾸지 않는다. ([ADR 0013](decisions/0013-manager-database-portability.md))

### Frontend

- `manager-web`에 Backend 주소를 넣지 않는다. `/api`는 개발 서버에서는 Vite proxy가, Docker에서는 Nginx가 Backend로 넘긴다. 같은 origin이라 CORS 설정이 없다.
- 색과 간격은 `src/index.css`의 token으로 쓰고 화면에서 hex 값을 직접 쓰지 않는다. 화면 원칙은 [DESIGN.md](DESIGN.md)에 있다.

## 공통 관심사

- **수집 대상 전달**: `manager-api`가 `GET /api/prometheus/sd/blackbox`와 `GET /api/prometheus/sd/snmp`로 Prometheus `http_sd_configs` 형식의 대상 목록을 준다. Prometheus는 blackbox를 15초, snmp를 30초마다 조회한다. 이 API는 조회마다 Manager Database를 읽으므로 응답이 느려지지 않게 한다. Database Target용 `/api/prometheus/sd/mariadb`는 아직 없다.
- **인증·권한**: 사용자 로그인과 권한 확인이 없다. 외부에서 들어오는 요청 가운데 Alertmanager webhook은 Bearer token(`alertmanager_webhook_token`)으로, Telegram webhook은 `X-Telegram-Bot-Api-Secret-Token` header의 webhook secret으로 검증한다.
- **오류 처리**: `common.exception`의 `ApiExceptionHandler`가 예외를 Problem Details 응답으로 바꾼다. `server.error`가 예외 메시지와 stack trace를 응답에 넣지 않는다.
- **관측**: `manager-api`는 actuator의 `health`, `info`, `prometheus` endpoint만 연다. `manager-web`의 Nginx는 `/actuator`를 넘기지 않는다. Prometheus는 Docker network 안에서 `manager-api:8080`을 직접 수집한다.
- **설정과 비밀**: 일반 설정은 `.env`의 환경 변수로, 비밀은 `/run/secrets` 아래 파일을 Spring Boot config tree(`spring.config.import`)로 읽는다. 파일 목록은 [DEVELOPMENT.md의 비밀 파일](DEVELOPMENT.md#비밀-파일)에 있다.
- **Manager Database 연결**: 연결 정보는 환경 변수(`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`)와 secret 파일로 받는다. MariaDB와 PostgreSQL JDBC Driver를 모두 포함하고, `spring.flyway.locations`의 `{vendor}` 자리표시자로 JDBC URL에 맞는 migration 디렉터리를 고른다.
- **시간**: Manager Database에는 UTC로 저장한다. 화면은 browser 시간대로, Telegram 메시지는 `MANAGER_TIME_ZONE`(기본 `Asia/Seoul`)으로 표시한다.

## 주요 결정

- [0001. Prometheus Target을 HTTP Service Discovery로 관리한다](decisions/0001-prometheus-http-service-discovery.md)
- [0002. Blackbox SD entry를 Check Target 단위로 만든다](decisions/0002-sd-entry-per-check-target.md)
- [0003. Monitoring Check를 Server에서 분리한다](decisions/0003-separate-check-from-server.md)
- [0004. HTTP 성공 조건과 Timeout을 Check마다 받지 않는다](decisions/0004-fixed-blackbox-modules.md)
- [0005. Alertmanager는 Manager Webhook 하나로 보내고 알림 대상은 Manager가 정한다](decisions/0005-alertmanager-single-webhook-routing.md)
- [0006. 장애 한 번을 fingerprint + startsAt으로 식별하고 중복 전송은 짧은 창으로 막는다](decisions/0006-alert-event-identity-and-dedupe.md)
- [0007. 알림 전송에 Message Queue를 쓰지 않는다](decisions/0007-no-message-queue-for-notifications.md)
- [0008. Telegram 메시지를 plain text로 보낸다](decisions/0008-telegram-plain-text-messages.md)
- [0009. 서버 Resource를 Agent 없이 SNMP로 수집한다](decisions/0009-agentless-snmp-resource-monitoring.md)
- [0010. SNMP Credential을 Manager가 만든 snmp_exporter auth config로 전달한다](decisions/0010-snmp-credentials-via-exporter-auth-config.md)
- [0011. Storage Resource를 SNMP index가 아니라 hrStorageDescr로 식별한다](decisions/0011-storage-resource-identity.md)
- [0012. Secret을 Docker Secret과 Spring config tree로 넣는다](decisions/0012-secrets-via-docker-secret-config-tree.md)
- [0013. Manager Database를 제품별 Flyway Migration으로 이식 가능하게 둔다](decisions/0013-manager-database-portability.md)
