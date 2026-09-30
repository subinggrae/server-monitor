# 아키텍처

Monitoring Manager의 전체 구조와 설계 이유를 설명합니다.

이 문서는 현재 구현 상태가 아니라 **목표 구조**를 설명합니다.
Database Target, Threshold, Grafana 연동은 아직 구현되지 않았습니다.

주요 결정의 대안과 trade-off는 [ADR](../decisions/ADR-decisions/)에 있습니다.

| 주제 | ADR |
| --- | --- |
| Target 관리와 SD entry 단위 | [ADR-0001](../decisions/ADR-decisions/ADR-0001-prometheus-http-service-discovery.md), [ADR-0002](../decisions/ADR-decisions/ADR-0002-sd-entry-per-check-target.md) |
| Check와 Server 분리 | [ADR-0003](../decisions/ADR-decisions/ADR-0003-separate-check-from-server.md) |
| Blackbox module 고정 | [ADR-0004](../decisions/ADR-decisions/ADR-0004-fixed-blackbox-modules.md) |
| 알림 경로 | [ADR-0005](../decisions/ADR-decisions/ADR-0005-alertmanager-single-webhook-routing.md) ~ [ADR-0008](../decisions/ADR-decisions/ADR-0008-telegram-plain-text-messages.md) |
| SNMP Resource 수집 | [ADR-0009](../decisions/ADR-decisions/ADR-0009-agentless-snmp-resource-monitoring.md) ~ [ADR-0011](../decisions/ADR-decisions/ADR-0011-storage-resource-identity.md) |
| Secret 주입 | [ADR-0012](../decisions/ADR-decisions/ADR-0012-secrets-via-docker-secret-config-tree.md) |
| Manager Database 이식성 | [ADR-0013](../decisions/ADR-decisions/ADR-0013-manager-database-portability.md) |

---

## 전체 구성

```text
   운영자
     │  ① 서버 / Check 등록
     ▼
  manager-web ──/api──▶ manager-api ──▶ Manager Database
                              ▲                 │
                              │                 │ ② 활성 Check 조회
                              │                 ▼
                              │        GET /api/prometheus/sd/blackbox
                              │                 │
                              │                 │ ③ 15초마다 조회
                              │                 ▼
                              │            Prometheus
                              │                 │ ④ probe 요청
                              │                 ▼
                              │         Blackbox Exporter
                              │                 │ ⑤ ICMP / TCP / HTTP / HTTPS
                              │                 ▼
                              │            모니터링 대상
                              │
                              └── ⑥ probe_success 조회 → ⑦ UI에 정상 / 위험 표시
```

Check를 추가하거나 지워도 `prometheus.yml`을 수정하지 않습니다.
Prometheus를 다시 시작할 필요도 없습니다.

```text
                  ┌─────────────────┐
   운영자  ─────▶ │   manager-web   │  React SPA (Nginx)
                  └────────┬────────┘
                           │ /api
                  ┌────────▼────────┐
                  │   manager-api   │  Spring Boot
                  └───┬────────┬────┘
                      │        │
        ┌─────────────┘        └──────────────┐
        │                                     │
┌───────▼────────┐                   ┌────────▼─────────┐
│ Manager        │                   │ Telegram Bot API │
│ Database       │                   └──────────────────┘
└───────┬────────┘
        │ Target 목록 / Threshold
┌───────▼─────────────────────────────────────────────┐
│ Prometheus                                          │
│  - HTTP Service Discovery로 manager-api 조회         │
│  - Blackbox / SNMP / mysqld Exporter로 metric 수집   │
└───────┬─────────────────────────────────────────────┘
        │ Alert
┌───────▼────────┐        Webhook
│  Alertmanager  │ ──────────────────▶ manager-api
└────────────────┘

        Prometheus ◀──── Grafana (상세 조회)
```

---

## 구성요소별 역할

| 구성요소 | 역할 |
| --- | --- |
| `manager-web` | 운영자가 쓰는 화면. 설정 입력과 현재 상태 조회 |
| `manager-api` | 설정 저장, Target 목록 제공, Alert 수신, 알림 전송 |
| Manager Database | Manager의 모든 설정을 저장 |
| Prometheus | metric 수집과 Alert 규칙 평가 |
| Alertmanager | Alert 묶기, 중복 제거, Manager로 전달 |
| Blackbox Exporter | ICMP / HTTP / HTTPS / TCP 가용성 체크 |
| SNMP Exporter | CPU / Memory / Storage 수집 |
| mysqld_exporter | Database Target metric 수집 |
| Grafana | 상세 시계열 조회와 분석 |
| Telegram Bot | 운영자에게 알림 전송 |

