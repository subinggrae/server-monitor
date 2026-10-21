# 통합 계약

모니터링 코어와 그 코어에 붙는 앱 사이의 약속을 설명한다. 다른 앱의 개발자가 이 문서만 보고 코어에 붙을 수 있게 하는 것이 목적이다. 이 저장소의 백엔드, 화면, DB도 이 계약으로 코어에 붙는 앱 하나다. 이 저장소의 앱을 도입하면서 테이블과 열 이름을 바꾸는 절차도 함께 있다. 이렇게 나눈 결정과 이유는 [ADR 0007](decisions/0007-core-app-contract.md)에 있다.

## 코어와 앱

코어는 Prometheus, Blackbox Exporter, Alertmanager다. `infrastructure/compose.yml`로 앱과 따로 띄운다. 코어만으로는 감시와 알림이 동작하지 않는다. 무엇을 감시할지는 앱이 HTTP SD로 알려 주고, Alert를 누구에게 보낼지는 앱이 webhook을 받아 정한다.

| 방향 | 무엇 | 누가 부르는가 |
|---|---|---|
| 코어 → 앱 | 가용성 Check 대상 목록(HTTP SD) | Prometheus, 15초마다 |
| 코어 → 앱 | OS exporter 수집 대상 목록(HTTP SD) | Prometheus, 30초마다 |
| 코어 → 앱 | Alert(webhook) | Alertmanager |
| 앱 → 코어 | 수집 결과 조회(Prometheus HTTP API) | 앱 |
| 코어 → 감시 대상 | 가용성 확인과 OS exporter 수집 | Blackbox Exporter, Prometheus |

