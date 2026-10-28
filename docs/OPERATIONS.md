# 운영 가이드

모니터링 코어와 node_exporter를 설치하고, 코어에 붙일 관리자 서버를 만드는 설명서다. Prometheus를 모르는 개발자와 운영자가 이 문서만 보고 인터넷이 없는 서버에서도 감시부터 알림까지 동작시킬 수 있게 쓴다. 관리자 서버 API의 label 하나하나까지의 형식은 [통합 계약](INTEGRATION.md)에 있다. 통합 계약에서 "앱"이라고 부르는 것이 이 문서의 관리자 서버다.

## 한눈에 보기

이 시스템은 세 부분으로 되어 있다. 이 저장소가 만들어 주는 것은 코어와 node_exporter 묶음이고, 관리자 서버는 코어를 쓰는 쪽이 만든다.

- **코어**: 감시 대상을 주기적으로 확인하고 서버 사용량을 모아 보관하며, 주소 확인 실패가 이어지면 장애로 판단한다. 프로그램 세 개(Prometheus, Alertmanager, Blackbox Exporter)로 되어 있고, 코어 서버에 둔다.
- **관리자 서버**: 무엇을 감시할지 저장해 코어에 알려 주고, 코어가 보낸 장애를 받아 담당자에게 알린다. 화면, API, DB를 갖춘 웹 서비스이고, 코어와 같은 서버에 둔다.
- **OS exporter**: 서버의 CPU, Memory, 디스크 사용량을 코어가 읽을 수 있게 내보내는 작은 프로그램이다. Linux는 node_exporter, Windows는 windows_exporter이고, 감시 대상 서버마다 설치한다. 웹 주소, TCP 포트, ping으로 살아 있는지만 보는 대상에는 아무것도 설치하지 않는다.

감시부터 알림까지는 다음 순서로 흘러간다. 관리자 서버가 만들 것은 1, 5, 6, 7번이다.

1. 관리자 서버가 사용자가 등록한 감시 대상을 목록으로 내준다. 확인할 주소 목록과 사용량을 모을 서버 목록 두 가지다.
2. 코어가 15~30초마다 그 목록을 받아 간다.
3. 코어가 대상마다 정한 주기(10~240초)로 주소가 살아 있는지 확인하고, 서버의 OS exporter에서 사용량을 모은다. 모은 값은 15일 보관한다.
4. 주소 확인 실패가 1분 넘게 이어지면 코어가 장애로 판단해 관리자 서버에 보낸다. 이어지면 5분마다 다시 보내고, 풀리면 복구를 보낸다.
5. 관리자 서버가 장애를 받아 기록하고 담당자에게 알린다(문자, 메신저 등). 같은 장애를 다시 받으면 재알림 주기가 지났을 때만 다시 알린다.
6. 관리자 서버가 코어에 확인 결과와 사용량을 물어 화면에 보인다.
7. 관리자 서버가 주기적으로 사용량을 물어 사용자가 정한 임계값과 비교하고, 넘으면 알린다. 임계값 판단은 코어가 하지 않는다.