---

## Manager Database와 Database Target

이름이 비슷해서 자주 헷갈립니다. 완전히 다른 개념입니다.

### Manager Database

Monitoring Manager가 **자기 설정을 저장하는 DB**입니다.

여기에 저장하는 내용은 다음과 같습니다.

```text
Servers
Database Targets
Resources
Thresholds
Telegram Groups
Notification Bindings
Alert History
```

Spring Data JPA로 접근하고, Schema는 Flyway가 관리합니다.

### Database Target

사용자가 **모니터링하려는 DB**입니다.

```text
MariaDB
MySQL
PostgreSQL
AWS RDS MariaDB
```

Manager는 이 DB에 JPA로 붙지 않습니다.
연결 정보만 저장하고, 실제 metric은 mysqld_exporter가 수집합니다.

### 왜 구분하는가

둘을 섞으면 다음 문제가 생깁니다.

* 사용자가 등록한 DB를 Manager 설정 저장소로 잘못 쓰게 됩니다.
* Manager DB를 PostgreSQL로 바꾸면 Database Target 코드까지 영향을 받습니다.
* 인증 정보의 보관 방식이 달라야 하는데 같은 취급을 받게 됩니다.

그래서 package도 나눕니다.

* `kr.co.unidocs.monitoring.config` — Manager Database 연결 설정
* `kr.co.unidocs.monitoring.database` — Database Target 도메인

---

## 두 개의 독립된 도메인

### 무엇인가

Blackbox 모니터링과 서버 Resource 모니터링은 목적이 다릅니다.
그래서 서로 연결하지 않고 따로 둡니다.

```text
Blackbox Monitoring
  MonitoringCheck              사용자가 관리하는 논리적인 확인 단위
  └─ MonitoringCheckTarget     Blackbox가 실제로 확인하는 주소 (N개)

Infrastructure Monitoring
  ServerTarget                 서버 자산
  ├─ ServerSnmpConfig          SNMP 접속 정보 (0..1)
  └─ MonitoredResource         모니터링하기로 고른 CPU / Memory / Storage (N개)

Database Monitoring
  DatabaseTarget               MariaDB, MySQL 등
```

### 왜 Check를 Server 아래에 두지 않는가

같은 애플리케이션이 서버 여러 대에서 동작하는 경우가 많습니다.

```text
전자결재 Health (HTTP)
  ├─ http://10.0.0.11:8080/actuator/health
  ├─ http://10.0.0.12:8080/actuator/health
  └─ http://10.0.0.13:8080/actuator/health
```

사용자는 이것을 "전자결재 Health" 하나로 관리합니다.
이 Check를 10.0.0.11 서버의 하위 항목으로 두면 나머지 두 주소의 소속이 이상해집니다.
그 두 주소는 부모 서버와 아무 관계가 없기 때문입니다.

그래서 Check는 Server에 속하지 않습니다.

* Server 없이 Check를 만들 수 있습니다.
* Server를 지워도 Check는 그대로 남습니다.
* Server 상태를 Blackbox Check로 계산하지 않습니다.

ICMP Check의 대상이 ServerTarget으로 등록되어 있을 필요도 없습니다.

### 왜 Check와 Target을 나눴는가

사용자가 보는 단위(Check)와 Prometheus가 확인하는 단위(Target)가 다르기 때문입니다.

Prometheus는 주소마다 따로 확인해야 어디가 죽었는지 알 수 있습니다.

### 데이터 구조

Schema는 `backend/src/main/resources/db/migration/`의 Flyway Migration이 기준입니다.

`monitoring_checks`에는 `server_id`가 없습니다. Check 쪽 table과 `server_targets` 사이에 FK가 없습니다.

`environment`는 Check와 Server가 각각 가집니다.
두 도메인이 독립적이라 값이 겹쳐 보여도 서로 다른 의미입니다.

Server의 host에는 IP와 hostname을 모두 넣을 수 있습니다.
등록할 때 실제 연결은 확인하지 않습니다.

### Check Type별 입력

| Type | Target에 넣는 값 | port | Blackbox module |
| --- | --- | --- | --- |
| ICMP | IP 또는 hostname | 사용 안 함 | `icmp` |
| TCP | IP 또는 hostname | 필수 (1~65535) | `tcp_connect` |
| HTTP | `http://` 로 시작하는 전체 URL | 사용 안 함 | `http_2xx` |
| HTTPS | `https://` 로 시작하는 전체 URL | 사용 안 함 | `http_2xx` |

