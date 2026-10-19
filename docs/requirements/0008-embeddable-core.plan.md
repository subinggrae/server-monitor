# 0008. 다른 앱에 도입할 수 있는 모니터링 코어 구현 계획

- 요구사항: [0008. 다른 앱에 도입할 수 있는 모니터링 코어](0008-embeddable-core.md)
- 상태: 승인

## 접근 방법

일을 세 갈래로 나눈다. 코어 분리, DB 이름 정리, 쓰지 않는 구조 걷어내기다. 기준 migration을 새로 쓰는 김에 쓰지 않는 열과 인덱스를 함께 걷어낸다.

**코어 분리**: Prometheus, Blackbox Exporter, Alertmanager를 `infrastructure/compose.yml`로 옮긴다. 코어는 공식 이미지에 설정 파일을 붙여 띄우므로 따로 이미지를 빌드하지 않는다. 코어가 `monitoring` 네트워크를 만들고, 앱의 `compose.yml`은 그 네트워크를 `external`로 받아 붙는다. 그래서 코어를 먼저 띄운다.

앱 주소는 코어 설정의 환경 변수 `MONITORING_APP_ADDRESS`(`host:port`, 예: `manager-api:8080`) 하나에 둔다. Prometheus와 Alertmanager는 설정 파일 안에서 환경 변수를 펼치지 못한다. 그래서 두 컨테이너가 시작할 때 공용 스크립트 `infrastructure/render-config.sh`를 먼저 실행한다. 이 스크립트는 설정 템플릿의 자리표시자를 주소로 바꿔 컨테이너 안의 쓸 수 있는 경로에 쓴 뒤 원래 프로세스를 실행한다. 두 이미지에 `/bin/sh`와 `sed`가 있는 것은 확인했다(2026-10-19, Prometheus v3.14.0, Alertmanager v0.34.1). webhook token은 지금처럼 `infrastructure/secrets/alertmanager_webhook_token` 파일을 코어와 앱이 함께 읽는다.

검토했지만 고르지 않은 방법은 다음과 같다.

| 방법 | 고르지 않은 이유 |
|---|---|
| 앱이 Docker 네트워크 별칭으로 붙고, 코어 설정에는 별칭을 고정 | 앱 주소를 바꾸려면(S3) 코어 설정 파일을 고쳐야 한다 |
| Compose `configs.content`에 설정을 인라인으로 두고 Compose 변수로 치환 | 설정이 compose 파일 안으로 들어가 `promtool check config`로 검사하기 어렵다 |
| 루트 `compose.yml`이 `include`로 코어를 함께 띄움 | 코어를 따로 띄운 뒤 앱을 띄우면 두 프로젝트의 `container_name`이 겹친다 |
| Alertmanager만 `url_file`로 주소를 받음 | Prometheus `http_sd_configs`에는 같은 기능이 없어 방식이 둘이 된다 |

**DB 이름 정리**: 테이블과 열 이름을 Entity 어노테이션과 vendor별 기준 migration 두 파일(`V1__baseline.sql`)에만 둔다. 이름을 바꾸는 사람은 Entity와 두 SQL 파일만 고친다. 둘이 어긋나면 `ddl-auto: validate`가 기동을 막는다. MariaDB와 PostgreSQL 통합 테스트가 그 어긋남을 빌드 단계에서 잡는다. 네이티브 SQL이 없으므로, 열 이름이 다른 곳에 나오지 않는다는 것은 "네이티브 SQL을 쓰지 않는다"는 검사로 지킨다.

검토했지만 고르지 않은 방법은 다음과 같다.

| 방법 | 고르지 않은 이유 |
|---|---|
| Java 상수 클래스에 모든 이름을 두고 migration은 Flyway placeholder로 받음 | migration이 placeholder로 가득 차 테이블 모양을 migration에서 바로 읽기 어렵다(S6) |
| Entity에 이름을 쓰지 않고 Hibernate naming strategy가 필드 이름에서 만듦 | 열 이름을 바꾸려면 Java 필드와 그 필드를 쓰는 JPQL을 모두 고쳐야 한다 |

**쓰지 않는 구조 걷어내기**: 요구사항의 C9 목록대로 지운다. 지운 세 설정 키는 `@ConfigurationProperties`의 기본 `ignoreUnknownFields=true`라 환경 변수가 남아 있어도 기동한다. 이 동작은 테스트로 고정한다.