코어와 앱은 같은 호스트의 Docker 네트워크 `monitoring`으로 통신한다. 코어가 이 네트워크를 만들고, 앱은 `external: true`로 붙는다. 그래서 코어를 먼저 띄운다. 호스트에 여는 포트는 `127.0.0.1`에만 묶는다. 다른 호스트에 두는 구성은 아직 지원하지 않는다([#15](https://github.com/subinggrae/server-monitor/issues/15)).

## 코어 설정

코어 설정은 두 가지다. 바꾸면 `docker compose -f infrastructure/compose.yml up -d`로 코어를 다시 띄운다.

- `infrastructure/.env`의 `MONITORING_APP_ADDRESS`: 코어가 부를 앱 주소다. `monitoring` 네트워크 안에서 닿는 `host:port`이고, 예는 `manager-api:8080`이다. `http://`를 붙이지 않는다.
- `infrastructure/secrets/alertmanager_webhook_token`: webhook에 붙는 Bearer token이다. 앱도 같은 값을 받는다. 설정 파일, 이 문서, 로그에 값을 쓰지 않는다.

Prometheus와 Alertmanager의 설정 파일은 앱 주소를 `@MONITORING_APP_ADDRESS@`로 둔 템플릿이다. 컨테이너가 시작할 때 `infrastructure/render-config.sh`가 주소를 넣는다.

## 앱이 제공할 것

### 가용성 Check 대상: `GET /api/prometheus/sd/blackbox`

[Prometheus HTTP SD](https://prometheus.io/docs/prometheus/latest/http_sd/) 형식의 JSON 배열을 `200`과 `Content-Type: application/json`으로 돌려준다. 확인할 주소 하나가 entry 하나다.

```json
[
  {
    "targets": ["https://example.com/health"],
    "labels": {
      "check_id": "12",
      "check_target_id": "31",
      "check_type": "HTTPS",
      "module": "http_2xx",
      "environment": "Production",
      "check_name": "전자결재 Health",
      "__scrape_interval__": "30s"
    }
  }
]
```

| label | 뜻 | 값 |
|---|---|---|
| `check_id` | 감시 항목의 id(webhook에서 Alert의 감시 항목을 찾는 열쇠) | 숫자 문자열 |
| `check_target_id` | 감시 항목 안의 주소 하나의 id | 숫자 문자열 |
| `check_type` | 확인 방식 | `ICMP`, `TCP`, `HTTP`, `HTTPS` |
| `module` | Blackbox module | `icmp`, `tcp_connect`, `http_2xx`(`infrastructure/blackbox/blackbox.yml`에 있는 이름) |
| `environment` | 환경 이름 | 자유 문자열 |
| `check_name` | 감시 항목 이름(Alert 요약에 들어감) | 자유 문자열 |
| `__scrape_interval__` | 수집 주기(time series label로 남지 않음) | `10s`~`240s` |

`targets`의 값은 ICMP면 host, TCP면 `host:port`, HTTP와 HTTPS면 전체 URL이다.

### OS exporter 수집 대상: `GET /api/prometheus/sd/os-exporter`

형식은 위와 같다. `targets`는 OS exporter의 `host:port`다.

| label | 뜻 | 값 |
|---|---|---|
| `server_id` | 서버 id(사용률 series를 서버별로 찾는 열쇠) | 숫자 문자열 |
| `server_name` | 서버 이름 | 자유 문자열 |
| `environment` | 환경 이름 | 자유 문자열 |
| `os` | 운영체제 | `LINUX`, `WINDOWS` |
| `__scrape_interval__` | 수집 주기 | `10s`~`240s` |

SD 응답은 다음을 지킨다.

- label 이름을 더하거나 지우거나 바꾸지 않는다. id 값도 대상마다 바뀌지 않게 둔다. label과 id가 time series의 이름표라서 바뀌면 쌓아 온 series가 끊긴다.
- label에 알림 대상(chat id 등)이나 긴 설명을 넣지 않는다. label 값은 Prometheus에 그대로 저장된다.
- SD를 부르지 못하면 Prometheus는 마지막으로 받은 목록으로 수집을 이어 간다. 앱이 멈춰도 감시는 멈추지 않는다.

### Alert: `POST /api/integrations/alertmanager/webhook`

Alertmanager가 [webhook payload](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config)를 보낸다. 앱은 다음을 지킨다.

- `Authorization: Bearer <token>`이 코어 설정의 token과 다르면 `401`을 돌려주고 Alert를 기록하지 않는다. 비교는 고정 시간 비교로 한다.
- 처리하면 `2xx`를 돌려준다. 알림 전송에 실패해도 `2xx`를 돌려준다. 실패를 돌려주면 Alertmanager가 같은 payload를 계속 다시 보낸다.
- 모르는 필드가 있어도 실패하지 않는다. Alertmanager 버전이 올라가면 필드가 늘어난다.
- 앱이 멈춘 동안의 Alert는 Alertmanager가 다시 보낸다. 앱이 뜬 뒤 다음 전송에서 받는다.

Alert마다 들어오는 label과 annotation은 다음과 같다. 규칙은 `infrastructure/prometheus/rules/monitoring-checks.yml`에 있다.

| 이름 | 종류 | 값 |
|---|---|---|
| `alertname` | label | `MonitoringCheckDown`(확인 실패), `MonitoringCheckProbeFailed`(probe 결과를 가져오지 못함) |
| `severity` | label | `critical` |
| `check_id`, `check_target_id`, `check_name`, `check_type`, `environment` | label | 앱이 SD로 준 값 |
| `instance` | label | 확인하던 주소 |
| `summary`, `description` | annotation | 사람이 읽는 문장 |

Alertmanager는 `alertname`, `check_id`, `environment`로 Alert를 묶고, 이어지는 Alert를 5분(`group_interval`)마다 다시 보낸다. 같은 Alert를 다시 받았을 때 알림을 다시 보낼지는 앱이 정한다. 복구 Alert(`status: resolved`)도 보낸다.

## 코어가 제공할 것

앱은 `http://prometheus:9090/api/v1/query`로 수집 결과를 읽는다. 읽을 수 있는 series는 다음과 같다. 이름과 label은 바꾸지 않는다.

| series | 뜻 |
|---|---|
| `probe_success{job="manager-blackbox"}` | 가용성 확인 결과(1 성공, 0 실패), label은 SD label과 `instance` |
| `up{job="manager-os"}` | OS exporter 수집 상태(1 수집 중, 0 실패), label은 SD label과 `instance` |
| `manager:resource_usage_percent` | 사용률(0~100) |
| `manager:resource_used_bytes` | 사용량(byte) |
| `manager:resource_size_bytes` | 전체 크기(byte) |

`manager:resource_*`의 label은 `server_id`, `resource_type`(`CPU`, `MEMORY`, `STORAGE`), `resource_key`뿐이다. `resource_key`는 CPU가 `cpu`, Memory가 `memory`, Storage가 Linux mountpoint나 Windows 드라이브 이름(`C:`)이다. OS별 계산은 코어의 `infrastructure/prometheus/rules/server-resources.yml`에만 있다([ADR 0005](decisions/0005-common-resource-recording-rules.md)).

코어는 앱의 metric(`/actuator/prometheus` 등)을 수집하지 않는다.

## 리소스 임계값 판단

코어에는 CPU, Memory, Storage의 임계값 Alert 규칙이 없다. 임계값은 사용자가 화면에서 바꾸는 설정이라서, 앱이 `manager:resource_usage_percent`를 읽어 임계값과 비교하고 직접 알린다. 그래서 리소스 알림은 Alertmanager webhook으로 오지 않는다. 이 결정과 이유는 [ADR 0008](decisions/0008-resource-threshold-in-app.md)에, 동작의 기준은 [요구사항 0009](requirements/0009-resource-threshold-schema.md)에 있다. 이 저장소의 앱은 리소스 임계값을 판단하지 않는다.

### 참고용 DDL

판단에 필요한 설정과 상태를 담는 테이블의 참고용 DDL은 [MariaDB용](reference-schema/mariadb/resource-thresholds.sql)과 [PostgreSQL용](reference-schema/postgresql/resource-thresholds.sql)이 있다. 이 저장소의 migration에는 들어 있지 않고, 기준 migration(`V1__baseline.sql`) 다음에 적용하면 만들어진다. 두 파일은 `server_targets`와 `monitored_resources`를 외래 키로 가리키므로 그 이름을 바꿨으면 함께 고친다. 테이블마다 담는 것은 다음과 같다.

| 테이블 | 담는 것 | Resource나 서버를 지우면 |
|---|---|---|
| `resource_thresholds` | Resource마다 Warning·Critical 임계값과 지속 시간 | 함께 지워짐 |
| `server_resource_alert_settings` | 서버마다 리소스 알림 재발송 주기 | 서버를 지우면 함께 지워짐 |
| `resource_alert_states` | 단계마다 넘기 시작한 시각, 발생 기록, 마지막 발송 시각 | 함께 지워짐 |
| `resource_alert_events` | 발생 한 번과 그때의 임계값, 사용률, 복구 시각 | 남음 |

### 판단 규칙

앱은 감시 Resource의 단계(Warning, Critical)마다 다음 규칙으로 판단한다. 판단 상태는 `resource_alert_states`에 두고, 단계가 넘기 시작했거나 발생 중일 때만 행이 있다.

- **대상**: `resource_thresholds`에 행이 있는 Resource의 단계 가운데 임계값이 NULL이 아닌 단계만 판단한다.
- **주기**: 서버의 수집 주기(`server_exporter_configs.scrape_interval_seconds`)마다 그 서버의 `manager:resource_usage_percent{server_id="…"}`를 읽고, `resource_type`과 `resource_key`로 Resource를 찾는다.
- **대기**: 사용률이 임계값보다 크면 상태 행을 만들고 넘기 시작한 시각(`exceeded_since`)을 남긴다.
- **발생**: 넘은 상태가 지속 시간 이상 이어지면 발생 기록을 만들고 알림을 보낸 뒤 마지막 발송 시각을 남긴다. Critical이 발생 중인 동안 같은 Resource의 Warning 알림은 보내지 않는다.
- **정상으로 돌아옴**: 사용률이 임계값 이하가 되면, 대기 중인 단계는 상태 행을 지운다. 발생 중인 단계는 기록에 복구 시각을 쓰고 복구 알림을 보낸 뒤 상태 행을 지운다.
- **값이 없음**: series가 없거나, 값이 숫자가 아니거나(`NaN` 등), Prometheus 조회가 실패하면, 대기 중인 단계는 상태 행을 지운다. 발생 중인 단계는 유지하고 복구 알림을 보내지 않는다. 값이 없는데 복구를 알리면 사람이 잘못 안심하기 때문이다.
- **재발송**: 발생 중인 단계는 마지막 발송 뒤 서버의 재발송 주기(행이 없으면 60분)가 지나면 다시 보내고 마지막 발송 시각을 고친다.
- **설정을 바꿈**: 임계값을 바꾸면 다음 판단부터 새 값으로 비교한다. 단계의 임계값을 비우거나 `resource_thresholds` 행을 지우면, 판단을 기다리지 않고 저장할 때 그 단계의 상태 행을 정리한다. 발생 중이면 기록에 복구 시각을 쓰고 복구 알림을 보낸 뒤 행을 지우고, 대기 중이면 행만 지운다. 상태 행은 `monitored_resources`에 걸려 있어 임계값 행을 지워도 저절로 지워지지 않는다.
- **다시 띄움**: 상태 행에서 이어 간다. 넘기 시작한 시각을 다시 재지 않고, 발생 중인 단계를 새로 알리지 않는다.
- **지움**: Resource나 서버를 지우기 전에, 발생 중인 단계를 복구 알림으로 끝낸다. 발생 기록은 남는다.

설정 값의 허용 범위는 다음과 같다. 앱의 요청 검증에서 확인한다.

| 값 | 범위 | 정하지 않으면 |
|---|---|---|
| 임계값 | 1~100 정수 %, Warning < Critical | 그 단계를 판단하지 않음 |
| 지속 시간 | 1~60분 정수 | 화면의 처음 값 5분 |
| 재발송 주기 | 5~1440분 정수 | 60분 |

## 테이블과 열 이름 바꾸기

이 저장소의 앱을 도입하면서 Manager Database의 테이블과 열 이름을 자기 용어사전에 맞게 바꾸는 절차다. 이름은 다음 세 곳에만 있다. 다른 소스, 코어 설정, 이 계약은 고치지 않는다. [참고용 DDL](#참고용-ddl)을 쓰면 그 외래 키도 함께 고친다.

- JPA Entity의 `@Table`, `@Column`, `@JoinColumn`(`backend/src/main/java/` 아래 Entity 10개)
- `backend/src/main/resources/db/migration/mariadb/V1__baseline.sql`
- `backend/src/main/resources/db/migration/postgresql/V1__baseline.sql`

순서는 다음과 같다. 빈 DB에 처음 설치하기 전에 한다.

1. 바꿀 이름을 Entity 어노테이션에서 고친다. Java 필드 이름은 그대로 둔다. JPQL은 필드 이름을 쓰므로 고치지 않는다.
2. 같은 이름을 두 `V1__baseline.sql`에서 모두 고친다. 그 이름을 쓰는 외래 키, 인덱스의 열 목록도 함께 고친다. 제약과 인덱스 이름(`uk_`, `fk_`, `ix_`, `pk_`로 시작)은 바꾸지 않아도 동작한다.
3. Docker가 있는 PC에서 테스트를 실행한다. `SchemaMigrationIntegrationTest`는 빈 MariaDB와 PostgreSQL에 migration을 적용하고, Entity와 맞지 않으면 실패한다. `DatabaseNameLocationTest`는 테이블 이름이 Entity와 migration 밖에 남았는지 본다.

   ```sh
   cd backend && ./gradlew test integrationTest
   ```

4. 빌드한 앱을 빈 DB에 설치한다.

바꾼 이름은 HTTP SD 응답, webhook 처리, Prometheus series에 영향이 없다. 이 단계들은 네이티브 SQL이 없다는 전제에 기댄다. 네이티브 SQL을 더하면 이름이 다른 곳에도 생기므로 `DatabaseNameLocationTest`가 막는다.

이미 설치한 DB의 이름을 바꾸는 것은 이 절차의 범위가 아니다. 그때는 `ALTER TABLE`을 담은 다음 version의 migration을 두 vendor 디렉터리에 함께 더한다.