Type은 Check가 가집니다. 같은 Check의 Target은 모두 같은 Type입니다.

port는 Target마다 가집니다.
같은 Check 안에서도 node마다 port가 다를 수 있기 때문입니다.

Type과 값을 함께 봐야 하는 검증은 `MonitoringCheckService`에서 처리합니다.
Bean Validation으로는 한 field만 볼 수 있기 때문입니다.

같은 Check 안에 같은 주소를 두 번 넣을 수 없습니다.
같은 대상을 두 번 확인하게 되기 때문입니다.

### Target을 수정할 때

Check를 저장할 때 `targets`는 전체 목록으로 보냅니다.

* `id`가 있으면 기존 Target을 수정합니다.
* `id`가 없으면 새로 추가합니다.
* 목록에 없는 기존 Target은 삭제합니다.

`id`를 그대로 보내야 Prometheus에서 쌓아 온 time series가 끊기지 않습니다.
다른 Check의 Target id를 보내면 400으로 거부합니다.

### 지울 때

* Check를 지우면 딸린 Target이 함께 사라집니다. (`ON DELETE CASCADE`)
* **Server를 지워도 Check와 Target은 그대로 남습니다.**

Check는 여러 서버의 주소를 확인할 수 있어서 서버 한 대에 딸린 자원이 아닙니다.
서버를 정리했다고 모니터링이 조용히 멈추면 운영에서 사고로 이어집니다.

---

## Prometheus HTTP Service Discovery

### 무엇인가

Prometheus가 Target 목록을 설정 파일이 아니라 HTTP API에서 가져오는 방식입니다.

### 왜 필요한가

사용자가 서버를 추가할 때마다 `prometheus.yml`을 고치고 Prometheus를 다시 시작하면,
운영이 불편하고 설정 파일과 DB 내용이 어긋날 수 있습니다.

### 어떻게 동작하는가

Monitoring Manager가 Target 목록을 API로 제공합니다.

```text
GET /api/prometheus/sd/blackbox
GET /api/prometheus/sd/snmp
GET /api/prometheus/sd/mariadb
```

SNMP는 SNMP를 켠 Server 중 고른 Resource가 하나 이상인 것만 내보냅니다.
community는 응답에 없습니다. exporter가 찾을 `snmp_auth` 이름만 넘깁니다.

```json
[
  {
    "targets": ["10.0.0.11:161"],
    "labels": {
      "server_id": "15",
      "server_name": "WAS-01",
      "environment": "Production",
      "snmp_auth": "manager_server_15",
      "snmp_modules": "system,hrDevice,hrStorage"
    }
  }
]
```

`snmp_auth`와 `snmp_modules`는 exporter 요청 parameter로만 쓰고
relabel 단계에서 지웁니다. metric label로 남기지 않습니다.

응답 형식은 Prometheus `http_sd_configs`가 요구하는 모양을 씁니다.

**Target 하나가 entry 하나입니다.**

```json
[
  {
    "targets": ["http://10.0.0.11:8080/actuator/health"],
    "labels": {
      "check_id": "12",
      "check_target_id": "35",
      "check_type": "HTTP",
      "module": "http_2xx",
      "environment": "Production",
      "check_name": "전자결재 Health",
      "service": "전자결재 Health"
    }
  },
  {
    "targets": ["http://10.0.0.12:8080/actuator/health"],
    "labels": { "check_id": "12", "check_target_id": "36", "...": "..." }
  }
]
```

#### 왜 한 entry에 주소를 여러 개 넣지 않는가

`targets` 배열에 주소를 여러 개 넣을 수도 있습니다.
하지만 그러면 같은 entry의 주소들이 label을 전부 공유합니다.
주소마다 다른 `check_target_id`를 붙일 수 없습니다.

상태를 주소 단위로 보여 주려면 안정적인 식별자가 필요합니다.
그래서 Target마다 entry를 하나씩 만듭니다.
같은 Check에 속한다는 것은 `check_id`로 알 수 있습니다.

#### 응답에서 빠지는 것

* 꺼 둔 Target
* 꺼 둔 Check의 모든 Target
* 삭제한 Check와 Target

Target을 추가하거나 삭제하면 Prometheus의 다음 조회부터 반영됩니다.

### Prometheus 쪽 설정

설정은 [infrastructure/prometheus/prometheus.yml](../../infrastructure/prometheus/prometheus.yml)의
`manager-blackbox`, `manager-snmp` job에 있습니다.

