# 가용성 Check

Blackbox Exporter로 ICMP·TCP·HTTP·HTTPS 가용성을 확인하는 Monitoring Check의 구조, Prometheus SD 응답, 상태 계산, 수집 확인 절차를 설명한다. 전체 구조와 경계는 [ARCHITECTURE.md](../ARCHITECTURE.md)에 있다.

## 도메인 구조

Blackbox 모니터링과 서버 Resource 모니터링은 목적이 달라 서로 연결하지 않는다.

```text
Blackbox Monitoring
  MonitoringCheck              사용자가 관리하는 논리적인 확인 단위
  └─ MonitoringCheckTarget     Blackbox가 실제로 확인하는 주소 (N개)

Infrastructure Monitoring
  ServerTarget                 서버 자산
  ├─ ServerSnmpConfig          SNMP 접속 정보 (0..1)
  └─ MonitoredResource         모니터링하기로 고른 CPU / Memory / Storage (N개)

Database Monitoring (아직 구현하지 않았다)
  DatabaseTarget               MariaDB, MySQL, PostgreSQL, AWS RDS MariaDB
```

### Check와 Server를 분리한 이유

같은 애플리케이션이 서버 여러 대에서 동작하는 경우가 많다. 운영자는 아래 주소 세 개를 "전자결재 Health" 하나로 관리한다.

```text
전자결재 Health (HTTP)
  ├─ http://10.0.0.11:8080/actuator/health
  ├─ http://10.0.0.12:8080/actuator/health
  └─ http://10.0.0.13:8080/actuator/health
```

이 Check를 10.0.0.11 서버의 하위 항목으로 두면 나머지 두 주소는 부모 서버와 관계가 없어 소속이 맞지 않는다. 그래서 Check는 Server에 속하지 않는다.

- Server 없이 Check를 만들 수 있다.
- Server를 지워도 Check는 그대로 남는다.
- Server 상태를 Blackbox Check로 계산하지 않는다.
- ICMP Check의 대상이 ServerTarget으로 등록되어 있을 필요도 없다.

결정의 배경과 대안은 [ADR 0003](../decisions/0003-separate-check-from-server.md)에 있다.

### Check와 Target을 나눈 이유

사용자가 보는 단위(Check)와 Prometheus가 확인하는 단위(Target)가 다르다. Prometheus는 주소마다 따로 확인해야 어느 주소가 죽었는지 알 수 있다.

## 데이터 구조

schema는 `backend/src/main/resources/db/migration/`의 Flyway migration이 기준이다.

- `monitoring_checks`에는 `server_id`가 없다. Check 쪽 table과 `server_targets` 사이에 FK가 없다.
- `environment`는 Check와 Server가 각각 가진다. 두 도메인이 독립적이라 값이 겹쳐 보여도 서로 다른 의미다.
- Server의 host에는 IP와 hostname을 모두 넣을 수 있다. 등록할 때 실제 연결은 확인하지 않는다.

### Check Type별 입력

| Type | Target에 넣는 값 | port | Blackbox module |
|---|---|---|---|
| ICMP | IP 또는 hostname | 쓰지 않는다 | `icmp` |
| TCP | IP 또는 hostname | 필수 (1~65535) | `tcp_connect` |
| HTTP | `http://`로 시작하는 전체 URL | 쓰지 않는다 | `http_2xx` |
| HTTPS | `https://`로 시작하는 전체 URL | 쓰지 않는다 | `http_2xx` |

- Type은 Check가 가진다. 같은 Check의 Target은 모두 같은 Type이다.
- port는 Target마다 가진다. 같은 Check 안에서도 node마다 port가 다를 수 있다.
- Type과 값을 함께 봐야 하는 검증은 `MonitoringCheckService`가 한다. Bean Validation은 한 field만 볼 수 있다.
- 같은 Check 안에 같은 주소를 두 번 넣을 수 없다. 같은 대상을 두 번 확인하게 된다.

### Target 수정

Check를 저장할 때 `targets`는 전체 목록으로 보낸다.

- `id`가 있으면 기존 Target을 고친다.
- `id`가 없으면 새로 추가한다.
- 목록에 없는 기존 Target은 지운다.

`id`를 그대로 보내야 Prometheus에 쌓인 time series가 끊기지 않는다. 다른 Check의 Target id를 보내면 400으로 거부한다.

### 삭제

- Check를 지우면 딸린 Target이 함께 사라진다(`ON DELETE CASCADE`).
- Server를 지워도 Check와 Target은 그대로 남는다. Check는 여러 서버의 주소를 확인할 수 있어 서버 한 대에 딸린 자원이 아니다. 서버를 정리했다고 모니터링이 조용히 멈추면 운영 사고로 이어진다.