## 바뀌는 부분

```text
지금                                      바뀐 뒤
compose.yml (하나)                        infrastructure/compose.yml  ← 코어, 먼저 띄움
  manager-api ─┐                            prometheus ─ blackbox-exporter
  manager-web  │ monitoring 네트워크         alertmanager
  manager-db   │                            (네트워크 monitoring을 만듦)
  prometheus   │                                 ▲  MONITORING_APP_ADDRESS
  alertmanager │                                 │  (SD 두 곳, scrape, webhook)
  blackbox ────┘                            compose.yml  ← 앱
                                              manager-api, manager-web, manager-db
                                              (monitoring 네트워크에 external로 붙음)
                                          infrastructure/node-exporter/compose.yml
                                              ← 감시 대상 서버에서 따로 띄움
```

**코어와 배포 묶음**

- `infrastructure/compose.yml`(새 파일): `prometheus`, `alertmanager`, `blackbox-exporter`를 옮겨 온다. 네트워크 `monitoring`을 만들고, 세 컨테이너에 healthcheck를 더한다. 비밀값은 `alertmanager_webhook_token`만 받는다.
- `infrastructure/.env.example`(새 파일): `MONITORING_APP_ADDRESS=manager-api:8080`. `infrastructure/.env`는 기존 `.gitignore`의 `.env` 규칙으로 Git에 올라가지 않는다.
- `infrastructure/render-config.sh`(새 파일): 템플릿을 펼치고 `exec`로 원래 명령을 실행한다.
- `infrastructure/prometheus/prometheus.yml`: `manager-api` job을 지우고, 남은 `manager-api:8080` 두 곳(HTTP SD)을 자리표시자 `@MONITORING_APP_ADDRESS@`로 바꾼다. 컨테이너는 펼친 결과를 읽는다.
- `infrastructure/alertmanager/alertmanager.yml`: webhook 주소의 `manager-api:8080`을 같은 자리표시자로 바꾼다.
- `infrastructure/node-exporter/compose.yml`(새 파일): 감시 대상 Linux 서버용 배포 묶음. `network_mode: host`, `pid: host`, `/`를 읽기 전용으로 `/host`에 붙이고 `--path.rootfs=/host`를 준다. 이미지는 `os-test-node-exporter`와 같은 버전으로 고정한다.

**앱**