두 job 모두 SD가 준 주소를 exporter의 `target` parameter로 넘기고,
실제 scrape는 exporter로 보냅니다. `instance` label에는 exporter가 아니라 실제 대상이 남습니다.

### Label 규칙

이름이 바뀌어도 흔들리지 않도록 ID를 중심으로 씁니다.

| Label | 뜻 |
| --- | --- |
| `check_id` | Check 식별자. 같은 Check의 Target을 묶을 때 씁니다 |
| `check_target_id` | 실제 주소 하나의 식별자. 상태 조회의 기준입니다 |
| `check_type` | ICMP / TCP / HTTP / HTTPS |
| `module` | Blackbox module 이름 |
| `environment` | Check의 운영 분류 |
| `check_name` | Check 이름 (사람이 읽는 값) |

이름 label은 값이 바뀌면 Prometheus에서 새 time series가 시작됩니다.
그래도 알림에 ID만 찍히는 것보다 낫다고 판단해서 넣었습니다.

위 label은 Alertmanager를 거쳐 Manager Webhook까지 그대로 전달됩니다.
Manager는 `check_id`로 어떤 Check의 Alert인지 찾습니다.
`check_id` label을 지우거나 이름을 바꾸면 알림 대상을 찾지 못합니다.

#### Label로 알림 대상을 정하지 않는다

알림 대상은 Manager Database가 정합니다. Label은 Alert가 무엇인지 식별하는 용도입니다.

Label에 Telegram chat id 같은 라우팅 정보를 넣지 않습니다. (CLAUDE.md 4번)

다음은 Label에 넣지 않습니다.

```text
description
긴 오류 메시지
HTTP Response Body
인증 정보
Telegram chat id 같은 알림 대상 정보
```

#### Label 구성을 바꾸면 time series가 끊긴다

Label을 추가하거나 지우면 series 식별자가 바뀝니다.
기존 series는 더 이상 갱신되지 않고, 같은 대상에 새 series가 시작됩니다.

이전 데이터는 Prometheus retention(15일) 동안 남았다가 사라집니다.
Grafana에서 그 기간을 조회하면 그래프가 한 번 끊겨 보입니다.

### 주의할 점

이 API는 Prometheus가 주기적으로 부르며 매번 Manager Database를 조회합니다.
응답이 느려지지 않게 주의해야 합니다.

---

## Blackbox module 제약

### 무엇인가

Blackbox Exporter가 probe 요청에서 받는 값의 한계입니다.

### 확인한 내용

Blackbox Exporter v0.28.0의 `/probe`는 다음 query parameter만 받습니다.

```text
target
module
debug
hostname
```

실제로 확인해 봤습니다.

* `valid_status_codes=404`를 함께 보내도 무시합니다. 404 응답은 여전히 실패로 처리됩니다.
* `timeout=1`을 함께 보내도 module에 적힌 timeout이 그대로 쓰입니다.
* 없는 module 이름을 보내면 400을 돌려줍니다.

즉 **Check마다 다른 응답 코드 조건이나 timeout을 줄 수 없습니다.**

### 그래서 어떻게 했는가

`blackbox.yml`을 사용자 설정에 맞춰 만들어 내는 방법은 쓰지 않습니다.
사용자 설정 때문에 배포용 설정 파일을 바꾸지 않는다는 원칙과 어긋납니다.
([CLAUDE.md](../../CLAUDE.md)의 "외부 시스템의 제약을 코드로 숨기지 않는다")

대신 MVP 범위를 줄였습니다.

| 항목 | 결정 |
| --- | --- |
| HTTP / HTTPS 성공 조건 | 2xx 응답만 성공으로 봅니다 |
| Timeout | module에 적힌 5초를 모든 Check가 함께 씁니다 |

동작하지 않는 값을 받으면 사용자가 오해합니다.
그래서 Expected Status와 Timeout은 API와 화면에서 아예 뺐습니다.

### 나중에 필요해지면

조건이 다른 module을 `blackbox.yml`에 미리 몇 개 만들어 두고,
Check가 그중 하나를 고르는 방식으로 넓힐 수 있습니다.

예를 들어 `http_2xx`, `http_2xx_or_3xx`, `http_any` 같은 식입니다.
사용자가 임의의 숫자를 넣는 방식은 지금 구조에서 지원할 수 없습니다.

---

## Target과 Check 상태

### 무엇인가

지금 대상이 정상인지 판단한 결과입니다.

상태는 아래에서 위로 한 번 합쳐집니다.

```text
Target → Check
```

