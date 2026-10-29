# 통합 계약

모니터링 코어와 그 코어에 붙는 앱 사이의 약속을 설명한다. 다른 앱의 개발자가 이 문서만 보고 코어에 붙을 수 있게 하는 것이 목적이다. 이 저장소의 백엔드, 화면, DB도 이 계약으로 코어에 붙는 앱 하나다. 이 저장소의 앱을 도입하면서 테이블과 열 이름을 바꾸는 절차도 함께 있다. 이렇게 나눈 결정과 이유는 [ADR 0007](decisions/0007-core-app-contract.md)에 있다.

## 코어와 앱

코어는 Prometheus, Blackbox Exporter, Alertmanager다. `infrastructure/compose.yml`로 앱과 따로 띄운다. 코어만으로는 감시와 알림이 동작하지 않는다. 무엇을 감시할지는 앱이 HTTP SD로 알려 주고, Alert를 누구에게 보낼지는 앱이 webhook을 받아 정한다.

| 방향 | 무엇 | 누가 부르는가 |
|---|---|---|
| 코어 → 앱 | 가용성 Check 대상 목록(HTTP SD) | Prometheus, 15초마다 목록 갱신 |
| 코어 → 앱 | OS exporter 수집 대상 목록(HTTP SD) | Prometheus, 30초마다 목록 갱신 |
| 코어 → 앱 | Alert(webhook) | Alertmanager |
| 앱 → 코어 | 수집 결과 조회(Prometheus HTTP API) | 앱 |
| 코어 → 감시 대상 | 가용성 확인과 OS exporter 수집 | Blackbox Exporter, Prometheus |