- `compose.yml`: 코어 세 서비스를 빼고 `monitoring` 네트워크를 `external: true`로 받는다. `ALERTMANAGER_URL`, `BLACKBOX_EXPORTER_URL`, `MYSQLD_EXPORTER_URL`을 지운다. 개발용 `os-test-node-exporter`는 남긴다.
- `.env.example`: 위 세 변수와 그 설명을 지운다.
- `backend/src/main/resources/db/migration/{mariadb,postgresql}/`: V1~V9를 지우고 `V1__baseline.sql`을 새로 쓴다. 내용은 [데이터 모델 변경](#데이터-모델-변경)에 있다.
- `MonitoredResource`, `ServerResourceService`: `enabled` 필드와 `isEnabled()` 분기를 지운다.
- `AlertEvent`, `AlertEventService`: `description` 필드와 저장 코드를 지운다.
- `MonitoringManagerProperties`, `application.yml`: `alertmanager`, `exporters` 항목과 검증을 지운다.
- `application.yml`, `backend/build.gradle`: 수집하는 곳이 없어지므로 actuator의 `prometheus` 노출, `management.prometheus`와 metric tag 설정, 런타임 의존성 `micrometer-registry-prometheus`를 지운다. 런타임 의존성 변경이므로 위험 영역이다. `health`, `info`는 healthcheck가 쓰므로 남긴다.
- `NotificationDeliveryRepository.findAllByAlertEventIdOrderByIdAsc`, `CheckNotificationBindingRepository.deleteAllByCheckId`를 지운다.
- `TelegramClientConfiguration`: 쓰이지 않는 `baseUrl(...)`을 지운다. `telegram.api-base-url` 키는 `TelegramClient`가 쓰므로 남긴다.
- `threshold`, `grafana`, `database`, `database/provider` 패키지를 지운다.
- `FilesystemFilter`, `TelegramDestinationRepository`의 주석에서 열 이름을 빼고 필드 이름으로 쓴다.
- `(CLAUDE.md N번)` 표기를 백엔드, 화면, `infrastructure/`에서 지운다. 표기 없이 뜻이 통하지 않는 문장은 ARCHITECTURE.md의 경계를 가리키게 고친다. 동작은 바뀌지 않는다.

**테스트 설정**

- `backend/build.gradle`: 테스트 의존성 `spring-boot-testcontainers`, `testcontainers-mariadb`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`를 더한다. `test`에서 `integration` tag를 빼고, 그 tag만 실행하는 `integrationTest` 작업을 더한다. 테스트 설정만 바꾸므로 빌드 설정 변경이 아니다.
- `backend/src/test/`: [테스트 계획](#테스트-계획)의 새 테스트.

**문서**

- `docs/INTEGRATION.md`(새 문서): 통합 계약과 테이블·열 이름 바꾸기 절차. 다른 앱의 개발자가 이 문서 하나만 읽고 붙어야 하므로 ARCHITECTURE.md에 넣지 않고 따로 둔다.
- `README.md`: 코어와 앱을 띄우는 순서, node_exporter 설치(배포 묶음, Docker가 없을 때 바이너리, 방화벽), windows_exporter 설치 안내.
- `docs/ARCHITECTURE.md`와 `architecture-overview.svg`: 실행 단위를 코어와 앱으로 나눈다. 코드 지도에서 빈 패키지 행을 지우고, 지켜야 할 경계에 통합 계약과 이름 위치를 더한다.
- `docs/DEVELOPMENT.md`: 준비에 `infrastructure/.env`를, 명령 표에 코어 실행, 전체 실행 순서, 코어 설정 검사, 통합 테스트를 더한다.
- `docs/TESTING.md`와 `test-scope.svg`: 종류별 기준에 통합 테스트 행을 더한다. "DB까지 검사하는 것은 E2E뿐"이라는 문장과 그림을 고친다.
- `docs/decisions/0006-baseline-migration.md`, `0007-core-app-contract.md`(새 ADR): [남길 결정](#남길-결정).
- `AGENTS.md`: 요구사항에서 승인한 대로 "이미 있는 migration은 고치지 않는다"에 기준을 새로 잡을 때의 예외를 더한다. 문서 표에는 `docs/INTEGRATION.md` 행을 더하지 않고, ARCHITECTURE.md에서 링크한다(사람 확인: 2026-10-19).

### 데이터 모델 변경

- migration V1~V9를 vendor마다 `V1__baseline.sql` 하나로 바꾼다. 테이블 10개, 열 이름, 제약 이름, 인덱스 이름은 지금 V9 뒤의 모양과 같다. 다음은 예외다.
  - `monitored_resources.enabled`를 뺀다.
  - `alert_events.description`을 뺀다.
  - 인덱스 `ix_monitored_resources_server`, `ix_alert_events_check_id`를 뺀다. MariaDB의 `fk_monitored_resources_server`는 `uk_monitored_resources_key`(앞 열 `server_id`)를 인덱스로 쓴다.
- 이미 V1~V9를 적용한 DB는 Flyway 검증에 실패해 기동하지 않는다. 요구사항대로 데이터를 이어 받지 않고 DB를 새로 만든다.
- Entity: `MonitoredResource.enabled`, `AlertEvent.description` 필드를 지운다.

### 계약 변경

HTTP SD, webhook, Alert 규칙의 label은 바뀌지 않는다. 바뀌는 것은 코어가 앱을 부르는 주소를 정하는 곳뿐이다. `docs/INTEGRATION.md`에 다음을 쓴다.

- **앱이 제공할 것**
  - `GET /api/prometheus/sd/blackbox`: 지금 응답 형식과 label(`check_id`, `check_target_id`, `check_type`, `module`, `environment`, `check_name`, `__scrape_interval__`)
  - `GET /api/prometheus/sd/os-exporter`: label(`server_id`, `server_name`, `environment`, `os`, `__scrape_interval__`)
  - `POST /api/integrations/alertmanager/webhook`: Alertmanager webhook payload, `Authorization: Bearer <token>`. token이 다르면 거부한다
- **지우는 것**: 코어가 앱의 `/actuator/prometheus`를 수집하던 `manager-api` job. Alert 규칙과 recording rule, 화면이 이 metric을 읽지 않는다. 그동안 쌓인 `job="manager-api"` series는 더 이어지지 않는다(사람 확인: 2026-10-19)
- **코어가 제공할 것**
  - Prometheus HTTP API(`http://prometheus:9090/api/v1/query`), Docker 네트워크 `monitoring`
  - series: `probe_success{job="manager-blackbox"}`, `up{job="manager-os"}`, recording rule `manager:resource_usage_percent`, `manager:resource_used_bytes`, `manager:resource_size_bytes`(label `server_id`, `resource_type`, `resource_key`)
  - Alert 규칙이 붙이는 label과 annotation(`monitoring-checks.yml`), Alertmanager의 `group_by`와 재전송 간격
- **코어 설정**: `MONITORING_APP_ADDRESS`, `infrastructure/secrets/alertmanager_webhook_token`

## 위험과 되돌리기

- **컨테이너 안에서 펼친 설정을 쓸 곳이 없음**(Prometheus 이미지는 `nobody`로 실행된다): 첫 작업에서 `/tmp`에 쓸 수 있는지 확인한다. 쓸 수 없으면 데이터 volume 아래에 쓰고, 이 결정을 진행 중 발견에 적는다.
- **코어를 띄우지 않고 앱을 띄우면 `network monitoring not found`로 실패**: 의도한 동작이다. DEVELOPMENT.md와 README에 순서를 쓴다.
- **기존 개발 DB 볼륨**: 기준 migration으로 바뀌어 기존 `manager-db-data`로는 기동하지 않는다. 볼륨을 지우는 일은 사람이 확인한 뒤 한다.
- **Docker Desktop(Windows)에서 `network_mode: host`인 배포 묶음 확인**(C4): host 네트워크 기능이 꺼져 있으면 Prometheus 컨테이너가 닿지 않는다. 그때는 리눅스 VM에서 확인하거나, 확인하지 못한 것으로 보고한다.
- **되돌리기**: 코드와 설정은 커밋을 되돌린다. migration은 데이터를 이어 받지 않으므로, 되돌린 뒤에는 DB를 다시 만든다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 코어와 앱이 다른 compose 프로젝트가 되지만 같은 Docker 네트워크를 쓰므로, 네트워크 경계는 바뀌지 않는다. 감시 대상 서버에 node_exporter 배포 묶음이 생긴다.
- 잘못될 수 있는 것과 막는 방법:
  - 같은 Docker 네트워크의 다른 컨테이너가 가짜 Alert를 보냄(CWE-345) → Bearer 검사는 그대로 둔다. token이 다른 요청을 거부하고 기록하지 않는 통합 테스트를 더한다.
  - 감시 대상 서버의 node_exporter가 인증 없이 열림(CWE-306) → [#15](https://github.com/subinggrae/server-monitor/issues/15)에서 다루는 이미 받아들인 위험이다. 설치 안내에 방화벽 규칙을 쓴다.
  - 배포 묶음의 node_exporter가 뚫렸을 때 서버 전체로 번짐(CWE-250) → `privileged`를 쓰지 않고 루트 파일 시스템을 읽기 전용으로 붙인다. 설정만 고르고 코드는 더하지 않는다.
- 범위에서 뺀 것(TESTING.md의 점검 항목 기준, 사람 확인: 2026-10-19):
  - `MONITORING_APP_ADDRESS`의 주입(CWE-74): 공격자가 이 값을 넣을 경로가 없다. 코어의 `.env`를 고칠 수 있으면 Prometheus 설정도 직접 고칠 수 있다. 형식 검사 코드를 두지 않는다.
  - node_exporter 바이너리 바꿔치기(CWE-494): 이 제품이 구현한 기능이 아니라 공식 릴리스를 받는 설치 방식이고, 같은 릴리스의 체크섬으로는 막지 못한다.
  - 새 테스트 의존성(CWE-1395): 테스트에서만 쓰고 제품에 들어가지 않는다. 의존성 확인은 AGENTS.md의 보안 규칙대로 했다(진행 중 발견).
- 받아들이는 위험: 새로 받아들이는 위험은 없다.

## 테스트 계획

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | 코어 설정 검사 | 펼친 설정에 `promtool check config`, `amtool check-config` 통과(명령은 DEVELOPMENT.md에 씀) |
| C1 | 규칙 테스트 | DEVELOPMENT.md의 규칙 테스트 명령 통과 |
| C1 | 코어만 띄우기(수동) | `docker compose -f infrastructure/compose.yml up -d` 뒤 세 컨테이너가 `healthy` |
| C2 | E2E | 코어 뒤에 앱을 띄우고 `cd frontend && npm run test:e2e` 통과 |
| C3 | 주소 바꾸기(수동) | 앱에 네트워크 별칭을 더하고 주소를 별칭으로 바꿔 코어만 다시 띄우면 SD 두 곳과 webhook이 새 주소로 감 |
| C4 | node_exporter 등록(수동) | 배포 묶음의 node_exporter를 등록하면 연결 시험 통과, CPU, Memory, Storage 표시 |
| C5 | `DatabaseNameLocationTest`(단위) | `src/main`에 네이티브 SQL 사용과 Entity 밖의 테이블 이름이 없음(`./gradlew test` 통과) |
| C5 | 이름 바꾸기(수동) | 임시 worktree에서 테이블 하나와 열 하나를 절차대로 바꾸고 `./gradlew integrationTest` 통과 |
| C6 | `SchemaMigrationIntegrationTest` | MariaDB와 PostgreSQL의 빈 DB에서 앱 context가 뜸(`./gradlew integrationTest` 통과) |
| C7 | `AlertmanagerWebhookIntegrationTest` | token이 다르면 401과 기록 0건, 맞으면 기록 1건(`./gradlew integrationTest` 통과) |
| C8 | 문서 검사 | `node scripts/check-docs.mjs --render` 통과와 그림 확인 |
| C9 | `MonitoringManagerPropertiesTest`(단위) | 세 키가 없어도, 남아 있어도 바인딩 성공(지우기 전에는 첫 테스트가 실패) |
| C9 | 코드 검색 | `git grep` 결과가 `docs/requirements/`, `docs/decisions/`, AGENTS.md의 금지 규칙 밖에 없음 |
| 앱 metric 제거 | 기동 확인(수동) | 전체 실행에서 `/actuator/health`는 200, `/actuator/prometheus`는 404. Prometheus `/targets`에 `manager-api` job이 없음 |

## 작업 순서

- [x] 1. ADR 0006(기준 migration)과 0007(코어와 앱의 통합 계약) 초안, AGENTS.md의 migration 예외 문장
- [x] 2. 통합 테스트 설정(`integrationTest` 작업, Testcontainers 의존성 확인), `SchemaMigrationIntegrationTest`, `AlertmanagerWebhookIntegrationTest`를 지금 migration에서 통과시킴
- [x] 3. `MonitoringManagerPropertiesTest`와 `DatabaseNameLocationTest`를 먼저 쓰고 실패 확인
- [x] 4. 기준 migration `V1__baseline.sql` 두 개, Entity와 서비스에서 `enabled`, `description` 제거. 통합 테스트 통과
- [x] 5. 설정 키 세 개, repository 메서드 둘, Telegram `baseUrl`, 빈 패키지 넷, 열 이름 주석 제거. 단위 테스트 통과
- [x] 5-1. 앱 metric 노출 설정과 `micrometer-registry-prometheus` 제거(위험 영역: 런타임 의존성)
- [x] 6. `(CLAUDE.md N번)` 표기 제거(백엔드, 화면, `infrastructure/`)
- [x] 7. 코어 분리: `infrastructure/compose.yml`, `render-config.sh`, 설정 템플릿, `infrastructure/.env.example`, 루트 `compose.yml`과 `.env.example`. C1 확인
- [x] 8. node_exporter 배포 묶음과 README 설치 안내. C4 확인
- [x] 9. `docs/INTEGRATION.md`(통합 계약, 이름 바꾸기 절차), ARCHITECTURE.md와 그림, DEVELOPMENT.md, TESTING.md와 그림
- [ ] 10. 이름 바꾸기 확인(C5 수동), C3 확인, 전체 실행 뒤 E2E(C2)
  - C2(E2E와 실패 Alert의 webhook 기록), C3, C4는 확인했다. 다음 할 일은 C5 수동 확인이다.
- [ ] 11. 전체 검증(`npm run check`, `./gradlew build`, `./gradlew integrationTest`, 규칙 테스트, 문서 검사)과 C9 코드 검색
- [ ] 12. 새 하위 에이전트에 리뷰를 맡김(완료 기준, 이 계획, diff, 보안 위협의 CWE)
- [ ] 13. 남길 결정을 ADR로 확정하고, 바뀐 동작을 문서로 옮긴 뒤 이 계획을 지움(병합 전 마지막 커밋)

## 진행 중 발견

- 백엔드에 DB를 띄우는 테스트와 Alertmanager webhook 테스트가 없다(2026-10-19, `backend/src/test` 확인). C5, C6, C7의 "기존 테스트"는 이번에 새로 쓴다.
- `FilesystemFilterRuleSyncTest`가 `../infrastructure/prometheus/rules/server-resources.yml`을 읽는다. 코어 파일을 옮기지 않으므로 그대로 동작한다.
- Testcontainers 2.0.5 artifact 네 개는 Maven Central에 있고 Spring Boot 4.1.1이 버전을 관리한다. 라이선스는 MIT다(2026-10-19 확인). LICENSES.md는 패키지 의존성을 기록하지 않으므로(들여온 것 절의 안내) 고치지 않는다.
- `infrastructure/alertmanager/` 안에 Git이 추적하지 않는 빈 디렉터리 `alertmanager.yml;C`가 있다. 이번 범위 밖이라 두고 보고한다.
- `ResourceStatus.DISABLED`는 늘 켜져 있던 `monitored_resources.enabled`에서만 만들어졌다. 화면도 이 값을 따로 다루지 않아 enum과 화면 타입에서 함께 지웠다(`ServerResourceService`, `frontend/src/lib/api.ts`).
- 저장소는 `core.autocrlf`로 체크아웃해서 Windows에서 받은 셸 스크립트가 CRLF가 되면 컨테이너의 `sh`가 실행하지 못한다. `.gitattributes`에 `*.sh text eol=lf`를 더했다.
- Prometheus와 Alertmanager 이미지는 `nobody`로 실행되지만 `/tmp`에 쓸 수 있고 `wget`, `grep`이 있다(2026-10-19 확인). 펼친 설정은 `/tmp`에 쓰고, healthcheck는 `wget`으로 한다.
- Alertmanager의 상태 API(`/api/v2/status`)는 webhook 주소를 가린다. C3은 컨테이너 안의 `/tmp/alertmanager.yml`과 앱에 기록된 Alert로 확인했다.
- Docker Desktop(이 PC)의 VM은 `/`가 shared mount가 아니라서 배포 묶음의 `/:/host:ro,rslave`로 뜨지 않는다. `rslave`는 node_exporter 공식 README의 설정이고, systemd를 쓰는 Linux 서버에서는 동작하므로 배포 묶음은 그대로 두었다. C4는 `rslave`만 뺀 임시 override로 띄우고, host 네트워크 기능이 꺼져 있어 `monitoring` 네트워크의 gateway 주소(172.19.0.1)로 등록해 확인했다. 실제 Linux 서버에서는 확인하지 못했다.
- `MonitoringManagerPropertiesTest`는 "지운 키가 남아 있어도 기동한다"를 확인하므로 지운 설정 키 이름을 담는다. C9 코드 검색의 예외로 둔다.
- 기존 개발 환경의 멈춘 컨테이너 7개, `monitoring` 네트워크, `manager-db-data` 볼륨은 사람의 확인(2026-10-19)을 받고 지운 뒤 새로 만들었다.

## 남길 결정

- ADR 0006: V1~V9를 기준 migration 하나로 바꾸고, 기준을 새로 잡을 때는 이미 있는 migration을 지울 수 있다. 데이터를 이어 받지 않는다.
- ADR 0007: 코어(Prometheus, Blackbox Exporter, Alertmanager)와 앱을 통합 계약으로 나눈다. 앱 주소는 코어 설정 `MONITORING_APP_ADDRESS` 하나로 정하고 시작할 때 설정에 펼친다. 코어는 앱의 metric을 수집하지 않는다. 테이블·열 이름은 Entity와 기준 migration에만 둔다.