Server 상태는 여기에 포함되지 않습니다. Blackbox Check로 계산하지 않고 SNMP 수집 결과로 판단합니다.
아래 "SNMP Resource 모니터링"의 "상태를 계산하는 기준"을 참고해 주세요.

### 어떻게 계산하는가

Backend가 Prometheus에 PromQL을 한 번 실행합니다.

```text
probe_success{job="manager-blackbox"}
```

결과를 `check_target_id` 기준으로 묶어 두고 찾아 씁니다.
대상이 몇 개든 Prometheus query는 한 번만 실행합니다.

#### Target 상태

probe는 주소 단위로 실행되므로 상태의 출발점도 Target입니다.

| 상황 | 상태 |
| --- | --- |
| Target 또는 Check를 꺼 둠 | `DISABLED` |
| `probe_success = 1` | `OK` |
| `probe_success = 0` | `CRITICAL` |
| 아직 수집된 Metric 없음 | `UNKNOWN` |
| Prometheus 연결 실패 | `UNKNOWN` |

#### Check 상태

활성 Target 상태를 모아서 계산합니다.

| 상황 | 상태 |
| --- | --- |
| Check를 꺼 둠 | `DISABLED` |
| 활성 Target 없음 | `UNKNOWN` |
| 하나라도 `CRITICAL` | `CRITICAL` |
| 모두 `OK` | `OK` |
| `OK`와 `UNKNOWN`이 섞임 | `UNKNOWN` |

합치는 규칙은 `MonitoringStatus.aggregate` 한 곳에만 두었습니다.

가중치 같은 복잡한 규칙은 만들지 않습니다.
서버 3대 중 1대만 죽었을 때 서비스를 계속 쓸 수 있는지 같은 판단은 하지 않습니다.

### Prometheus가 내려가면

설정 조회는 그대로 동작합니다. 상태만 `UNKNOWN`이 됩니다.

`PrometheusQueryService`는 예외를 밖으로 던지지 않습니다.
Prometheus가 잠시 내려갔다고 서버 목록까지 못 보면 운영이 막히기 때문입니다.

연결 실패 로그는 5분에 한 번만 남깁니다.
요청마다 남기면 로그를 읽을 수 없게 됩니다.

### 주의할 점

PromQL은 `kr.co.unidocs.monitoring.prometheus` package 안에서만 만듭니다.
Frontend는 `GET /api/checks/{id}/status` 같은 업무 단위 API만 호출합니다.

Frontend에서 Prometheus를 직접 호출하지 않습니다.

---

## Alertmanager에서 Telegram까지

### 무엇인가

Alert를 Telegram으로 보내는 경로입니다.

### 왜 이렇게 하는가

Alertmanager 설정에 Telegram receiver를 그룹마다 만들면,
사용자가 그룹을 추가할 때마다 `alertmanager.yml`을 고쳐야 합니다.
그러면 CLAUDE.md 규칙 3번, 4번과 충돌합니다.

### 어떻게 동작하는가

```text
Prometheus Alert Rule
  ↓ Alert 발생
Alertmanager                          receiver는 manager-webhook 하나뿐입니다
  ↓ POST /api/integrations/alertmanager/webhook   (Bearer token 검증)
AlertEvent 저장                        fingerprint + startsAt 기준
  ↓ check_id로 Check 조회
Notification Binding 조회              Check에 연결된 Telegram 그룹
  ↓ severity와 sendResolved 확인
Telegram Destination
  ↓ Telegram Bot API
Telegram Group
```

Alertmanager는 Webhook receiver 하나만 가집니다.
누구에게 보낼지는 Manager Database가 정합니다.

### 무엇을 어디에 저장하는가

| Table | 담는 것 |
| --- | --- |
| `telegram_destinations` | 알림을 받을 Telegram 그룹 (chat id, topic) |
| `monitoring_check_notification_bindings` | Check와 그룹의 연결. severity와 복구 알림 설정 |
| `alert_events` | 받은 Alert. 장애 한 번이 한 줄 |
| `notification_deliveries` | Telegram 전송 결과 |

Bot Token은 어느 table에도 저장하지 않습니다.
Token은 Docker Secret이나 환경변수로만 받습니다. (CLAUDE.md 11번)

### 같은 장애를 한 줄로 유지하는 방법

Alertmanager는 같은 Alert를 반복해서 보냅니다.
`repeat_interval`마다 한 번, webhook 응답이 늦으면 곧바로 한 번 더 보냅니다.

받을 때마다 새 행을 만들면 이력이 금방 쓸모없어집니다.
그래서 `fingerprint`와 `startsAt`을 함께 씁니다.