코어와 앱은 같은 호스트의 Docker 네트워크 `monitoring`으로 통신한다. 코어가 이 네트워크를 만들고, 앱은 `external: true`로 붙는다. 그래서 코어를 먼저 띄운다. 호스트에 여는 포트는 `127.0.0.1`에만 묶는다. 다른 호스트에 두는 구성은 지원하지 않는다([#15](https://github.com/subinggrae/server-monitor/issues/15)). 코어가 부르는 앱 주소는 하나뿐이라, 코어 하나에는 앱 하나만 붙는다.

코어와 앱이 맡는 일, 설치 순서, 앱이 만들 것의 쉬운 안내는 [운영 가이드](OPERATIONS.md)에 있다. 이 문서는 그 안내가 기대는 약속(경로, 형식, label, series)의 기준이다.

### 용어

이 문서에 쓰는 Prometheus 용어는 다음과 같다.

- **HTTP SD**: Prometheus가 감시 대상 목록을 받아 가는 방식이다. 앱이 목록을 JSON으로 돌려주는 GET API를 만들면, Prometheus가 주기적으로 불러 그 목록을 감시한다.
- **label**: 대상과 결과에 붙는 이름표(`key=value`)다. 앱은 label로 어느 Check, 어느 서버의 결과인지 찾는다.
- **series**: 같은 label을 가진 값을 시간 순서로 쌓은 기록이다. label 값이 바뀌면 다른 series가 되어 이전 기록과 이어지지 않는다.
- **exporter**: 감시 대상의 값을 Prometheus가 읽을 수 있게 내보내는 프로그램이다. 서버에는 OS exporter(node_exporter, windows_exporter)를 설치한다.
- **PromQL**: Prometheus 조회 언어다. 앱은 이 문서의 예에 있는 식(series 이름, label 조건, `max by (server_id)` 같은 묶음)만 쓰면 된다.
- **job**: 코어 설정이 수집 방식마다 붙인 이름이다. `manager-blackbox`는 주소 확인, `manager-os`는 OS exporter 수집이다.
- **instance**: 확인하거나 수집한 대상이다. 주소 확인은 확인한 주소, OS exporter 수집은 `host:port`다.
- **probe, module**: Blackbox Exporter가 주소 하나를 한 번 확인하는 일을 probe라고 한다. module은 그 확인 방식(ping, TCP 연결, HTTP 요청)의 이름이다.
- **recording rule**: 코어가 원본 값을 미리 계산해 새 이름으로 저장하는 규칙이다. `manager:`로 시작하는 series가 그 결과다.
- **Alert**: 코어의 규칙이 "문제가 있다"고 판단한 상태다. Alertmanager가 앱의 webhook으로 보낸다. `fingerprint`는 Alertmanager가 label로 만든 Alert의 식별자다.
- **Resource**: 서버에서 감시하는 CPU, Memory, 디스크 하나하나다. 사용자가 서버마다 고른다.

## 코어 설정

코어 설정은 세 가지다. 저장소에서는 `infrastructure/.env`와 `infrastructure/secrets/`에, 내부망 묶음에서는 묶음 폴더의 `.env`와 token 폴더에 둔다. 바꾼 뒤 다시 띄우는 방법은 [운영 가이드의 운영 작업](OPERATIONS.md#운영-작업)에 있다.

- `MONITORING_APP_ADDRESS`: 코어가 부를 앱 주소다. `monitoring` 네트워크 안에서 닿는 `host:port`이고, 예는 `manager-api:8080`이다. `http://`를 붙이지 않는다.
- `CORE_SECRETS_DIR`: webhook token 파일이 있는 폴더다. 비우면 저장소에서는 `infrastructure/secrets/`를, 내부망 묶음에서는 설치 폴더(묶음 폴더의 상위 폴더)의 `secrets/`를 쓴다.
- `alertmanager_webhook_token`: webhook에 붙는 Bearer token 파일이다. 앱도 같은 파일을 Docker secret으로 받아 비교한다. 이 저장소의 앱은 루트 `compose.yml`의 `secrets`에서 이 파일을 설정 키 `monitoring-manager.notification.alertmanager-webhook-token`으로 받는다. 설정 파일, 이 문서, 로그에 값을 쓰지 않는다.

Prometheus와 Alertmanager의 설정 파일은 앱 주소를 `@MONITORING_APP_ADDRESS@`로 둔 템플릿이고, 코어 이미지 안에 들어 있다. 컨테이너가 시작할 때 이미지 안의 `render-config.sh`가 주소를 넣는다. 코어를 설치하고 서버마다 바꾸는 값은 [운영 가이드](OPERATIONS.md#언제-무엇을-바꾸나)에 있다.

### 경로와 주소 바꾸기

앱 주소(호스트와 포트)는 코어 설정의 `MONITORING_APP_ADDRESS`로 바꾼다. 반면 API 경로는 바꾸지 않는다. 코어는 앱 주소 뒤에 정해진 경로를 붙여 부르고, 경로는 코어 설정 파일에 적혀 있기 때문이다.

| 바꾸려는 것 | 방법 | 고치는 곳 |
|---|---|---|
| 앱 호스트, 포트 | 코어 설정의 앱 주소를 바꾸고 코어를 다시 띄움 | 코어의 `.env` |
| webhook token | token 파일을 바꾸고 코어의 Alertmanager와 앱을 다시 만듦(내부망 묶음은 `sh core-server.sh rotate-token`) | token 폴더의 `alertmanager_webhook_token` |
| API 경로 | 바꾸지 않고, 앱 앞단(리버스 프록시 등)에서 받아 넘김 | 앱 쪽 |
| `http`를 `https`로 | 지원하지 않음(같은 호스트의 Docker 네트워크 안에서만 부름) | 없음 |

API 경로를 꼭 바꿔야 하면 `infrastructure/prometheus/prometheus.yml`의 `http_sd_configs.url` 두 곳과 `infrastructure/alertmanager/alertmanager.yml`의 `webhook_configs.url`을 고친 뒤 코어 이미지를 다시 빌드하고 모든 코어 서버를 갱신한다. 이는 코어 변경이라 이 저장소에서는 큰 변경으로 다루고, 이 문서도 함께 고친다.

## 앱이 제공할 API

코어가 부르는 앱의 API는 다음 세 개뿐이다. 이 세 API의 경로와 형식은 코어와의 약속이라 바꾸지 않는다. 화면용 API, 설정 API, 리소스 임계값 판단은 코어가 부르지 않으므로 앱이 자유롭게 만든다.

| API | 부르는 쪽 | 언제 | 인증 | 응답 |
|---|---|---|---|---|
| `GET /api/prometheus/sd/blackbox` | Prometheus | 15초마다(목록 갱신) | 없음 | 확인할 주소 목록 |
| `GET /api/prometheus/sd/os-exporter` | Prometheus | 30초마다(목록 갱신) | 없음 | 수집할 서버 목록 |
| `POST /api/integrations/alertmanager/webhook` | Alertmanager | Alert가 생기거나 바뀔 때, 이어지면 5분마다 | `Authorization: Bearer <token>` | `2xx` |

목록 갱신 주기는 Prometheus가 목록을 다시 받아 가는 간격이다. 실제로 확인하고 수집하는 주기는 대상마다 `__scrape_interval__` label로 따로 정한다. 목록 API 두 개는 인증 없이 불린다. 그래서 앱 포트를 `monitoring` 네트워크와 `127.0.0.1`에만 열어 밖에서 부르지 못하게 한다. 목록 응답에는 감시 대상 주소와 이름이 들어가므로 비밀값을 넣지 않는다.

### 가용성 Check 대상: `GET /api/prometheus/sd/blackbox`

[Prometheus HTTP SD](https://prometheus.io/docs/prometheus/latest/http_sd/) 형식의 JSON 배열을 `200`과 `Content-Type: application/json`으로 돌려준다. 확인할 주소 하나가 entry 하나다. 목록에는 켜져 있는 감시 항목의 켜져 있는 주소만 넣고, 대상이 없으면 빈 배열 `[]`을 돌려준다. 목록에서 빠진 주소는 다음 갱신부터 확인하지 않는다.

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

확인 방식마다 `targets`에 넣는 값, `module` 값, 성공으로 보는 조건은 다음과 같다. 확인마다 5초 안에 응답이 없으면 실패다.

| `check_type` | `targets` 값 | `module` | 성공 조건 |
|---|---|---|---|
| `ICMP` | host 또는 IP | `icmp` | ping 응답 |
| `TCP` | `host:port` | `tcp_connect` | TCP 연결 성공 |
| `HTTP`, `HTTPS` | 전체 URL | `http_2xx` | `GET` 응답이 2xx(리다이렉트는 따라감) |

세 방식 모두 IPv4로만 확인한다. IPv6 주소만 있는 대상은 실패한다.

### OS exporter 수집 대상: `GET /api/prometheus/sd/os-exporter`

형식은 위와 같다. `targets`는 OS exporter의 `host:port`다. node_exporter의 기본 포트는 9100, windows_exporter는 9182다. 목록에는 감시가 켜져 있고 수집도 켠 서버만 넣는다.

| label | 뜻 | 값 |
|---|---|---|
| `server_id` | 서버 id(사용률 series를 서버별로 찾는 열쇠) | 숫자 문자열 |
| `server_name` | 서버 이름 | 자유 문자열 |
| `environment` | 환경 이름 | 자유 문자열 |
| `os` | 운영체제 | `LINUX`, `WINDOWS` |
| `__scrape_interval__` | 수집 주기 | `10s`~`240s` |

SD 응답은 다음을 지킨다.

- label 값은 모두 JSON 문자열로 보낸다. id를 숫자로 보내면 Prometheus가 응답 전체를 거부한다.
- label 이름을 더하거나 지우거나 바꾸지 않는다. id 값도 대상마다 바뀌지 않게 둔다. label과 id가 time series의 이름표라서 바뀌면 쌓아 온 series가 끊긴다.
- 이름과 환경 값(`check_name`, `server_name`, `environment`)은 사용자가 바꾸면 그대로 바꿔 보낸다. 다만 그 대상의 series가 새로 시작되고, 진행 중인 Alert가 복구됐다가 다시 생길 수 있다([#22](https://github.com/subinggrae/server-monitor/issues/22)에서 확인한다).
- `__scrape_interval__`은 10초보다 짧게 두지 않는다. 코어의 수집 제한 시간(`scrape_timeout`)이 10초라서, 주기가 그보다 짧으면 Prometheus가 그 대상을 수집하지 않는다.
- 목록을 만들지 못하면(DB 오류 등) 빈 배열 `[]` 대신 `5xx`를 돌려준다. `[]`을 받으면 Prometheus가 모든 대상의 감시를 멈추지만, 오류를 받으면 마지막 목록을 유지한다.
- label에 알림 대상(chat id 등)이나 긴 설명을 넣지 않는다. label 값은 Prometheus에 그대로 저장된다.
- SD를 부르지 못하면 Prometheus는 마지막으로 받은 목록으로 수집을 이어 간다. 앱이 멈춰도 감시는 멈추지 않는다.

### Alert: `POST /api/integrations/alertmanager/webhook`

Alertmanager가 [webhook payload](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config)를 보낸다. 한 요청에 같은 감시 항목의 Alert 여러 개(주소마다 하나)가 `alerts` 배열로 들어온다. 요청 본문의 예는 다음과 같다. 앱이 쓰는 필드만 남겼다.

```json
{
  "version": "4",
  "status": "firing",
  "alerts": [
    {
      "status": "firing",
      "labels": {
        "alertname": "MonitoringCheckDown",
        "severity": "critical",
        "check_id": "12",
        "check_target_id": "31",
        "check_name": "전자결재 Health",
        "check_type": "HTTPS",
        "environment": "Production",
        "instance": "https://example.com/health"
      },
      "annotations": {
        "summary": "전자결재 Health 확인에 실패했습니다",
        "description": "https://example.com/health 확인이 1분 넘게 실패하고 있습니다. (HTTPS / Production)"
      },
      "startsAt": "2026-10-21T01:02:03.000Z",
      "endsAt": "0001-01-01T00:00:00Z",
      "fingerprint": "a1b2c3d4e5f60718"
    }
  ]
}
```

앱은 `alerts`의 항목마다 다음 순서로 처리한다.

1. `labels.check_id`로 앱의 감시 항목을 찾는다. 지운 감시 항목이면 기록만 남기고 알리지 않는다.
2. `fingerprint`와 `startsAt`으로 장애 한 번을 구분한다. 같은 값이 다시 오면 같은 장애가 이어진다. 복구된 뒤 다시 실패하면 `startsAt`이 새로 온다.
3. `status`가 `firing`이면 장애, `resolved`면 복구다. 복구 시각은 `endsAt`이다. 장애 중에는 `endsAt`이 `0001-01-01`이라 복구 시각으로 쓰지 않는다.
4. 같은 장애가 다시 오면, 마지막으로 알림을 보낸 뒤 그 감시 항목의 재발송 주기가 지났을 때만 다시 보낸다. 이 저장소의 앱은 재발송 주기를 5~1440분으로 받고, 정하지 않은 감시 항목은 60분으로 본다. Alertmanager는 이어지는 Alert를 5분마다 보내므로, 재발송 주기를 5분보다 짧게 두지 않는다([ADR 0001](decisions/0001-alert-repeat-in-manager.md)).

`alertname`이 `MonitoringCheckProbeFailed`인 Alert는 대상이 아니라 코어 쪽 문제다. Blackbox Exporter가 멈췄거나 응답하지 않아 확인 결과를 받지 못한 상태다. label은 `MonitoringCheckDown`과 같아서 어느 감시 항목의 확인이 멈췄는지 알 수 있고, annotation 문구는 다르다. 이 저장소의 앱은 두 Alert를 같은 방식으로 기록하고 알린다.

앱은 다음도 지킨다.

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

Alertmanager는 `alertname`, `check_id`, `environment`로 Alert를 묶어 한 요청으로 보낸다. 처음 생긴 Alert는 30초 기다렸다 보내고, 이어지는 Alert는 5분마다 다시 보낸다. `alertmanager.yml`의 `repeat_interval`이 4분이지만 묶음을 다시 살피는 간격(`group_interval`)이 5분이라, 결과는 5분마다다. 복구 Alert(`status: resolved`)도 보낸다. 대상 확인이 실패하기 시작해 webhook이 오기까지는 1분(규칙의 지속 시간)에 규칙 평가 간격(15초)과 30초를 더한 정도가 걸린다.

## 코어가 제공할 것

코어가 앱에 주는 것은 수집 결과 조회 API 하나다. 앱은 `monitoring` 네트워크 안에서 `http://prometheus:9090`으로 부르고, 인증은 없다. 조회는 `GET /api/v1/query`에 PromQL을 `query` 파라미터로 넘긴다. 다음은 서버 7의 사용률을 읽는 요청이다.

```http
GET http://prometheus:9090/api/v1/query?query=manager%3Aresource_usage_percent%7Bserver_id%3D%227%22%7D
```

URL 인코딩 전의 `query` 값은 `manager:resource_usage_percent{server_id="7"}`이다. 응답은 다음과 같다. `result`의 항목 하나가 series 하나다. `value`는 `[조회 시각(Unix 초), "값"]`이고 값은 문자열이다. 첫 값은 Prometheus가 조회를 계산한 시각이고, 값을 수집한 시각이 아니다.

```json
{
  "status": "success",
  "data": {
    "resultType": "vector",
    "result": [
      {
        "metric": { "server_id": "7", "resource_type": "STORAGE", "resource_key": "/" },
        "value": [1792544523.123, "87.5"]
      }
    ]
  }
}
```

응답은 다음처럼 다룬다.

- `status`가 `success`가 아니거나 연결이 실패하면 "알 수 없음"으로 본다. 예외로 화면이나 판단을 멈추지 않는다. 이 저장소의 앱은 `unavailable` 상태를 돌려준다.
- 조건에 맞는 series가 없으면 `result`가 빈 배열이다. 대상을 아직 수집하지 않았거나 수집에 실패했다는 뜻이다.
- 값이 `"NaN"`일 수 있다. 숫자로 바꿔 쓰기 전에 확인한다.
- 수집이 멈춘 뒤에도 마지막 값이 몇 분 동안 나올 수 있다. CPU 사용률은 최근 10분 안의 수집 두 번으로 계산하므로 최대 10분이다. 값이 지금 것인지는 같은 서버의 수집 상태(`up{job="manager-os"}`)가 1인지로 판단한다.
- 주소나 Resource마다 따로 부르지 않는다. 전체나 서버 하나 단위로 한 번에 읽고 label로 나눈다.

앱이 쓰는 조회의 예는 다음과 같다.

| 알고 싶은 것 | `query` 값 | 결과를 나누는 label |
|---|---|---|
| 주소마다 마지막 확인 결과 | `probe_success{job="manager-blackbox"}` | `check_target_id` |
| 서버마다 수집 상태 | `max by (server_id) (up{job="manager-os"})` | `server_id` |
| 한 서버의 Resource 사용률 | `manager:resource_usage_percent{server_id="7"}` | `resource_type`, `resource_key` |

읽을 수 있는 series는 다음과 같다. 이름과 label은 바꾸지 않는다.

| series | 뜻 |
|---|---|
| `probe_success{job="manager-blackbox"}` | 가용성 확인 결과(1 성공, 0 실패), label은 SD label과 `instance` |
| `up{job="manager-os"}` | OS exporter 수집 상태(1 수집 중, 0 실패), label은 SD label과 `instance` |
| `manager:resource_usage_percent` | 사용률(0~100) |
| `manager:resource_used_bytes` | 사용량(byte), CPU는 없음 |
| `manager:resource_size_bytes` | 전체 크기(byte), CPU는 없음 |

`manager:resource_*`의 label은 `server_id`, `resource_type`(`CPU`, `MEMORY`, `STORAGE`), `resource_key`뿐이다. `resource_key`는 CPU가 `cpu`, Memory가 `memory`, Storage가 Linux mountpoint나 Windows 드라이브 이름(`C:`)이다. OS별 계산은 코어의 `infrastructure/prometheus/rules/server-resources.yml`에만 있다([ADR 0005](decisions/0005-common-resource-recording-rules.md)).

코어는 앱의 metric(`/actuator/prometheus` 등)을 수집하지 않는다.

## 리소스 임계값 판단

코어에는 CPU, Memory, Storage의 임계값 Alert 규칙이 없다. 임계값은 사용자가 화면에서 바꾸는 설정이라서, 앱이 `manager:resource_usage_percent`를 읽어 임계값과 비교하고 직접 알린다. 그래서 리소스 알림은 Alertmanager webhook으로 오지 않는다. 이 결정과 이유는 [ADR 0008](decisions/0008-resource-threshold-in-app.md)에, 동작의 기준은 [요구사항 0009](requirements/0009-resource-threshold-schema.md)에 있다. 이 저장소의 앱은 리소스 임계값을 판단하지 않는다.

### 앱이 만들 것

리소스 임계값 판단에는 코어와 주고받는 API가 새로 없다. 코어는 사용률만 주고, 나머지는 모두 앱 안에서 만든다. 경로와 형식은 앱이 정한다.

| 만들 것 | 하는 일 | 쓰는 것 |
|---|---|---|
| 설정 저장과 화면 | Resource마다 임계값과 지속 시간, 서버마다 재발송 주기를 받는다 | `resource_thresholds`, `server_resource_alert_settings` |
| 판단 작업 | 앱 안에서 주기적으로 실행해 사용률을 읽고 [판단 규칙](#판단-규칙)대로 상태를 바꾼다 | [코어가 제공할 것](#코어가-제공할-것)의 조회 API, `resource_alert_states`, `resource_alert_events` |
| 알림 발송 | 판단 작업이 발생, 재발송, 복구를 정하면 사람에게 보낸다 | 앱이 고른 알림 방법(문자, Telegram 등) |

판단 작업은 앱이 하나만 띄워져 있다고 보고 만든다. 앱을 여러 개 띄우면 같은 Resource를 두 번 판단해 알림이 두 번 갈 수 있다([ADR 0008](decisions/0008-resource-threshold-in-app.md)).

### 서버와 Resource 등록

판단하려면 먼저 서버와 그 서버에서 감시할 Resource가 앱의 DB에 있어야 한다. 이 저장소의 기준 migration(`V1__baseline.sql`)에서 다음 세 테이블을 옮겨 쓴다.

| 테이블 | 담는 것 | 쓰는 곳 |
|---|---|---|
| `server_targets` | 서버 이름, 주소, 환경, 감시 켜짐(`enabled`) | OS exporter 목록 API, 판단 대상 |
| `server_exporter_configs` | 서버마다 수집 켜짐, exporter 포트, 수집 주기 | OS exporter 목록 API, 판단 주기 |
| `monitored_resources` | 서버마다 사용자가 고른 Resource(`resource_type`, `resource_key`, 표시 이름) | 임계값을 거는 대상 |

사용자가 고를 수 있는 Resource 후보는 서버의 수집을 켠 뒤 `manager:resource_usage_percent{server_id="7"}`를 조회하면 나온다. 결과의 `resource_type`과 `resource_key`를 `monitored_resources`에 그대로 저장한다. 판단할 때도 이 두 값으로 조회 결과와 Resource를 맞춘다.

### 참고용 DDL

판단에 필요한 설정과 상태를 담는 테이블의 참고용 DDL은 [MariaDB용](reference-schema/mariadb/resource-thresholds.sql)과 [PostgreSQL용](reference-schema/postgresql/resource-thresholds.sql)이 있다. 이 저장소의 migration에는 들어 있지 않고, 기준 migration(`V1__baseline.sql`) 다음에 적용하면 만들어진다. 두 파일은 `server_targets`와 `monitored_resources`를 외래 키로 가리키므로 그 이름을 바꿨으면 함께 고친다. 테이블마다 담는 것은 다음과 같다.

| 테이블 | 담는 것 | Resource나 서버를 지우면 |
|---|---|---|
| `resource_thresholds` | Resource마다 Warning·Critical 임계값과 지속 시간 | 함께 지워짐 |
| `server_resource_alert_settings` | 서버마다 리소스 알림 재발송 주기 | 서버를 지우면 함께 지워짐 |
| `resource_alert_states` | 단계마다 넘기 시작한 시각, 발생 기록, 마지막 발송 시각 | 함께 지워짐 |
| `resource_alert_events` | 발생 한 번과 그때의 서버·Resource 이름, 임계값, 사용률, 복구 시각 | 남음 |

### 판단 규칙

앱은 감시 Resource의 단계(Warning, Critical)마다 다음 규칙으로 판단한다. 판단 상태는 `resource_alert_states`에 두고, 단계가 넘기 시작했거나 발생 중일 때만 행이 있다. 시각은 모두 UTC로 저장한다.

- **대상**: 감시와 수집이 모두 켜진 서버(`server_targets.enabled`, `server_exporter_configs.enabled`)의 Resource 가운데 `resource_thresholds`에 행이 있고 임계값이 NULL이 아닌 단계만 판단한다.
- **주기**: 서버의 수집 주기(`server_exporter_configs.scrape_interval_seconds`)마다 그 서버의 `manager:resource_usage_percent{server_id="…"}`를 읽고, `resource_type`과 `resource_key`로 Resource를 찾는다.
- **대기**: 사용률이 임계값보다 크면 상태 행을 만들고 넘기 시작한 시각(`exceeded_since`)을 남긴다.
- **발생**: 넘은 상태가 지속 시간 이상 이어지면 발생 기록을 만들고 알림을 보낸 뒤 마지막 발송 시각을 남긴다. 발생 기록에는 그때의 서버 이름과 Resource 이름도 남긴다.
- **Warning 억제**: Critical이 발생 중인 동안 같은 Resource의 Warning은 대기 상태로만 둔다. 지속 시간이 지나도 발생 기록과 알림을 만들지 않는다. Critical이 복구된 판단에서 Warning을 여전히 넘고 있고 지속 시간이 지났으면, 그때 Warning의 발생 기록을 만들고 알린다. Warning이 이미 발생 중일 때 Critical이 발생하면, Warning은 발생 중으로 두고 Critical이 발생 중인 동안 Warning의 재발송만 멈춘다. Warning 복구는 사용률이 Warning 임계값 이하가 될 때 알린다.
- **정상으로 돌아옴**: 사용률이 임계값 이하가 되면, 대기 중인 단계는 상태 행을 지운다. 발생 중인 단계는 기록에 복구 시각을 쓰고 복구 알림을 보낸 뒤 상태 행을 지운다.
- **값이 없음**: series가 없거나, 값이 숫자가 아니거나(`NaN` 등), 그 서버의 수집 상태(`up{job="manager-os",server_id="…"}`)가 1이 아니거나, Prometheus 조회가 실패하면, 대기 중인 단계는 상태 행을 지운다. 발생 중인 단계는 유지하고 복구 알림을 보내지 않으며, 재발송은 계속한다. 값이 없는데 복구를 알리면 사람이 잘못 안심하기 때문이다.
- **재발송**: 발생 중인 단계는 마지막 발송 뒤 서버의 재발송 주기(행이 없으면 60분)가 지나면 다시 보내고 마지막 발송 시각을 고친다.
- **설정을 바꿈**: 임계값을 바꾸면 다음 판단부터 새 값으로 비교한다. 단계의 임계값을 비우거나 `resource_thresholds` 행을 지우면, 판단을 기다리지 않고 저장할 때 그 단계의 상태 행을 정리한다. 발생 중이면 기록에 복구 시각을 쓰고 복구 알림을 보낸 뒤 행을 지우고, 대기 중이면 행만 지운다. 상태 행은 `monitored_resources`에 걸려 있어 임계값 행을 지워도 저절로 지워지지 않는다.
- **다시 띄움**: 앱이 뜨면 대기 중인 상태 행(`resource_alert_event_id`가 NULL)을 모두 지운다. 멈춘 동안 사용률이 계속 넘었는지 알 수 없어서 값이 없을 때와 같이 다룬다. 발생 중인 단계는 이어 가고, 새로 알리지 않으며, 마지막 발송 시각부터 재발송 주기를 잰다.
- **감시를 끔**: 서버의 감시를 끄거나 수집만 끄면, 끌 때 그 서버의 발생 중인 단계를 복구 알림으로 끝내고 대기 중인 상태 행을 지운다. 끄지 않으면 사용률이 들어오지 않아 해제되지 않은 알림이 남는다. 임계값과 재발송 주기는 남긴다.
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