## Prometheus SD 응답

`GET /api/prometheus/sd/blackbox`는 Prometheus `http_sd_configs` 형식으로 Target 목록을 준다. 결정 배경은 [ADR 0001](../decisions/0001-prometheus-http-service-discovery.md)에 있다.

**Target 하나가 entry 하나다.**

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
      "check_name": "전자결재 Health"
    }
  },
  {
    "targets": ["http://10.0.0.12:8080/actuator/health"],
    "labels": { "check_id": "12", "check_target_id": "36", "...": "..." }
  }
]
```

`targets` 배열에 주소를 여러 개 넣으면 같은 entry의 주소들이 label을 모두 공유해 주소마다 다른 `check_target_id`를 붙일 수 없다. 주소 단위 상태를 보여 주려면 안정적인 식별자가 필요해 Target마다 entry를 하나씩 만든다. 같은 Check에 속한다는 것은 `check_id`로 안다. ([ADR 0002](../decisions/0002-sd-entry-per-check-target.md))

응답에서 빠지는 것은 다음과 같다. Target을 추가하거나 지우면 Prometheus의 다음 조회부터 반영된다.

- 꺼 둔 Target
- 꺼 둔 Check의 모든 Target
- 삭제한 Check와 Target

### Prometheus 설정

`infrastructure/prometheus/prometheus.yml`의 `manager-blackbox`, `manager-snmp` job이 SD를 조회한다. 두 job 모두 SD가 준 주소를 exporter의 `target` parameter로 넘기고 실제 scrape는 exporter로 보낸다. `instance` label에는 exporter가 아니라 실제 대상이 남는다.

### Label 규칙

이름이 바뀌어도 흔들리지 않도록 ID를 중심으로 쓴다.

| Label | 뜻 |
|---|---|
| `check_id` | 같은 Check의 Target을 묶을 때 쓰는 Check 식별자 |
| `check_target_id` | 상태 조회의 기준이 되는 실제 주소 하나의 식별자 |
| `check_type` | ICMP, TCP, HTTP, HTTPS |
| `module` | Blackbox module 이름 |
| `environment` | Check의 운영 분류 |
| `check_name` | Check 이름(사람이 읽는 값) |

- 이름 label은 값이 바뀌면 Prometheus에서 새 time series가 시작된다. 그래도 알림에 ID만 찍히는 것보다 낫다고 판단해 넣었다.
- label은 Alertmanager를 거쳐 Manager webhook까지 그대로 전달된다. Manager는 `check_id`로 어떤 Check의 Alert인지 찾는다. `check_id`를 지우거나 이름을 바꾸면 알림 대상을 찾지 못한다.
- label은 Alert가 무엇인지 식별하는 용도다. 알림 대상은 Manager Database가 정한다.
- label을 추가하거나 지우면 series 식별자가 바뀐다. 기존 series는 더 이상 갱신되지 않고 같은 대상에 새 series가 시작된다. 이전 데이터는 Prometheus 보존 기간(15일) 동안 남았다가 사라지고, Grafana에서 그 기간을 조회하면 그래프가 한 번 끊겨 보인다.

label에 넣지 않는 것은 다음과 같다.

```text
description
긴 오류 메시지
HTTP Response Body
인증 정보
Telegram chat id 같은 알림 대상 정보
```

## Blackbox module 제약

Blackbox Exporter v0.28.0의 `/probe`는 `target`, `module`, `debug`, `hostname` query parameter만 받는다. 직접 확인한 결과는 다음과 같다.

- `valid_status_codes=404`를 함께 보내도 무시한다. 404 응답은 여전히 실패다.
- `timeout=1`을 함께 보내도 module에 적힌 timeout을 쓴다.
- 없는 module 이름을 보내면 400을 돌려준다.

즉 Check마다 다른 응답 코드 조건이나 timeout을 줄 수 없다. 사용자 설정에 맞춰 `blackbox.yml`을 만들어 내면 배포용 설정 파일을 사용자 설정 때문에 바꾸게 되므로 쓰지 않는다. 대신 범위를 줄였다.

| 항목 | 결정 |
|---|---|
| HTTP·HTTPS 성공 조건 | 2xx 응답만 성공으로 본다 |
| Timeout | module에 적힌 5초를 모든 Check가 함께 쓴다 |

동작하지 않는 값을 받으면 사용자가 오해하므로 Expected Status와 Timeout은 API와 화면에서 뺐다.

나중에 필요해지면 조건이 다른 module(`http_2xx`, `http_2xx_or_3xx`, `http_any` 등)을 `blackbox.yml`에 미리 두고 Check가 그중 하나를 고르게 넓힐 수 있다. 사용자가 임의의 숫자를 넣는 방식은 이 구조에서 지원할 수 없다. ([ADR 0004](../decisions/0004-fixed-blackbox-modules.md))

## Target과 Check 상태

상태는 Target에서 Check로 한 번 합쳐진다. Server 상태는 여기에 포함되지 않는다. Server 상태는 SNMP 수집 결과로 판단한다([snmp-resources.md의 상태 계산](snmp-resources.md#상태-계산)).

Backend는 Prometheus에 `probe_success{job="manager-blackbox"}`를 한 번 실행하고, 결과를 `check_target_id` 기준으로 묶어 찾아 쓴다. 대상이 몇 개든 query는 한 번이다.

### Target 상태

probe는 주소 단위로 실행되므로 상태의 출발점은 Target이다.

| 상황 | 상태 |
|---|---|
| Target 또는 Check를 꺼 둠 | `DISABLED` |
| `probe_success = 1` | `OK` |
| `probe_success = 0` | `CRITICAL` |
| 아직 수집된 metric 없음 | `UNKNOWN` |
| Prometheus 연결 실패 | `UNKNOWN` |

### Check 상태

활성 Target 상태를 모아 계산한다. 합치는 규칙은 `MonitoringStatus.aggregate` 한 곳에만 둔다.

| 상황 | 상태 |
|---|---|
| Check를 꺼 둠 | `DISABLED` |
| 활성 Target 없음 | `UNKNOWN` |
| 하나라도 `CRITICAL` | `CRITICAL` |
| 모두 `OK` | `OK` |
| `OK`와 `UNKNOWN`이 섞임 | `UNKNOWN` |

가중치 같은 복잡한 규칙은 만들지 않는다. 서버 3대 중 1대만 죽었을 때 서비스를 계속 쓸 수 있는지 같은 판단은 하지 않는다.

### Prometheus가 내려갔을 때

설정 조회는 그대로 동작하고 상태만 `UNKNOWN`이 된다. `PrometheusQueryService`는 예외를 밖으로 던지지 않는다. Prometheus가 잠시 내려갔다고 서버 목록까지 못 보면 운영이 막힌다. 연결 실패 로그는 5분에 한 번만 남긴다. 요청마다 남기면 로그를 읽을 수 없다.

## Check 수집 반영 확인

등록한 Check가 Prometheus에 발견되고 probe 결과가 화면 상태에 반영됐는지 확인하는 운영 절차다.

- 도구: curl, Docker
- 권한: 배포 서버 shell 접근. port는 `127.0.0.1`에만 열린다
- 담당자와 에스컬레이션 경로: 아직 정하지 않았다

1. SD 응답에 Check Target이 있는지 확인한다.

   ```sh
   curl -s http://localhost:8080/api/prometheus/sd/blackbox
   ```

2. 30초 정도 기다린 뒤 Prometheus가 Target을 발견했는지 확인한다. SD 조회 주기(15초)와 scrape 주기(15초)가 지나야 한다.

   ```sh
   curl -s 'http://localhost:9090/api/v1/targets?state=active'
   ```

3. probe 결과를 확인한다.

   ```sh
   curl -s --get http://localhost:9090/api/v1/query \
     --data-urlencode 'query=probe_success{job="manager-blackbox"}'
   ```

4. Backend가 계산한 Check 상태를 확인한다. `1`은 Check id다.

   ```sh
   curl -s http://localhost:8080/api/checks/1/status
   ```

| 증상 | 확인할 것 |
|---|---|
| SD 응답에 Target이 없음 | Check와 Target이 모두 켜져 있는지 확인한다(꺼 둔 것은 응답에서 빠짐) |
| Prometheus가 Target을 발견하지 못함 | Prometheus container에서 `manager-api:8080`에 접근되는지 확인한다 |
| `probe_success`가 0 | 아래 명령으로 Blackbox module만 따로 호출해 대상 문제인지 확인한다 |
| 상태가 계속 "알 수 없음" | Prometheus가 떠 있는지 확인한다(연결하지 못하면 설정은 보이고 상태만 `UNKNOWN`이 됨) |

```sh
docker exec prometheus wget -qO- \
  'http://blackbox-exporter:9115/probe?module=icmp&target=manager-api' | grep probe_success
```

해결할 수 없을 때 연락할 곳은 아직 정하지 않았다. 담당 팀을 정해야 한다.