* `fingerprint`는 label 조합이라 같은 대상이면 계속 같습니다.
* `startsAt`은 장애가 한 번 해소된 뒤 다시 발생하면 새 값이 됩니다.

두 값을 합치면 "장애 한 번"이 한 행이 됩니다.

### 같은 알림을 두 번 보내지 않는 방법

`fingerprint + status`를 영구 unique로 두면 안 됩니다.
장애가 이어지는 동안 오는 정상적인 반복 알림까지 막히기 때문입니다.

대신 짧은 시간 창을 씁니다.

```text
같은 alert_event + 같은 그룹 + 같은 status로
dedupe window(기본 2분) 안에 이미 성공한 전송이 있으면 보내지 않는다
```

* 값은 `alertmanager.yml`의 `group_interval`(5분)보다 작아야 합니다.
  그래야 Alertmanager의 정상적인 반복 알림을 막지 않습니다.
* 실패한 전송은 세지 않습니다. 다시 보낼 수 있어야 합니다.
* 보내지 않은 건도 `notification_deliveries`에 `SKIPPED`로 남깁니다.
  "왜 안 왔는지" 확인할 수 있어야 합니다.

### 주의할 점

* Telegram 전송이 실패해도 Webhook 응답은 성공으로 돌려줍니다.
  그렇지 않으면 Alertmanager가 같은 payload를 계속 재시도합니다.
* 연결이 없는 Check의 Alert는 아무 그룹에도 보내지 않습니다.
  기본 그룹을 몰래 정해 두면 운영자가 모르는 곳으로 장애 알림이 갑니다.
* 모르는 severity는 보내지 않고 기록만 합니다. 임의로 critical로 바꾸지 않습니다.
* 지워진 Check의 Alert가 뒤늦게 올 수 있습니다.
  이때도 요청 전체를 실패시키지 않고 기록만 남긴 뒤 전송을 건너뜁니다.

### Telegram 그룹 등록

두 가지 방법이 있습니다.

| 방법 | 필요한 것 |
| --- | --- |
| 그룹에서 `/register` 전송 | Telegram이 접근할 수 있는 외부 HTTPS 주소 (`TELEGRAM_WEBHOOK_PUBLIC_URL`) |
| 화면에서 Chat ID 직접 입력 | 없음 |

`/register`는 Telegram 서버가 Manager를 직접 부르는 방식입니다.
`http://manager-api:8080` 같은 내부 주소로는 받을 수 없습니다.

외부 주소가 없는 환경에서는 화면에서 Chat ID를 직접 등록합니다.
Bot Token은 어느 방법에서도 사용자가 입력하지 않습니다.

---

## Agent 없이 모니터링하기

### 무엇인가

대상 서버에 Node Exporter 같은 Agent를 설치하지 않고 수집하는 방식입니다.

### 왜 이렇게 하는가

고객사 서버나 다른 팀 서버에 Agent를 설치하려면 승인 절차가 필요합니다.
설치 자체가 도입 장벽이 됩니다.

### 어떻게 동작하는가

| 수집 대상 | 방법 |
| --- | --- |
| 가용성 (ICMP / HTTP / HTTPS / TCP) | Blackbox Exporter |
| CPU / Memory / Storage | SNMP Exporter |
| Database | 중앙 DB Exporter 또는 원격 연결 |

세 가지 모두 모니터링 서버 쪽에서 대상에 접근합니다.
대상 서버에는 아무것도 설치하지 않습니다.

### 주의할 점

* SNMP는 대상 서버에서 snmpd를 켜고 방화벽을 열어야 합니다.
* SNMP로 얻는 정보는 Node Exporter보다 적습니다. 이 점은 감수합니다.

---

## SNMP Resource 모니터링

### 네 갈래의 역할

같은 SNMP를 쓰지만 하는 일이 다릅니다. 섞으면 구조가 무너집니다.

| 역할 | 누가 | 무엇을 |
| --- | --- | --- |
| Resource Discovery | Manager Backend | 장비에 직접 SNMP로 물어 무엇이 있는지 확인 |
| 지속적인 Metric 수집 | Prometheus → snmp_exporter | 주기적으로 CPU / Memory / Storage 수집 |
| Configuration | Manager Database → runtime auth config | 무엇을 어떤 Credential로 수집할지 결정 |
| Target Discovery | Prometheus → Manager HTTP SD | 어떤 장비를 수집할지 결정 |

`snmp_exporter`를 Discovery API처럼 쓰지 않습니다.
Exporter는 Prometheus가 가져갈 metric만 내줍니다.