코어와 관리자 서버는 같은 서버에서 각자 컨테이너로 띄우고, Docker 네트워크 `monitoring`으로만 통신한다. 서로 다른 서버에 나눠 두는 구성은 지원하지 않는다([#15](https://github.com/subinggrae/server-monitor/issues/15)). 서버 한 대에는 코어 하나와 관리자 서버 하나만 둔다.

## 준비물

| 어디 | 필요한 것 |
|---|---|
| 빌드 PC | Windows, 인터넷, 이 저장소(커밋한 상태), Docker Desktop, Node 18 이상 |
| 코어 서버 | x86-64 Linux, Docker Engine과 Docker Compose v2(29.2와 5.1에서 확인), 서버가 켜질 때 Docker가 자동으로 켜지는 설정(`sudo systemctl enable docker`), 15일치 기록을 둘 디스크, 감시할 주소와 감시 대상 서버의 9100·9182 포트에 닿는 네트워크 |
| 감시 대상 Linux 서버 | x86-64, Docker Compose v2 또는 systemd, 비어 있는 9100 포트 |
| 감시 대상 Windows 서버 | windows_exporter를 설치할 권한, 비어 있는 9182 포트 |

코어는 실행 중에 인터넷에 나가지 않는다. 관리자 서버가 외부 메신저로 알리면 관리자 서버만 그 서비스에 닿으면 된다. Windows의 Docker Desktop은 개발 환경에서만 쓴다.

## 언제 무엇을 바꾸나

바꿀 수 있는 값은 바꾸는 때에 따라 네 가지로 나뉜다. 대부분은 빌드한 뒤 서버에서 바꾼다.

### 서버에서 바꾸는 값

다음 값은 빌드한 묶음을 옮긴 뒤 서버에서 바꾼다. 다시 빌드하지 않는다.

| 바꾸고 싶은 것 | 바꾸는 곳 | 기본값 |
|---|---|---|
| 설치 폴더 위치 | 원하는 곳에 폴더를 만들고 묶음 폴더를 그 안에 둠 | 없음(어디든 됨) |
| 관리자 서버 이름·포트 | 묶음 폴더 `.env`의 `MONITORING_APP_ADDRESS` | 없음(`install`이 물음) |
| token 폴더 위치 | 묶음 폴더 `.env`의 `CORE_SECRETS_DIR` | 설치 폴더의 `secrets/` |
| token 값 | token 폴더의 `alertmanager_webhook_token` 파일 | 없음(`install`이 만듦) |
| 관리자 서버가 코어에 물을 주소 | 관리자 서버의 설정 | `http://prometheus:9090` |
| 감시 대상 서버의 접근 허용 | 그 서버의 방화벽 | 없음(코어 서버 IP에만 연다) |

관리자 서버 이름·포트는 `monitoring` 네트워크 안에서 닿는 `컨테이너 이름:포트`이고 `http://`를 붙이지 않는다. 관리자 서버의 컨테이너 이름이나 포트를 바꾸면 이 값도 함께 바꾼다.

묶음 폴더는 버전마다 새로 생긴다. 그래서 묶음 폴더들을 한 설치 폴더에 나란히 두고, token은 설치 폴더의 `secrets/`에 둔다. 버전이 바뀌어도 token 경로가 그대로라 관리자 서버의 설정을 바꾸지 않는다. 설치 폴더는 `/opt/monitoring` 등 어디든 된다.

```text
/opt/monitoring/                         설치 폴더
├── secrets/alertmanager_webhook_token   코어와 관리자 서버가 함께 읽는 token
├── monitoring-core-<이전 버전>/          되돌릴 때 쓰도록 남겨 둔다
└── monitoring-core-<새 버전>/
```

token을 다른 곳에 두려면 `.env`의 `CORE_SECRETS_DIR`에 경로를 적는다. 상대 경로는 묶음 폴더 기준이다.

### 관리자 서버 화면에서 바꾸는 값

사용자가 화면에서 바꾸는 값은 관리자 서버의 DB에 두고 코어 설정에는 넣지 않는다. 관리자 서버가 목록 API로 내주거나 스스로 판단에 쓰므로 코어를 다시 띄우지 않는다. 장애는 두 종류이고 판단하는 곳이 달라서, 바꿀 수 있는 것도 다르다.

| 장애 종류 | 판단하는 곳 | 관리자 서버에서 바꾸는 것 |
|---|---|---|
| 주소 확인 실패(웹 주소, TCP, ping) | 코어 | 감시할 주소, 확인 방식, 확인 주기(10~240초), 재알림 주기(5~1440분), 알릴지와 알림 받을 곳, 복구 알림 여부 |
| CPU·Memory·디스크 임계값 초과 | 관리자 서버 | 임계값, 지속 시간(1~60분), 재알림 주기(5~1440분), 알림 받을 곳 |

사용량을 모을 서버의 주소, exporter 포트(Linux 9100, Windows 9182), 수집 주기(10~240초)도 관리자 서버에서 정한다. 목록에 넣은 값은 30초 안에 코어에 반영된다.

### 빌드 전에 저장소에서 고치는 값

다음 값은 코어 이미지와 묶음의 `compose.yml`에 들어 있다. 바꾸려면 저장소에서 고치고 다시 빌드해 모든 코어 서버를 갱신한다.

- **주소 확인 장애의 기준**: 실패가 1분 이어지면 장애, 코어가 관리자 서버에 5분마다 다시 보냄. 그래서 관리자 서버의 재알림 주기는 5분보다 짧게 둘 수 없다. 1분을 화면에서 정하는 일은 [#5](https://github.com/subinggrae/server-monitor/issues/5)에 남아 있다.
- **주소 확인 방식**: 제한 시간 5초, IPv4만, HTTP는 2xx만 성공
- **기록 보관 기간**: 15일
- **코어 포트**: 9090, 9093, 9115

### 바꾸지 않는 값

다음 값은 코어와 관리자 서버의 약속이라 바꾸지 않는다.

- **코어 컨테이너 이름**: `prometheus`, `alertmanager`, `blackbox-exporter`. 관리자 서버 이름을 이와 겹치게 짓지 않는다.
- **네트워크 이름**: `monitoring`
- **관리자 서버 API 경로 세 개**: 관리자 서버 안의 경로가 다르면 앞단(리버스 프록시 등)에서 이 경로를 받아 넘긴다.

## 설치 순서

인터넷이 없는 서버에 설치하는 순서다. 인터넷이 되는 서버나 개발 PC에서는 [저장소에서 실행](#저장소에서-실행)이 더 간단하다.

### 내부망 서버에 옮겨 실행

1. Windows 빌드 PC의 저장소 폴더에서 묶음을 만든다. 이미지를 빌드하고 설정 검사, 규칙 테스트, 운영 명령 시험을 통과하면 `dist\monitoring-core-<버전>\`과 `dist\node-exporter-<버전>\`이 생긴다. `<버전>`은 커밋에서 나온 값이고, 커밋하지 않은 변경이 있으면 같은 버전 이름이 다른 내용에 붙을 수 있어 멈춘다.

   ```bat
   core-dev.bat build
   ```

2. 코어 서버에 설치 폴더(예: `/opt/monitoring`)를 만들고, `dist\monitoring-core-<버전>\` 폴더를 그 안에 옮긴다. 폴더 안의 `licenses/`와 `sources/`도 함께 옮긴다([라이선스](#라이선스)).
3. 묶음 폴더에서 설치를 준비한다. 관리자 서버 주소(`컨테이너 이름:포트`)를 물으면 넣는다. 설치 폴더의 `secrets/`에 token 파일을 만들고, 묶음 폴더에 `.env`를 만든다. token 폴더는 운영자만 열 수 있게(0700), 파일은 컨테이너 사용자(`nobody`)가 읽을 수 있게(0644) 만든다. token 값은 화면에 보이지 않는다.

   ```sh
   sh core-server.sh install
   ```

4. 묶음 폴더에서 코어를 띄운다. 파일이 온전한지 체크섬으로 확인하고, 설정과 token을 검사한 뒤, 이미지를 불러와 띄우고 세 컨테이너가 정상 상태가 될 때까지 최대 3분 기다린다. 이 폴더의 `compose.yml`은 인터넷에서 이미지를 받지 않는다. 서버가 켜질 때 Docker가 자동으로 켜지지 않으면 경고와 켜는 명령을 보인다.

   ```sh
   sh core-server.sh start
   ```

5. 관리자 서버를 띄운다. 코어가 `monitoring` 네트워크를 만들었으므로 코어 다음에 띄운다. 띄우는 옵션은 [관리자 서버 띄우기](#관리자-서버-띄우기)에 있다.
6. 감시 대상 서버에 OS exporter를 설치하고 방화벽을 연다([OS exporter 설치](#os-exporter-설치)).
7. 관리자 서버에 감시 대상을 등록하고 [설치 확인](#설치-확인)대로 동작을 본다.

관리자 서버가 없어도 코어는 정상으로 뜬다. 감시 대상이 없는 상태로 기다리다가, 관리자 서버가 뜨면 15~30초 안에 대상 목록을 받아 간다.

코어는 사람이 멈추지 않는 한 서버나 Docker가 재시작된 뒤 다시 뜬다. 세 컨테이너의 재시작 정책이 `unless-stopped`이기 때문이다. 다만 서버가 켜질 때 Docker가 자동으로 켜져야 한다.

운영 명령 없이 직접 실행할 수도 있다. 3, 4번 대신 묶음 폴더에서 다음을 실행한다. token은 설치 폴더의 `secrets/`(`../secrets`)에서 읽는다. token 파일이 없는데 띄우면 Docker가 그 자리에 빈 폴더를 만들므로, token을 먼저 만든다.

```sh
install -d -m 0700 ../secrets
openssl rand -hex 32 > ../secrets/alertmanager_webhook_token
chmod 0644 ../secrets/alertmanager_webhook_token
cp .env.example .env              # MONITORING_APP_ADDRESS를 관리자 서버의 컨테이너 이름:포트로 고친다
sha256sum -c SHA256SUMS           # 모든 줄이 OK여야 한다
docker load -i core-images.tar
docker compose up -d
docker compose ps                 # 세 컨테이너가 healthy여야 한다
```

### 저장소에서 실행

인터넷이 되는 서버나 개발 PC에서는 저장소 폴더에서 바로 빌드해 띄운다. token 파일은 `infrastructure/secrets/`에, 설정은 `infrastructure/.env`에 둔다.

```sh
cp infrastructure/.env.example infrastructure/.env
openssl rand -hex 32 > infrastructure/secrets/alertmanager_webhook_token
chmod 0644 infrastructure/secrets/alertmanager_webhook_token
```

Windows에서는 `core-dev.bat start`로 띄우고 `core-dev.bat stop`으로 멈춘다. 다른 OS에서는 같은 일을 하는 다음 명령을 쓴다.

```sh
docker compose -f infrastructure/compose.yml up -d --build
docker compose -f infrastructure/compose.yml ps
```

이 저장소의 관리자 서버(Manager)를 쓰면 `MONITORING_APP_ADDRESS`는 기본값 `manager-api:8080` 그대로 두고, 저장소 폴더에서 `docker compose up -d --build`로 띄운다. 준비는 [개발 가이드의 준비](DEVELOPMENT.md#준비)에 있다.

## 관리자 서버 만들기

관리자 서버가 감시부터 알림까지 하려면 다음 여섯 가지를 만든다. 앞의 세 가지는 코어가 부르는 API라서 경로와 형식을 정해진 대로 만들고, 뒤의 세 가지는 관리자 서버 안에서 자유롭게 만든다.

| 만들 것 | 누가 언제 | 하는 일 |
|---|---|---|
| 주소 감시 대상 목록 API | 코어가 15초마다 `GET /api/prometheus/sd/blackbox` | 확인할 주소와 확인 주기를 내줌 |
| 서버 감시 대상 목록 API | 코어가 30초마다 `GET /api/prometheus/sd/os-exporter` | 사용량을 모을 서버와 수집 주기를 내줌 |
| 장애 수신 API | 코어가 장애가 생기거나 바뀔 때 `POST /api/integrations/alertmanager/webhook` | 장애를 기록하고 알림을 보냄 |
| 결과 조회 | 관리자 서버가 필요할 때 코어의 `GET /api/v1/query` | 주소 확인 결과와 사용률을 화면에 보임 |
| 임계값 판단 | 관리자 서버 안의 주기 작업 | 사용률이 임계값을 넘으면 알림 |
| 알림 발송과 재알림 | 관리자 서버 안 | 문자, 메신저 등으로 보내고 재알림 주기를 지킴 |

DB 테이블은 이 저장소의 기준 migration(`backend/src/main/resources/db/migration/`의 `V1__baseline.sql`)과 [임계값 판단용 참고 DDL](INTEGRATION.md#참고용-ddl)을 참고한다.

### 주소 감시 대상 목록 API

- **요청**: 코어가 15초마다 `GET /api/prometheus/sd/blackbox`를 부른다. 인증은 없다.
- **응답**: `200`, `Content-Type: application/json`으로 JSON 배열을 돌려준다. 켜져 있는 감시 주소 하나가 항목 하나이고, 대상이 없으면 `[]`이다.
- **오류**: DB 오류 등으로 목록을 만들지 못하면 `[]`이 아니라 `5xx`를 돌려준다. `[]`을 받으면 코어가 모든 감시를 멈추지만, 오류를 받으면 마지막 목록으로 감시를 이어 간다.

응답 예는 다음과 같다. label 값은 모두 문자열로 보낸다. 숫자로 보내면 코어가 목록 전체를 거부한다.

```json
[
  {
    "targets": ["https://example.com/health"],
    "labels": {
      "check_id": "12", "check_target_id": "31", "check_type": "HTTPS", "module": "http_2xx",
      "environment": "Production", "check_name": "전자결재 Health", "__scrape_interval__": "30s"
    }
  }
]
```

확인 방식마다 `targets`와 `module`에 넣는 값은 다음과 같다.

| 확인 방식(`check_type`) | `targets` | `module` |
|---|---|---|
| `ICMP`(ping) | host 또는 IP | `icmp` |
| `TCP` | `host:port` | `tcp_connect` |
| `HTTP`, `HTTPS` | 전체 URL | `http_2xx` |

`check_id`와 `check_target_id`는 감시 항목과 그 주소의 id이고, 장애를 받았을 때 어느 감시 항목인지 찾는 열쇠다. 한 번 정한 id는 바꾸지 않는다. `__scrape_interval__`은 확인 주기이고 `10s`~`240s`다. label마다의 뜻은 [통합 계약의 가용성 Check 대상](INTEGRATION.md#가용성-check-대상-get-apiprometheussdblackbox)에 있다.

### 서버 감시 대상 목록 API

- **요청**: 코어가 30초마다 `GET /api/prometheus/sd/os-exporter`를 부른다. 인증은 없다.
- **응답**: 주소 감시 대상 목록과 같은 형식이다. 감시와 사용량 수집을 모두 켠 서버 하나가 항목 하나이고, `targets`는 OS exporter의 `host:port`다.
- **오류**: 주소 감시 대상 목록과 같이 `5xx`를 돌려준다.

```json
[
  {
    "targets": ["10.0.0.21:9100"],
    "labels": {
      "server_id": "7", "server_name": "웹 서버 1", "environment": "Production", "os": "LINUX",
      "__scrape_interval__": "30s"
    }
  }
]
```

`server_id`는 사용량을 서버별로 찾는 열쇠라 한 번 정하면 바꾸지 않는다. `os`는 `LINUX` 또는 `WINDOWS`다.

### 장애 수신 API

- **요청**: 코어가 `POST /api/integrations/alertmanager/webhook`을 부른다. 장애가 처음 생기면 30초 기다렸다 보내고, 이어지면 5분마다, 풀리면 복구를 보낸다. 한 요청에 같은 감시 항목의 장애 여러 개(주소마다 하나)가 `alerts` 배열로 온다.
- **인증**: `Authorization: Bearer <token>` 헤더가 붙는다. token 파일 값과 고정 시간 비교로 맞춰 보고, 다르면 `401`을 돌려주고 기록하지 않는다.
- **응답**: 처리하면 `2xx`를 돌려준다. 알림 전송에 실패해도 `2xx`다. 실패를 돌려주면 코어가 같은 장애를 계속 다시 보낸다. 모르는 필드가 있어도 실패하지 않는다.

받는 내용 가운데 쓰는 필드는 다음과 같다.

```json
{
  "alerts": [
    {
      "status": "firing",
      "labels": {
        "alertname": "MonitoringCheckDown", "check_id": "12", "check_target_id": "31",
        "check_name": "전자결재 Health", "instance": "https://example.com/health"
      },
      "annotations": { "summary": "전자결재 Health 확인에 실패했습니다" },
      "startsAt": "2026-10-21T01:02:03.000Z",
      "endsAt": "0001-01-01T00:00:00Z",
      "fingerprint": "a1b2c3d4e5f60718"
    }
  ]
}
```

`alerts`의 항목마다 다음 순서로 처리한다.

1. `labels.check_id`로 감시 항목을 찾는다. 지운 감시 항목이면 기록만 남기고 알리지 않는다.
2. `fingerprint`와 `startsAt`으로 장애 한 번을 구분한다. 같은 값이 다시 오면 같은 장애가 이어지고, 복구된 뒤 다시 실패하면 `startsAt`이 새로 온다.
3. `status`가 `firing`이면 장애, `resolved`면 복구다. 복구 시각은 `endsAt`이고, 장애 중의 `endsAt`(`0001-01-01`)은 쓰지 않는다.
4. 같은 장애가 다시 오면 마지막 알림 뒤 그 감시 항목의 재알림 주기가 지났을 때만 다시 알린다.

`alertname`이 `MonitoringCheckProbeFailed`이면 대상이 아니라 코어의 확인 기능이 멈췄다는 뜻이다. label은 같아서 어느 감시 항목의 확인이 멈췄는지 알 수 있다. 전체 필드는 [통합 계약의 Alert](INTEGRATION.md#alert-post-apiintegrationsalertmanagerwebhook)에 있다.

### 코어에 결과 묻기

관리자 서버는 `http://prometheus:9090/api/v1/query`에 조회식을 `query` 파라미터로 넘겨 결과를 읽는다. 인증은 없다. 자주 쓰는 조회는 다음과 같다.

| 알고 싶은 것 | `query` 값 | 결과를 나누는 label |
|---|---|---|
| 주소마다 마지막 확인 결과(1 성공, 0 실패) | `probe_success{job="manager-blackbox"}` | `check_target_id` |
| 서버마다 수집 상태(1 수집 중, 0 실패) | `max by (server_id) (up{job="manager-os"})` | `server_id` |
| 한 서버의 사용률(0~100) | `manager:resource_usage_percent{server_id="7"}` | `resource_type`, `resource_key` |

응답은 `{"status":"success","data":{"result":[{"metric":{...},"value":[조회 시각,"값"]}]}}` 형태다. 읽을 때는 다음을 지킨다.

- `status`가 `success`가 아니거나 연결에 실패하면 "알 수 없음"으로 보이고, 화면이나 판단을 멈추지 않는다.
- `result`가 빈 배열이면 아직 수집하지 않았거나 수집에 실패했다는 뜻이다. 값이 `"NaN"`일 수도 있다.
- `value`의 첫 값은 조회한 시각이고 수집한 시각이 아니다. 값이 지금 것인지는 그 서버의 수집 상태가 1인지로 본다.
- 대상마다 따로 부르지 않고 한 번에 읽어 label로 나눈다.

응답 예와 자세한 규칙은 [통합 계약의 코어가 제공할 것](INTEGRATION.md#코어가-제공할-것)에 있다.

### 임계값 판단

CPU·Memory·디스크 임계값은 코어가 판단하지 않는다. 관리자 서버가 서버의 수집 주기마다 사용률을 읽어 임계값과 비교하고, 넘은 상태가 지속 시간 동안 이어지면 알린다. 값을 읽지 못하면 이미 알린 장애는 유지하고, 관리자 서버를 다시 띄우면 대기 중이던 판단을 처음부터 다시 잰다. Critical이 발생 중이면 같은 리소스의 Warning은 보내지 않는다. 전체 규칙과 참고 DDL은 [통합 계약의 리소스 임계값 판단](INTEGRATION.md#리소스-임계값-판단)에 있다.

### 관리자 서버 띄우기

관리자 서버는 다음 조건으로 띄운다.

- 코어 다음에 띄우고 `monitoring` 네트워크에 붙는다.
- 컨테이너 이름과 포트가 코어 `.env`의 `MONITORING_APP_ADDRESS`와 같다.
- 코어와 같은 token 파일을 컨테이너에 붙인다. 컨테이너 안에서는 `/run/secrets/alertmanager_webhook_token`에서 읽는다. 코드나 설정 파일에 값을 쓰지 않는다.
- 목록 API는 인증 없이 불리므로 관리자 서버 포트를 밖에 열지 않는다. 화면을 열어야 하면 `127.0.0.1`에만 묶는다.

`docker run`으로 띄우면 다음과 같다. `my-admin`, `my-admin-image`, 환경 변수 이름은 관리자 서버에 맞춘다.

```sh
docker run -d --name my-admin --network monitoring \
  -v /opt/monitoring/secrets/alertmanager_webhook_token:/run/secrets/alertmanager_webhook_token:ro \
  -e PROMETHEUS_URL=http://prometheus:9090 \
  -p 127.0.0.1:8080:8080 my-admin-image
```

이미 떠 있는 관리자 서버는 다시 띄우지 않고 `docker network connect monitoring my-admin`으로 네트워크에 붙여도 된다. compose로 띄우면 다음과 같다.

```yaml
services:
  my-admin:
    image: my-admin-image
    container_name: my-admin
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      PROMETHEUS_URL: http://prometheus:9090
    secrets:
      - alertmanager_webhook_token
    networks:
      - monitoring

secrets:
  alertmanager_webhook_token:
    file: /opt/monitoring/secrets/alertmanager_webhook_token

networks:
  monitoring:
    name: monitoring
    external: true
```

### 가장 작은 구현 예

다음은 코어가 부르는 API 세 개만 만든 Node.js 예다. 고정된 대상 목록을 내주고, 받은 장애를 콘솔에 찍는다. [설치 확인](#설치-확인)을 이 예로 시험했다. 실제 관리자 서버는 목록을 DB에서 만들고, 장애를 기록하고 알림을 보낸다.

```js
import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';

// 코어와 같은 token 파일을 읽는다. 값을 로그에 남기지 않는다.
const token = fs.readFileSync('/run/secrets/alertmanager_webhook_token', 'utf8').trim();

// label 값은 모두 문자열이다. id를 숫자로 보내면 코어가 목록 전체를 거부한다.
const addressTargets = [
  { targets: ['https://example.com/health'],
    labels: { check_id: '1', check_target_id: '11', check_type: 'HTTPS', module: 'http_2xx',
      environment: 'Production', check_name: '서비스 상태', __scrape_interval__: '30s' } },
];
const serverTargets = [
  { targets: ['10.0.0.21:9100'],
    labels: { server_id: '7', server_name: '웹 서버 1', environment: 'Production', os: 'LINUX',
      __scrape_interval__: '30s' } },
];

function json(res, body) {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/api/prometheus/sd/blackbox') return json(res, addressTargets);
  if (req.method === 'GET' && req.url === '/api/prometheus/sd/os-exporter') return json(res, serverTargets);
  if (req.method === 'POST' && req.url === '/api/integrations/alertmanager/webhook') {
    const given = Buffer.from(req.headers.authorization ?? '');
    const expected = Buffer.from(`Bearer ${token}`);
    if (given.length !== expected.length || !crypto.timingSafeEqual(given, expected)) {
      res.writeHead(401);
      return res.end();
    }
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      for (const alert of JSON.parse(body).alerts) {
        // status는 firing(장애) 또는 resolved(복구)다. 여기서 기록하고 알림을 보낸다.
        console.log(alert.status, alert.labels.check_id, alert.labels.instance, alert.annotations.summary);
      }
      // 알림 전송에 실패해도 2xx를 돌려준다. 실패를 돌려주면 코어가 같은 장애를 계속 다시 보낸다.
      res.writeHead(200);
      res.end();
    });
    return;
  }
  res.writeHead(404);
  res.end();
}).listen(8080);
```

이 저장소의 관리자 서버(Manager)는 이 여섯 가지 가운데 임계값 판단을 뺀 다섯 가지를 갖추고 있다.

## 설치 확인

코어, OS exporter, 관리자 서버를 띄운 뒤 다음 순서로 감시부터 알림까지 동작하는지 확인한다. 명령은 코어 서버의 묶음 폴더에서 실행한다.

1. 코어가 관리자 서버의 목록을 받았는지 본다. 브라우저로 `http://127.0.0.1:9090/targets`(다른 PC에서는 [SSH 터널](#포트))를 열면 `manager-blackbox`에 주소가, `manager-os`에 서버가 보이고 상태가 `UP`이다. `manager-blackbox`의 `UP`은 코어가 확인을 실행했다는 뜻일 뿐이라, 확인에 실패한 주소도 `UP`으로 보인다. 목록이 비어 있으면 코어가 관리자 서버에 닿지 못했으므로 로그를 본다.

   ```sh
   docker compose logs prometheus | grep "Unable to refresh"
   ```

2. 주소 확인 결과를 본다. 대상마다 1(성공)이나 0(실패)이 나온다.

   ```sh
   curl -sG http://127.0.0.1:9090/api/v1/query --data-urlencode 'query=probe_success{job="manager-blackbox"}'
   ```

3. 서버 사용량을 본다. `server_id`는 목록에 넣은 서버 id다. `resource_type`이 `CPU`, `MEMORY`, `STORAGE`인 값이 0~100으로 나오면 수집과 계산이 모두 동작한다. 처음 수집한 뒤 30초쯤 지나야 나온다.

   ```sh
   curl -sG http://127.0.0.1:9090/api/v1/query --data-urlencode 'query=manager:resource_usage_percent{server_id="7"}'
   ```

4. 장애가 관리자 서버에 오는지 본다. 일부러 없는 주소(예: `http://does-not-exist.invalid/`)를 감시 대상에 넣으면 약 2분 뒤 관리자 서버가 `firing` 장애를 받는다. 목록 갱신 15초, 실패가 이어지는 1분, 장애를 묶는 30초를 기다리기 때문이다. 오지 않으면 Alertmanager 로그에서 관리자 서버로 보내지 못했는지 본다.

   ```sh
   docker compose logs alertmanager | grep -i "notify"
   ```

## 운영 작업

코어를 운영하면서 하는 일은 다음과 같다. 명령은 지금 쓰는 버전의 묶음 폴더에서 실행한다. 저장소에서 실행했으면 `core-dev.bat`을 쓰거나 `docker compose` 뒤에 `-f infrastructure/compose.yml`을 붙인다.

- **관리자 서버 이름·포트 바꾸기**: `.env`의 `MONITORING_APP_ADDRESS`를 고친 뒤 `sh core-server.sh start`를 실행한다.
- **token 바꾸기**: `sh core-server.sh rotate-token`을 실행한다. 새 token을 만들고 Alertmanager를 다시 만든다. 파일만 바꾸고 `up -d`를 하면 Alertmanager가 옛 값을 계속 쓸 수 있어 이 명령을 쓴다. 관리자 서버도 시작할 때 token을 읽으므로 다시 띄운다.
- **빌드 전에 고치는 값 바꾸기**: 저장소에서 고친 뒤 새 묶음을 만들어 옮기고, 아래 갱신 절차를 따른다. 저장소에서 실행하면 `core-dev.bat start`로 다시 띄운다.
- **새 버전으로 갱신**: 새 묶음 폴더를 이전 묶음 폴더 옆에 옮기고, 새 폴더에서 `sh core-server.sh install`, `sh core-server.sh start`를 실행한다. `install`은 지금 떠 있는 버전의 폴더에서 `.env`를 가져온다. token은 설치 폴더에 있으므로 옮기지 않는다. 쌓은 기록과 장애 상태는 이어진다.
- **이전 버전으로 되돌리기**: 이전 묶음 폴더에서 `sh core-server.sh start`를 실행한다. 이전 버전으로 바꿀지 물으면 `y`를 넣는다. 이전 버전의 이미지를 지우지 않았으면 다시 불러오지 않는다.
- **띄운 버전 확인**: `sh core-server.sh status`를 실행한다. 떠 있는 버전, 그 버전을 빌드한 커밋, 세 컨테이너의 상태가 보인다.
- **멈추기**: `docker compose down`을 실행한다. 쌓은 기록은 volume에 남고, 서버가 재시작돼도 다시 뜨지 않는다. 다시 띄우려면 `sh core-server.sh start`를 실행한다.
- **기록까지 지우기**: `docker compose down -v`를 실행한다. 되돌릴 수 없어 운영 명령에 넣지 않았다.
- **로그 보기**: `docker logs -f prometheus`를 실행한다.

## 문제 해결

코어가 뜨지 않거나 동작하지 않으면 다음 표에서 보이는 것을 찾는다. 운영 명령은 대부분의 경우를 띄우기 전에 알아보고 고치는 방법을 함께 보인다. 아래 표는 그 안내에 없는 경우와 직접 실행했을 때를 다룬다.

| 보이는 것 | 원인 | 해결 |
|---|---|---|
| `Docker에 연결하지 못했습니다` | Docker가 꺼져 있거나 이 사용자가 `docker`를 쓸 권한이 없음 | `sudo systemctl start docker`, 또는 사용자를 `docker` 그룹에 넣는다 |
| `서버가 켜질 때 Docker가 자동으로 켜지지 않습니다` | Docker 서비스가 자동 시작으로 설정되지 않음 | `sudo systemctl enable docker` |
| `이전 버전(…)으로 바꾸려면 터미널에서 실행해 확인해 주세요` | 이전 버전 폴더에서 `start`를 실행했는데, 입력을 받을 수 없는 방식으로 실행함 | 이전 버전으로 되돌리려는 것이 맞으면 터미널에서 직접 실행하고 `y`를 넣는다 |
| `token 파일이 없습니다`와 `묶음 폴더 안에 token이 있습니다` | 예전 방식대로 token을 묶음 폴더의 `secrets/`에 둠 | 설치 폴더의 `secrets/`로 옮기고(권한 0644) 관리자 서버의 경로도 바꾼다 |
| `MONITORING_APP_ADDRESS가 필요합니다` | 직접 실행했는데 `.env`가 없거나 관리자 서버 주소가 비어 있음 | `.env`를 만들고 관리자 서버 주소를 넣는다 |
| `alertmanager`가 계속 다시 시작하고 로그에 `필요한 파일 없음` | token 파일이 없거나 비어 있음 | 로그의 경로에 token 파일을 만든다 |
| `alertmanager`가 계속 다시 시작하고 로그에 `필요한 파일 읽기 불가` | token 파일 권한이 0600 등이라 컨테이너 사용자가 읽지 못함 | 파일 권한을 0644로 바꾼다 |
| `alertmanager_webhook_token`이 폴더임 | token 파일 없이 띄워서 Docker가 그 자리에 빈 폴더를 만듦 | `docker compose down` 뒤 그 폴더를 지우고 token 파일을 만든다 |
| `No such image` | 코어 이미지를 불러오지 않음 | `docker load -i core-images.tar`로 불러온다 |
| `sha256sum`이 `FAILED` | 옮기는 중에 파일이 망가짐 | 묶음을 다시 옮긴다 |
| 관리자 서버가 `network monitoring not found`로 뜨지 않음 | 코어보다 먼저 띄움 | 코어를 먼저 띄운 뒤 관리자 서버를 띄운다 |
| 장애 수신 API가 모두 `401` | 코어와 관리자 서버의 token 파일이 다름 | 두 쪽이 같은 파일을 가리키게 하고 둘 다 다시 띄운다 |

## OS exporter 설치

CPU, Memory, 디스크를 볼 감시 대상 서버마다 OS exporter를 설치한다. Linux는 [node_exporter](https://github.com/prometheus/node_exporter)(포트 9100, v1.12.1에서 확인), Windows는 [windows_exporter](https://github.com/prometheus-community/windows_exporter)(포트 9182, 공식 문서의 metric 이름 기준)다. 설치하기 전에 [감시 대상 서버의 방화벽](#감시-대상-서버의-방화벽)을 먼저 설정하고, 설치한 뒤에는 관리자 서버에 그 서버를 등록해 사용량 수집을 켠다. 이 저장소의 Manager는 서버 상세에서 수집을 켜고 연결 시험을 누른다.

Linux 서버에 필요한 파일은 코어 묶음과 함께 생기는 `dist/node-exporter-<버전>/` 폴더에 모두 있다. 인터넷이 없는 서버에는 이 폴더를 옮기고, 먼저 `sha256sum -c SHA256SUMS`로 파일이 온전한지 확인한다.

| 파일 | 쓰는 때 |
|---|---|
| `node-exporter-image.tar`, `compose.yml` | Docker가 있는 서버 |
| `node_exporter-1.12.1.linux-amd64.tar.gz`, `node_exporter.service` | Docker가 없는 서버 |

### Docker가 있는 Linux 서버

node_exporter 묶음 폴더에서 이미지를 불러온 뒤 띄운다. 인터넷이 되는 서버면 이미지를 불러오지 않고 저장소의 [`infrastructure/node-exporter/compose.yml`](../infrastructure/node-exporter/compose.yml)만 복사해 띄워도 된다. 서버의 네트워크와 프로세스를 그대로 보고, 루트 파일 시스템은 읽기 전용으로 붙인다. 쓰기 권한과 privileged 권한은 주지 않는다.

```sh
docker load -i node-exporter-image.tar
docker compose up -d
```

### Docker가 없는 Linux 서버

node_exporter 묶음 폴더에서 실행 파일을 풀어 설치하고, 묶음의 systemd unit(저장소의 [`infrastructure/node-exporter/node_exporter.service`](../infrastructure/node-exporter/node_exporter.service)과 같다)으로 서비스를 켠다. 인터넷이 되는 서버면 [공식 릴리스](https://github.com/prometheus/node_exporter/releases/tag/v1.12.1)에서 같은 파일을 받아도 된다.

```sh
tar xzf node_exporter-1.12.1.linux-amd64.tar.gz
sudo install -m 0755 node_exporter-1.12.1.linux-amd64/node_exporter /usr/local/bin/node_exporter
sudo useradd --system --no-create-home --shell /usr/sbin/nologin node_exporter
sudo cp node_exporter.service /etc/systemd/system/node_exporter.service
sudo systemctl daemon-reload
sudo systemctl enable --now node_exporter
```

### Windows 서버

[windows_exporter 릴리스](https://github.com/prometheus-community/windows_exporter/releases)의 MSI로 설치한다. 설치 방법과 옵션은 그 저장소의 README를 따른다.

### node_exporter 응답 확인

코어 서버에서 다음을 실행해 exporter가 응답하는지 확인한다. `10.0.0.21`은 감시 대상 서버 주소의 예다. `# HELP`로 시작하는 줄이 보이면 된다.

```sh
curl -s http://10.0.0.21:9100/metrics | head
```

### node_exporter 갱신과 멈추기

갱신하거나 멈추는 방법은 설치 방식마다 다르다. 멈춘 서버는 관리자 서버에서도 수집을 끈다. 끄지 않으면 수집 실패가 계속 기록된다.

- **Docker 방식 갱신**: 새 node_exporter 묶음 폴더에서 `docker load -i node-exporter-image.tar` 뒤 `docker compose up -d`를 실행한다.
- **Docker 방식 멈추기**: 묶음 폴더에서 `docker compose down`을 실행한다.
- **systemd 방식 갱신**: 새 실행 파일을 같은 경로에 `sudo install`로 덮어쓴 뒤 `sudo systemctl restart node_exporter`를 실행한다.
- **systemd 방식 멈추기**: `sudo systemctl disable --now node_exporter`를 실행한다.

## 보안 설정

이 시스템은 대부분의 통신에 인증이 없다. 그래서 포트를 어디에 열지가 보안의 중심이다. 기본 원칙은 두 가지다. 코어 서버의 포트는 밖에 열지 않고, 감시 대상 서버의 exporter 포트는 코어 서버에만 연다.

### 포트

이 시스템이 쓰는 포트는 다음과 같다.

| 포트 | 듣는 것 | 열리는 곳 | 인증 | 누가 닿아야 하는가 |
|---|---|---|---|---|
| 9090 | Prometheus | 코어 서버의 `127.0.0.1` | 없음 | 코어 서버의 운영자, 같은 네트워크의 관리자 서버 |
| 9093 | Alertmanager | 코어 서버의 `127.0.0.1` | 없음 | 코어 서버의 운영자 |
| 9115 | Blackbox Exporter | 코어 서버의 `127.0.0.1` | 없음 | Prometheus(같은 네트워크) |
| 8080 | 관리자 서버 API(이 저장소의 Manager) | 코어 서버의 `127.0.0.1` | 화면용 API는 없음, 장애 수신은 token | 코어(같은 네트워크), 화면 |
| 8081 | 관리자 서버 화면(이 저장소의 Manager) | 코어 서버의 `127.0.0.1` | 없음 | 화면을 쓰는 사람 |
| 9100 | node_exporter | 감시 대상 Linux 서버의 모든 주소 | 없음 | 코어 서버 |
| 9182 | windows_exporter | 감시 대상 Windows 서버의 모든 주소 | 없음 | 코어 서버 |

코어 서버의 포트는 모두 `127.0.0.1`에 묶여 있어 다른 PC에서 접속할 수 없다. 이 바인딩을 `0.0.0.0`으로 바꾸지 않는다. Prometheus, Alertmanager, 화면용 API에 인증이 없어서, 열면 누구나 설정과 감시 결과를 보고 바꿀 수 있기 때문이다. 다른 PC에서 화면을 보려면 SSH 터널을 쓴다. 다음은 운영자 PC에서 실행해 화면(8081)을 PC의 `http://localhost:8081`로 여는 예다.

```sh
ssh -L 8081:127.0.0.1:8081 운영자@코어서버
```

여러 사람이 화면을 써야 해서 포트를 열어야 하면, 인증을 거는 리버스 프록시(사내 SSO 등)를 앞에 두고 프록시만 연다. 이 구성은 이 저장소가 제공하지 않는다.

### 코어 서버의 방화벽

코어 서버에는 들어오는 연결을 열 필요가 없다. 운영자의 SSH만 연다. 나가는 연결은 다음을 허용한다.

- 감시할 주소: 확인 방식에 따라 ICMP(ping), 대상 TCP 포트, HTTP·HTTPS 포트
- 감시 대상 서버의 exporter 포트: 9100, 9182
- 관리자 서버가 외부 메신저로 알리면 그 서비스(예: Telegram은 `api.telegram.org`의 443)

### 감시 대상 서버의 방화벽

exporter는 인증과 암호화 없이 열린다([#15](https://github.com/subinggrae/server-monitor/issues/15)). 그래서 감시 대상 서버의 방화벽에서 exporter 포트를 코어 서버에만 연다. 관리자 서버의 연결 시험도 코어 서버에서 exporter를 읽으므로 코어 서버 하나만 허용하면 된다. 예를 들어 `ufw`를 쓰는 Linux 서버에서 코어 서버가 `10.0.0.5`면 다음과 같다.

```sh
sudo ufw allow from 10.0.0.5 to any port 9100 proto tcp
```

이 규칙은 `ufw`가 켜져 있고 들어오는 연결의 기본 정책이 `deny`일 때만 다른 서버를 막는다. `sudo ufw status verbose`로 확인한다. Windows 서버는 Windows 방화벽의 인바운드 규칙에서 9182 포트의 원격 주소를 코어 서버로 제한한다.

### 비밀값

코어에 필요한 비밀값은 장애 수신 token 하나다. 이 저장소의 관리자 서버(Manager)는 그 밖의 비밀값도 `infrastructure/secrets/`의 파일로 둔다.

| 파일 | 하는 일 | 읽는 쪽 |
|---|---|---|
| `alertmanager_webhook_token` | 코어가 장애 수신 API를 부를 때 붙이는 Bearer token | 코어(Alertmanager), 관리자 서버 |
| `manager_db_password` | Manager DB 비밀번호 | 이 저장소의 관리자 서버 |
| `manager_db_root_password` | 개발용 DB의 root 비밀번호 | 개발용 DB(`local-db` profile) |
| `telegram_bot_token` | Telegram Bot API token | 이 저장소의 관리자 서버 |
| `telegram_webhook_secret` | Telegram이 관리자 서버의 webhook을 부를 때 확인하는 값 | 이 저장소의 관리자 서버 |

비밀값은 다음처럼 다룬다.

- 값은 `openssl rand -hex 32`처럼 길고 예측할 수 없게 만든다. `*.example` 파일의 값을 그대로 쓰지 않는다.
- 이 파일들은 Git에 올리지 않고(`.gitignore`), 설정 파일, 문서, 로그, 메신저에 옮기지 않는다.
- 값이 새어 나갔으면 새 값으로 바꾸고 그 값을 읽는 쪽을 모두 다시 띄운다. 장애 수신 token이면 코어와 관리자 서버를 함께 다시 띄운다.

### 밖에서 들어오는 연결

이 저장소의 관리자 서버(Manager)는 Telegram 그룹의 `/register` 명령을 받으려고 Telegram webhook을 쓸 수 있다. 이 기능을 쓰면 Telegram이 관리자 서버에 닿을 수 있는 HTTPS 주소(`TELEGRAM_WEBHOOK_PUBLIC_URL`)가 필요하다. 이 주소는 코어 서버 앞의 리버스 프록시에서 `/api/integrations/telegram/webhook` 경로 하나만 넘기고, 나머지 경로는 막는다. 이 기능을 쓰지 않으면 밖에서 들어오는 연결은 없다.

## 라이선스

묶음 폴더의 `licenses/`와 `sources/`는 지우지 않고 묶음과 함께 옮긴다. 코어와 node_exporter 묶음을 다른 회사의 프로젝트에 줄 때 라이선스 조건을 지키는 데 필요하기 때문이다. `licenses/`에는 각 프로그램의 라이선스 전문과 고지가, `sources/`에는 이미지 안의 BusyBox(GPL-2.0) 소스가 있고, 무엇이 들었는지는 `licenses/README.txt`에 있다. 라이선스 기록은 [LICENSES.md](LICENSES.md)에 있다.
