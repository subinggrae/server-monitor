# 0008. 다른 앱에 도입할 수 있는 모니터링 코어

- 상태: 구현됨
- 작성일: 2026-10-19
- 관련: [ADR 0002](../decisions/0002-scrape-interval-via-http-sd.md), [ADR 0004](../decisions/0004-os-exporter-collection.md), [ADR 0005](../decisions/0005-common-resource-recording-rules.md), [#14](https://github.com/subinggrae/server-monitor/issues/14), [#15](https://github.com/subinggrae/server-monitor/issues/15), [#18](https://github.com/subinggrae/server-monitor/issues/18)

## 배경

이 시스템은 최종적으로 Prometheus, Blackbox Exporter, node_exporter, Alertmanager로 된 모니터링 코어를 따로 빌드하고, 거기에 백엔드 API, 화면, DB를 붙여 다른 앱이 모니터링을 도입하게 한다. 도입하는 앱은 자기 용어사전에 맞게 DB의 테이블과 열 이름을 바꿀 수 있어야 한다. 기능은 다음 네 가지면 된다(사람 확인: 2026-10-19).

- 어떤 앱을 몇 초마다 상태 확인할지
- 어떤 서버의 CPU, Memory를 몇 초마다 수집할지
- 어떤 임계치와 상태 조건으로 알림을 보낼지
- 알림을 어떤 방법으로 보낼지

지금 구조는 앞의 두 목표를 막고 있다.

- **코어가 앱 없이 서지 못한다**: Prometheus, Blackbox Exporter, Alertmanager가 앱과 같은 `compose.yml`에 있다. `prometheus.yml`은 감시 대상을 `manager-api`의 HTTP SD 두 곳에서만 받고, `alertmanager.yml`은 Alert를 `manager-api` webhook 한 곳으로만 보낸다. 앱 주소 `manager-api:8080`이 두 파일의 다섯 곳에 박혀 있다. node_exporter는 이 시스템이 띄우지 않고 사람이 서버마다 설치하며, 배포 묶음이 없다(개발용 테스트 컨테이너만 있다).
- **코어와 앱 사이의 약속이 문서로 없다**: 앱이 HTTP SD에 어떤 label을 실어야 하는지, webhook으로 무엇이 오는지, 앱이 Prometheus에서 어떤 series를 읽는지가 코드와 ARCHITECTURE.md의 경계 문장에 흩어져 있다. 다른 앱이 이 약속만 보고 붙을 수 없다.
- **테이블과 열 이름이 여러 곳에 흩어져 있다**: 이름은 JPA Entity 어노테이션 83곳과 Flyway migration V1~V9에 있다. 이미 적용한 migration은 고칠 수 없어서, 한 테이블의 지금 이름과 열 목록을 보려면 여러 migration을 따라가야 한다. 네이티브 SQL은 없다. 지금 설치를 이어 받지 않으므로 V1~V9를 기준 migration 하나로 바꾼다. 이 결정은 ADR로 남기고, AGENTS.md의 "이미 있는 migration은 고치지 않는다" 규칙에 기준을 새로 잡을 때의 예외를 더한다(사람 확인: 2026-10-19).
- **쓰지 않는 구조가 남아 있다**: 2026-10-19에 Manager Database의 모든 열과 인덱스가 어디에서 읽히는지 확인했다([#18](https://github.com/subinggrae/server-monitor/issues/18)). 리소스 선택의 `enabled`는 바꾸는 코드가 없어 늘 켜져 있다. 장애 기록의 `description`은 저장만 하고 어디에서도 읽지 않는다. 인덱스 `ix_monitored_resources_server`와 `ix_alert_events_check_id`는 쓰는 조회가 없다. 설정 키 `alertmanager.base-url`, `exporters.blackbox-url`, `exporters.mysqld-url`은 읽는 코드가 없는데 비어 있으면 Manager가 기동하지 않는다. 부르는 곳이 없는 repository 메서드 둘, 쓰이지 않는 Telegram client 기본 주소, `package-info.java`만 있는 빈 패키지 넷(`threshold`, `grafana`, `database`, `database/provider`), 원문이 저장소에 없는 `(CLAUDE.md N번)` 주석도 있다. 도입하는 쪽이 읽고 이름을 바꿀 대상이 늘어나므로, 기준 migration을 새로 쓸 때 함께 걷어낸다.

나머지 두 목표 가운데 임계치·조건 알림은 [#23](https://github.com/subinggrae/server-monitor/issues/23), 알림 방법은 [#24](https://github.com/subinggrae/server-monitor/issues/24)에서 따로 요구사항을 쓴다. 코어와 앱의 약속이 먼저 정해져야 알림을 어디에서 판단할지 정할 수 있기 때문이다.

## 목표

- 코어(Prometheus, Blackbox Exporter, Alertmanager)를 앱 없이 따로 빌드하고 띄울 수 있다.
- 감시 대상 서버에 node_exporter를 설치하는 배포 묶음(compose 파일과 설치 안내)이 코어와 따로 있다. Docker가 없는 서버는 안내대로 바이너리를 설치한다. windows_exporter는 지금처럼 설치 안내만 둔다(사람 확인: 2026-10-19).
- 코어와 앱 사이의 약속(통합 계약)이 문서 하나에 있다. 앱은 그 문서만 보고 코어에 붙을 수 있다.
- 코어가 부를 앱 주소와 webhook token은 코어 설정 한곳에서 바꾼다.
- 이 저장소의 백엔드, 화면, DB는 통합 계약으로 코어에 붙는 앱 하나다. 지금 기능은 그대로 동작한다.
- DB 테이블과 열 이름은 소스의 정해진 곳에만 있다. 도입하는 쪽은 그곳만 고쳐 빌드하면 이름을 바꿀 수 있고, 그 절차가 문서에 있다.
- 동작에 영향이 없는 열, 인덱스, 설정 키, 코드가 남지 않는다. 하지 않는 것에 적은 구조는 예외다.

## 하지 않는 것

- 코어만으로 감시와 알림이 동작하게 하는 것. 파일로 대상을 적거나 Alertmanager가 직접 알림을 보내지 않는다. 코어만 띄우면 대상이 없는 상태로 떠 있다(사람 확인: 2026-10-19).
- 설치할 때 설정으로 테이블·열 이름을 바꾸게 하는 것. 도입하는 쪽이 소스를 고친다(사람 확인: 2026-10-19).
- Prometheus TSDB의 metric 이름과 label 이름을 바꿀 수 있게 하는 것.
- 사용자가 정하는 임계치와 상태 조건으로 알림을 보내는 것([#23](https://github.com/subinggrae/server-monitor/issues/23)).
- 일반 webhook 알림을 더하고 Telegram을 선택 기능으로 바꾸는 것([#24](https://github.com/subinggrae/server-monitor/issues/24)). Telegram은 남긴다(사람 확인: 2026-10-19).
- 알림 연결의 `warning_enabled`, `critical_enabled`, `enabled` 열과 화면의 "경고"·"심각" 선택, severity에 따른 발송 분기를 지우는 것. 임계치 알림([#23](https://github.com/subinggrae/server-monitor/issues/23))이 Warning·Critical을 쓰므로 그때까지 남긴다(사람 확인: 2026-10-19).
- 읽는 곳이 없는 기록용 열을 지우는 것: getter가 없는 `created_at`·`updated_at`, 장애 기록의 `created_at`·`check_target_id`, 발송 기록의 `error_message`. 장애나 발송 실패를 조사할 때 DB를 직접 보는 용도다(사람 확인: 2026-10-19).
- 화면에 보이는 설명과 표시 열(`*.description`, `server_targets.environment`, 장애 기록의 `severity`·`summary`·`instance`·`alert_name`·`status`·`check_id`), Grafana 링크 주소(`GRAFANA_URL`), `notification.dedupe-window` 설정 키와 `AlertIngestService`를 정리하는 것.
- OS exporter SD의 `server_name`, `environment`, `os` label을 지우는 것. 읽는 규칙은 없지만 지우면 series가 끊긴다(사람 확인: 2026-10-19).
- 서버를 꺼도 리소스 찾기가 되는 문제([#21](https://github.com/subinggrae/server-monitor/issues/21))와 Check 이름·환경 label이 바뀔 때의 영향([#22](https://github.com/subinggrae/server-monitor/issues/22))을 고치는 것.
- exporter 접근에 TLS와 인증을 거는 것([#15](https://github.com/subinggrae/server-monitor/issues/15)).
- 코어와 앱을 다른 호스트에 두는 것. 이번에는 같은 호스트의 Docker 네트워크로 붙인다. 다른 호스트에 두려면 SD 응답과 webhook이 네트워크를 건너므로 TLS와 함께 [#15](https://github.com/subinggrae/server-monitor/issues/15)에서 다룬다(사람 확인: 2026-10-19).
- 지금 설치의 데이터(Check, 서버, 알림 연결, 장애 기록)를 이어 받는 것. 이 변경을 배포할 때는 DB를 새로 만들고 다시 등록한다(사람 확인: 2026-10-19).
- Grafana를 코어에 넣는 것.

## 사용자 경험

사용자는 세 부류다. 코어 운영자, 감시 대상 서버 담당자, 코어를 도입하는 앱의 개발자다. 화면을 쓰는 사람이 겪는 동작은 바뀌지 않는다.

1. 코어 운영자가 코어 설정에 앱 주소와 webhook token을 넣고 코어만 띄운다. 앱이 아직 없으면 Prometheus, Blackbox Exporter, Alertmanager가 감시 대상 없이 떠 있다.
2. 감시 대상 서버 담당자가 node_exporter 배포 묶음으로 서버에 node_exporter를 띄운다.
3. 앱 개발자가 통합 계약 문서를 보고 앱을 붙인다. 이 저장소의 백엔드, 화면, DB를 쓰면 띄우기만 하면 된다.
4. 도입하는 쪽의 개발자가 문서의 절차대로 테이블과 열 이름을 자기 용어로 고치고 빌드해 새 DB에 설치한다.

## 동작

### 정상 동작

- **S1. 코어만 띄우기**
  - 상황: 앱이 없다.
  - 행동: 코어 운영자가 코어만 띄운다.
  - 결과: Prometheus, Blackbox Exporter, Alertmanager가 모두 정상 상태로 뜬다. Prometheus는 HTTP SD를 부르지 못해 감시 대상이 없다고 보고, 앱이 뜨면 다음 SD 갱신 때 대상을 받는다.
- **S2. 앱 붙이기**
  - 상황: 코어가 떠 있다.
  - 행동: 앱을 띄우고 화면에서 Check와 서버를 등록한다.
  - 결과: 코어를 다시 띄우지 않고 등록한 대상을 정한 주기로 수집하고, Check가 실패하면 Alert가 앱 webhook으로 온다. 지금 이 저장소의 기능이 모두 그대로 동작한다.
- **S3. 앱 주소와 token 바꾸기**
  - 상황: 앱을 다른 주소로 옮겼다.
  - 행동: 코어 운영자가 코어 설정 한곳에서 앱 주소와 token을 바꾸고 코어를 다시 띄운다.
  - 결과: Prometheus의 SD 호출과 수집, Alertmanager의 webhook이 모두 새 주소로 간다. 다른 파일은 고치지 않는다.
- **S4. node_exporter 설치**
  - 상황: 감시 대상 Linux 서버에 node_exporter가 없다.
  - 행동: 서버 담당자가 배포 묶음으로 node_exporter를 띄우고, 앱 화면에서 그 서버의 수집을 켠다.
  - 결과: 연결 시험이 통과하고, 정한 주기로 CPU, Memory, Storage가 수집되어 화면에 보인다.
- **S5. 테이블·열 이름 바꾸기**
  - 상황: 도입하는 앱의 용어사전이 이 저장소와 다르다.
  - 행동: 개발자가 문서의 절차대로 정해진 곳에서 테이블과 열 이름을 고치고 빌드한 뒤, 빈 DB에 설치한다.
  - 결과: 고친 이름으로 테이블이 만들어지고 모든 기능이 동작한다. 정해진 곳 밖의 소스, 코어 설정, 통합 계약은 고치지 않는다. 고친 이름은 HTTP SD 응답, webhook 처리, Prometheus series에 영향이 없다.
- **S6. 새 DB에 설치**
  - 상황: 빈 MariaDB나 PostgreSQL이 있다.
  - 행동: 이 변경의 앱을 띄운다.
  - 결과: 테이블이 만들어지고 Manager가 기동한다. 한 테이블의 지금 이름과 열 목록을 migration 하나에서 볼 수 있다. MariaDB와 PostgreSQL에서 같은 논리 schema다.

### 예외 동작

- **S7. token이 다른 webhook 요청**
  - 상황: 코어와 앱의 webhook token이 다르다.
  - 행동: Alertmanager가 Alert를 보낸다.
  - 결과: 앱이 거부하고 Alert를 기록하지 않는다. 지금 동작과 같다.
- **S8. 앱이 멈춘 동안의 Alert**
  - 상황: 앱이 멈췄다.
  - 행동: Check가 실패해 Alert가 생긴다.
  - 결과: 코어는 멈추지 않는다. Alertmanager가 다시 보내고, 앱이 뜬 뒤 다음 전송에서 알림이 간다. 앱이 멈춘 동안 Prometheus는 마지막으로 받은 SD 목록으로 수집을 이어 간다.

### 보안

- 접근 권한: 바뀌지 않는다. 화면용 API에는 지금처럼 인증이 없다.
- 외부 입력: HTTP SD 응답(코어가 앱에서 받음), webhook 요청(앱이 코어에서 받음). 코어와 앱은 같은 호스트의 Docker 네트워크로만 통신하고, 호스트에 여는 포트는 지금처럼 `127.0.0.1`에만 묶는다.
- 민감한 정보: webhook token. 코어와 앱이 같은 값을 비밀값 파일로 받는다. 설정 파일, 통합 계약 문서, 로그에 값을 넣지 않는다.
- 남길 보안 이벤트: 지금과 같다. token이 다른 webhook 요청은 거부한다.

### 바뀌지 않는 것

- HTTP SD 두 곳의 경로, 응답 형식, label과 `__scrape_interval__`.
- Alertmanager webhook의 경로, Bearer 검사, payload.
- Alert 규칙, 공통 recording rule의 series 이름과 label, `manager-os` job의 keep 목록.
- 화면에서 쓰는 기능과 API.
- 이름을 바꾸지 않으면 테이블과 열 이름은 지금과 같다.
- MariaDB와 PostgreSQL 모두 지원.

## 완료 기준

- [x] C1. 코어 설정만으로 코어를 띄우면 세 컨테이너가 정상 상태가 되고, Prometheus 설정 검사와 Alert 규칙 테스트가 통과한다. (S1) — 확인: `promtool check config`, `promtool test rules`, 코어만 띄우는 수동 확인(2026-10-19 확인. Alert 규칙의 테스트는 없고 recording rule 테스트만 통과했다)
- [x] C2. 코어에 이 저장소의 앱을 붙이면 Check 등록, 수집, 실패 알림까지 지금처럼 동작한다. (S2, S8) — 확인: 기존 E2E 통과
- [x] C3. 앱 주소와 token을 코어 설정 한곳에서 바꾸면 SD 호출, 수집, webhook이 모두 새 주소로 간다. (S3) — 확인: 다른 주소로 바꿔 띄운 뒤 Prometheus target 화면과 Alertmanager 전송 확인
- [x] C4. 배포 묶음으로 띄운 node_exporter를 등록하면 연결 시험이 통과하고 CPU, Memory, Storage가 보인다. (S4) — 확인: 개발 환경에서 수동 확인(2026-10-19, Docker Desktop에서 `rslave`를 뺀 임시 설정으로 확인. 실제 Linux 서버는 확인하지 못했다)
- [x] C5. 테이블과 열 이름이 정해진 곳 밖의 소스에 없다. 한 테이블과 한 열의 이름을 절차대로 바꾸면 백엔드 테스트가 MariaDB와 PostgreSQL에서 모두 통과한다. (S5) — 확인: 이름 위치 검사, 이름을 바꾼 상태의 통합 테스트
- [x] C6. 빈 MariaDB와 PostgreSQL에 설치하면 기동하고, 두 DB의 schema가 Entity와 맞는다. 테이블마다 지금 모양을 migration 하나에서 볼 수 있다. (S6) — 확인: 두 DB의 migration 통합 테스트
- [x] C7. token이 다른 webhook 요청을 거부하고 기록하지 않는다. (S7) — 확인: 기존 webhook 테스트 통과
- [x] C8. 통합 계약 문서가 앱이 제공할 것(HTTP SD 두 곳, webhook)과 코어가 제공할 것(Prometheus 조회 API, series 이름과 label)을 설명하고, 이름 바꾸기 절차 문서가 있다. 문서 검사가 통과한다. — 확인: `node scripts/check-docs.mjs`, 문서 리뷰
- [x] C9. 저장소의 코드, 설정, 문서에 `monitored_resources.enabled`, `alert_events.description`, 인덱스 `ix_monitored_resources_server`·`ix_alert_events_check_id`, 설정 키 `alertmanager.base-url`·`exporters.blackbox-url`·`exporters.mysqld-url`, 부르는 곳 없는 repository 메서드, Telegram client 기본 주소, 빈 패키지, `(CLAUDE.md N번)` 표기가 남지 않는다. 기록 문서는 제외한다. 지운 세 설정 키의 환경 변수가 남아 있어도 기동한다. — 확인: 코드 검색, 설정 바인딩 테스트

## 확인 필요

없음.