### Credential을 넘기는 방법

`snmp_exporter`는 scrape 요청에서 `target`, `module`, `auth`만 받습니다.
community는 받지 않습니다. exporter가 자기 config의 `auths`에서 이름으로 찾습니다.

그래서 Manager가 auth config를 만들어 줍니다.

```text
Manager Database (community 암호문)
        ↓  복호화
runtime auth config          manager_server_<id>: { community, version }
        ↓  POST /-/reload
snmp_exporter
        ↑  auth=manager_server_<id>
Prometheus  ←  HTTP SD (auth 이름만, community 없음)
```

* module 정의는 exporter image에 들어 있는 기본 `snmp.yml`을 그대로 씁니다.
* auth만 담긴 파일을 `--config.file`로 하나 더 주면 exporter가 둘을 합쳐서 읽습니다.
* auth config는 `snmp-runtime` volume에만 있습니다.
  Git에 없고 host에 bind하지 않으며 Prometheus도 보지 않습니다.
* 파일은 임시 이름으로 쓴 뒤 옮깁니다. exporter가 쓰다 만 파일을 읽으면 안 됩니다.

### 반영 실패를 숨기지 않는다

DB 저장과 exporter 반영은 별개입니다.
저장은 됐는데 반영이 실패하면 수집이 시작되지 않습니다.

그래서 `GET /api/servers/{id}/snmp` 응답에 `exporterSynced`를 함께 돌려주고,
화면에서도 "snmp_exporter에 반영되지 않았습니다"라고 알려 줍니다.

### CPU / Memory / Storage를 가르는 기준

| Resource | 근거 |
| --- | --- |
| CPU | `hrProcessorLoad` 행 개수. 사용률은 Prometheus에서 processor 평균 |
| Memory | `hrStorageType`이 `hrStorageRam`(`1.3.6.1.2.1.25.2.1.2`)인 행 |
| Storage | `hrStorageType`이 `hrStorageFixedDisk` 또는 `hrStorageNetworkDisk`인 행 |

`hrStorageTable`에는 memory, swap, buffer, cache, disk가 함께 들어옵니다.
첫 행을 memory로 보면 안 됩니다. 반드시 `hrStorageType`으로 가릅니다.

크기는 byte가 아니라 `hrStorageAllocationUnits` 단위입니다.
`hrStorageSize`와 `hrStorageUsed`에 이 값을 곱해야 byte가 됩니다.

장비가 `hrProcessorTable`을 주지 않으면 CPU를 발견하지 못한 것으로 둡니다.
값을 만들어 내지 않습니다.

### Discovery와 선택은 다르다

```text
Discover    지금 장비에서 보이는 것. 저장하지 않습니다.
Configured  사용자가 모니터링하기로 고른 것. monitored_resources에 저장합니다.
```

다시 검색해도 기존 선택은 그대로 둡니다.
장비에서 사라진 Resource는 지우지 않고 값이 없는 `UNAVAILABLE`로 보여 줍니다.

Storage의 식별자는 SNMP index가 아니라 장비가 준 `hrStorageDescr`입니다.
index는 재부팅이나 Agent 설정 변경으로 바뀔 수 있습니다.

### 상태를 계산하는 기준

Threshold 기능이 아직 없습니다. 사용률로 위험을 판단하지 않습니다.

| 상황 | Server 상태 |
| --- | --- |
| 서버를 꺼 둠 | `DISABLED` |
| SNMP 미설정 | `UNKNOWN` |
| SNMP는 켰는데 값이 없음 | `UNKNOWN` |
| 값을 읽고 있음 | `OK` |

사용률이 아무리 높아도 `CRITICAL`이 되지 않습니다.
Threshold 단계에서 상태 모델을 넓힙니다.

---

## Grafana와의 경계

### 무엇인가

Monitoring Manager와 Grafana의 역할을 나누는 기준입니다.

### 왜 필요한가

둘 다 metric을 보여 줄 수 있습니다.
경계를 정하지 않으면 Manager에서 Grafana를 다시 만들게 됩니다.

### 어떻게 나누는가

| Monitoring Manager | Grafana |
| --- | --- |
| 설정 (서버, DB, Threshold, 알림) | 상세 시계열 그래프 |
| 현재 장애 상태 | 장기간 추세 분석 |
| 알림 이력 | 여러 metric을 조합한 분석 |
| 간단한 요약 | 자유로운 Dashboard 구성 |

Manager 화면에서는 대상별로 Grafana Dashboard 링크를 제공합니다.
Manager가 Dashboard를 자동으로 만들지는 않습니다.

---

## Manager Database 이식성

### 무엇인가

Manager Database 제품을 바꿔도 코드를 고치지 않아도 되는 구조입니다.

### 왜 필요한가

Manager Database를 어디에 둘지 아직 정하지 않았습니다.

```text
Docker Compose 내부 MariaDB
AWS RDS MariaDB
AWS RDS PostgreSQL
사내 DB
```

나중에 정해도 되도록 지금 구조를 잡아 둡니다.

### 어떻게 동작하는가

1. 연결 정보는 환경변수로 받습니다.
   `application.yml`에 DB 주소를 적지 않습니다.

2. MariaDB와 PostgreSQL JDBC Driver를 둘 다 포함합니다.

3. Flyway Migration은 제품별로 나눕니다.

   ```text
   db/migration/mariadb/V1__baseline.sql
   db/migration/postgresql/V1__baseline.sql
   ```

   `spring.flyway.locations`에 `{vendor}` 자리표시자를 써서
   JDBC URL에 맞는 디렉터리를 자동으로 고릅니다.

4. Hibernate는 `ddl-auto=validate`만 씁니다.
   Entity와 Schema가 어긋나면 기동할 때 바로 실패합니다.

### 어떻게 확인하는가

테스트에서 MariaDB와 PostgreSQL container를 각각 띄워서
같은 검증을 실행합니다.

```text
MariaDbManagerDatabaseTest
PostgresManagerDatabaseTest
```

한쪽에서만 동작하는 SQL을 쓰면 테스트가 실패합니다.

### 주의할 점

* 제품별 Migration을 하나만 추가하면 다른 쪽 테스트가 깨집니다.
  항상 두 곳에 같이 추가합니다.
* 특정 제품에만 있는 SQL 기능은 업무 로직에 쓰지 않습니다.
* `ddl-auto`를 `update`로 바꾸지 않습니다.

---

## Backend Package 구조

기능 중심으로 나눕니다.

```text
kr.co.unidocs.monitoring
├ config          설정 바인딩, bean 등록
├ common
│  ├ exception    오류 처리
│  └ security     인증 정보 암호화
├ system          애플리케이션 자체 정보 API
├ server          모니터링 대상 서버
├ snmp            SNMP 설정, 직접 SNMP 조회, exporter auth config
├ resource        CPU / Memory / Storage 발견·선택·현재 값
├ check           가용성 체크 (Blackbox)
├ database        Database Target
│  └ provider     DB 엔진별 처리
├ threshold       Threshold
├ prometheus      HTTP Service Discovery, PromQL 조회
├ alert           Alertmanager Webhook 수신과 Alert 이력
├ notification    전송과 결과 기록
│  ├ routing      Check ↔ Telegram 그룹 연결
│  └ telegram     Telegram 그룹 관리와 Bot API 호출
└ grafana         Grafana 링크 생성
```

각 package에는 `package-info.java`로 역할과 제약을 적어 두었습니다.
`database`, `threshold`, `grafana`는 아직 `package-info.java`만 있습니다.
새 기능을 만들기 전에 해당 package의 설명을 먼저 읽어 주세요.

---

## Frontend 구조

```text
frontend/src
├ components/ui       button, input, table, section, dialog, tabs 같은 공통 component
├ components/<도메인>  checks, servers, notifications 화면 전용 Dialog와 section
├ components          Layout, PageHeader, StatusDot, Toolbar 같은 화면 공통 요소
├ pages               Router에 연결되는 화면
├ lib/api.ts          Backend 호출, 타입, query key
├ index.css           Design Token
└ App.tsx             Router와 TanStack Query 설정
```

Query key는 `lib/api.ts`의 `queryKeys`에 모아 둡니다.
Mutation이 끝나면 관련된 query를 invalidate합니다.

화면 규칙은 [CLAUDE.md](../../CLAUDE.md)의 "UI/UX"와 [design.md](../design/design.md)에 있습니다.
색과 간격 token은 `src/index.css`에 모아 두고, 화면에서 hex 값을 직접 쓰지 않습니다.

상태는 색만으로 구분하지 않습니다.
색을 구분하기 어려운 사용자도 알 수 있게 작은 점 옆에 한국어 문구를 함께 보여 줍니다.

Backend 주소를 Frontend에 넣지 않습니다.
개발 서버에서는 Vite proxy가, Docker에서는 Nginx가 `/api` 요청을 Backend로 넘깁니다.
그래서 CORS 설정이 필요 없습니다.
