# 운영 가이드

모니터링 코어와 node_exporter를 설치하고, 코어에 붙일 관리자 서버를 만들고, 서버마다 다른 설정과 보안을 맞추는 방법을 설명한다. Prometheus를 모르는 개발자와 운영자가 이 문서만 보고 인터넷이 없는 서버에서도 감시부터 알림까지 동작시킬 수 있게 하는 것이 목적이다. 관리자 서버 API의 자세한 형식은 [통합 계약](INTEGRATION.md)에 있다. 통합 계약에서 "앱"이라고 부르는 것이 이 문서의 관리자 서버다.

## 구성

이 시스템은 세 부분으로 나뉜다. 이 저장소가 만들어 주는 것은 코어와 node_exporter 묶음이고, 관리자 서버는 코어를 쓰는 쪽이 만든다.

- **코어**: 감시 대상을 주기적으로 확인하고 서버 사용량을 모아 보관하며, 문제가 이어지면 경보를 만든다. 프로그램 세 개(Prometheus, Alertmanager, Blackbox Exporter)로 되어 있고, 코어 서버에 둔다.
- **관리자 서버**: 무엇을 감시할지 저장해 코어에 알려 주고, 코어의 경보를 받아 담당자에게 알린다. 화면, API, DB를 갖춘 웹 서비스이고, 코어와 같은 서버에 둔다.
- **OS exporter**: 서버의 CPU, Memory, 디스크 사용량을 코어가 읽을 수 있게 내보내는 작은 프로그램이다. Linux는 node_exporter, Windows는 windows_exporter이고, 감시 대상 서버마다 설치한다.

코어와 관리자 서버는 같은 서버에 둔다. 둘은 그 서버의 Docker 네트워크 `monitoring`으로만 통신하고, 다른 서버에 나눠 두는 구성은 지원하지 않는다([#15](https://github.com/subinggrae/server-monitor/issues/15)). 코어 하나에는 관리자 서버 하나만 붙는다. 웹 주소, TCP 포트, ping으로 살아 있는지만 확인하는 대상에는 아무것도 설치하지 않는다.

## 전체 흐름

감시부터 알림까지는 다음 순서로 흘러간다. 관리자 서버가 만들 것은 1, 5, 6, 7번이다.

1. 관리자 서버가 사용자가 등록한 감시 대상을 목록으로 내준다. 확인할 웹 주소·TCP 포트·ping 대상 목록과, 사용량을 모을 서버 목록 두 가지다.
2. 코어가 15~30초마다 그 목록을 받아 간다.
3. 코어가 대상마다 정한 주기(10~240초)로 주소가 살아 있는지 확인하고, 서버의 OS exporter에서 사용량을 모은다. 모은 값은 15일 보관한다.
4. 주소 확인 실패가 1분 넘게 이어지면 코어가 경보를 만들어 관리자 서버에 보낸다. 경보가 이어지면 5분마다, 풀리면 복구 경보를 다시 보낸다.
5. 관리자 서버가 경보를 받아 기록하고, 담당자에게 알림(문자, 메신저 등)을 보낸다. 같은 경보를 다시 받으면 정해 둔 재알림 주기가 지났을 때만 다시 알린다.
6. 관리자 서버가 코어에 확인 결과와 사용량을 물어 화면에 보인다.
7. 관리자 서버가 주기적으로 사용량을 물어 사용자가 정한 임계값과 비교하고, 넘으면 알린다. 임계값 판단은 코어가 하지 않는다.

## 서버 조건

코어 서버는 다음을 갖춘다.

- **Docker**: Docker Engine과 Docker Compose v2. Linux 서버를 기준으로 한다. Windows의 Docker Desktop은 개발 환경에서만 쓴다.
- **CPU**: x86-64. 코어 이미지는 x86-64에서 확인했다.
- **디스크**: 확인·수집 기록을 15일 보관할 공간. 대상 수에 비례해 늘어난다.
- **네트워크**: 감시할 주소와 감시 대상 서버의 exporter 포트에 닿아야 한다. 코어 자체는 인터넷에 나가지 않는다. 관리자 서버가 외부 메신저로 알리면 관리자 서버는 그 서비스에 닿아야 한다.

## 코어 실행

코어는 관리자 서버보다 먼저 띄운다. 코어가 `monitoring` 네트워크를 만들고 관리자 서버가 그 네트워크에 붙기 때문이다. 코어의 설정과 경보 규칙은 코어 이미지 안에 들어 있다([ADR 0009](decisions/0009-core-config-in-image.md)). 서버마다 다른 관리자 서버 주소와 webhook token만 서버에서 넣는다.

코어를 띄우는 방법은 두 가지다.

| 방법 | 쓰는 때 | 서버에 필요한 것 |
|---|---|---|
| 저장소에서 실행 | 개발 PC, 인터넷이 되는 서버 | 저장소, 인터넷 |
| 내부망 서버에 옮겨 실행 | 인터넷이 없는 서버 | 빌드한 코어 묶음 폴더 |

### 저장소에서 실행

저장소 폴더에서 다음 순서로 실행한다.

1. 코어 설정 파일을 만든다.

   ```sh
   cp infrastructure/.env.example infrastructure/.env
   ```

2. `infrastructure/.env`의 `MONITORING_APP_ADDRESS`를 관리자 서버 주소로 고친다. `monitoring` 네트워크 안에서 닿는 `컨테이너 이름:포트`이고, 이 저장소의 관리자 서버이면 기본값 `manager-api:8080` 그대로 둔다. `http://`는 붙이지 않는다.
3. webhook token 파일을 만든다. 코어가 관리자 서버에 경보를 보낼 때 붙이는 값이고, 같은 파일을 관리자 서버도 읽는다.

   ```sh
   openssl rand -hex 32 > infrastructure/secrets/alertmanager_webhook_token
   ```

4. 코어 이미지를 빌드해 띄운다.

   ```sh
   docker compose -f infrastructure/compose.yml up -d --build
   ```

5. 세 컨테이너(`prometheus`, `alertmanager`, `blackbox-exporter`)의 상태가 `healthy`인지 확인한다.

   ```sh
   docker compose -f infrastructure/compose.yml ps
   ```

### 내부망 서버에 옮겨 실행

인터넷이 되는 PC에서 코어 묶음을 만들고, 그 폴더를 내부망 서버로 옮겨 띄운다. 빌드하는 PC에는 저장소, Docker, Node 18 이상이 있어야 한다. 묶음은 x86-64 서버용이다.

1. 빌드하는 PC의 저장소 폴더에서 묶음을 만든다. 이미지를 빌드하고 설정 검사와 규칙 테스트를 통과하면 `dist/monitoring-core-<버전>/`과 `dist/node-exporter-<버전>/`이 생긴다. `<버전>`은 커밋에서 나온 값이고, 커밋하지 않은 변경이 있으면 끝에 `-dirty`가 붙는다.

   ```sh
   node scripts/build-core-bundle.mjs
   ```

2. `dist/monitoring-core-<버전>/` 폴더를 내부망 서버로 옮긴다.
3. 서버에서 그 폴더로 이동해 옮기는 중에 파일이 망가지지 않았는지 확인한다. 모든 줄이 `OK`여야 한다.

   ```sh
   sha256sum -c SHA256SUMS
   ```

4. 코어 이미지를 불러온다.

   ```sh
   docker load -i core-images.tar
   ```

5. 관리자 서버 주소 설정 파일과 webhook token 파일을 만든다. `.env`의 `MONITORING_APP_ADDRESS`는 [저장소에서 실행](#저장소에서-실행)의 2단계와 같이 고친다. 관리자 서버가 같은 token을 읽어야 하므로, 이미 관리자 서버가 쓰는 token이 있으면 그 값을 넣는다.

   ```sh
   cp .env.example .env
   openssl rand -hex 32 > secrets/alertmanager_webhook_token
   ```

6. 코어를 띄우고 세 컨테이너가 `healthy`인지 확인한다. 이 폴더의 `compose.yml`은 인터넷에서 이미지를 받지 않는다.

   ```sh
   docker compose up -d
   docker compose ps
   ```

### 띄운 뒤 확인

관리자 서버가 없어도 코어는 정상으로 뜬다. 감시 대상이 없는 상태로 기다리다가, 관리자 서버가 뜨면 15~30초 안에 대상 목록을 받아 간다. 관리자 서버를 띄운 뒤에는 코어 서버에서 `http://127.0.0.1:9090/targets`를 열어 대상이 `UP`인지 본다.

코어가 뜨지 않으면 원인은 다음과 같다.

| 보이는 것 | 원인 | 해결 |
|---|---|---|
| `MONITORING_APP_ADDRESS가 필요합니다` | `.env`가 없거나 관리자 서버 주소가 비어 있음 | `.env`를 만들고 관리자 서버 주소를 넣는다 |
| `alertmanager`가 계속 다시 시작함 | webhook token 파일이 없거나 비어 있음 | `docker compose logs alertmanager`의 안내대로 `secrets/`에 파일을 만든다 |
| `secrets/alertmanager_webhook_token`이 폴더임 | token 파일 없이 띄워서 Docker가 그 자리에 빈 폴더를 만듦 | `docker compose down` 뒤 그 폴더를 지우고 token 파일을 만든다 |
| `No such image` | 코어 이미지를 불러오지 않음 | `docker load -i core-images.tar` |
| `sha256sum`이 `FAILED` | 옮기는 중에 파일이 망가짐 | 묶음을 다시 옮긴다 |

### 운영 작업

코어를 운영하면서 하는 일은 다음과 같다. 명령은 코어 폴더에서 실행한다. 저장소에서 실행했으면 `docker compose` 뒤에 `-f infrastructure/compose.yml`을 붙인다.

- **관리자 서버 주소나 token 바꾸기**: 파일을 고친 뒤 `docker compose up -d`로 다시 띄운다. 이미지는 다시 빌드하지 않는다. token을 바꿨으면 관리자 서버도 다시 띄운다.
- **경보 규칙이나 코어 설정 바꾸기**: 저장소에서 고친다. 저장소에서 실행하면 `docker compose -f infrastructure/compose.yml up -d --build`로 다시 띄운다. 내부망 서버면 새 묶음을 만들어 옮긴 뒤 아래 갱신 절차를 따른다.
- **새 버전으로 갱신**: 새 묶음 폴더에서 `sha256sum -c SHA256SUMS`와 `docker load -i core-images.tar`를 실행하고, 이전 폴더의 `.env`와 `secrets/`를 새 폴더로 복사한 뒤 `docker compose up -d`. 쌓은 기록과 경보 상태는 이어진다.
- **이전 버전으로 되돌리기**: 이전 묶음 폴더에서 `docker compose up -d`. 이전 버전의 이미지를 지우지 않았으면 다시 불러올 필요가 없다.
- **띄운 버전 확인**: `docker compose ps`의 이미지 태그가 버전이다. 그 버전을 빌드한 커밋은 `docker image inspect monitoring-core-prometheus:<버전> | grep image.revision`으로 본다.
- **멈추기**: `docker compose down`. 쌓은 기록은 volume에 남는다.
- **기록까지 지우기**: `docker compose down -v`. 되돌릴 수 없다.
- **로그 보기**: `docker compose logs -f prometheus`

## 서버마다 바꾸는 설정

서버를 옮기거나 관리자 서버를 바꿀 때 바꾸는 값은 다음과 같다. 이 값들은 이미지를 다시 빌드하지 않고 바꾼다.

| 값 | 넣는 곳 | 예 | 바꾼 뒤 할 일 |
|---|---|---|---|
| 관리자 서버 주소 | 코어 폴더 `.env`의 `MONITORING_APP_ADDRESS` | `admin-api:8080` | 코어를 `docker compose up -d`로 다시 띄움 |
| 경보 수신 token | 코어 폴더 `secrets/alertmanager_webhook_token`과 관리자 서버의 설정 | 64자 무작위 값 | 코어와 관리자 서버를 함께 다시 띄움 |
| 코어 조회 주소 | 관리자 서버의 설정 | `http://prometheus:9090` | 관리자 서버를 다시 띄움 |
| exporter 접근 허용 주소 | 감시 대상 서버의 방화벽 | 코어 서버의 IP | [감시 대상 서버의 방화벽](#감시-대상-서버의-방화벽) |

관리자 서버 주소는 `monitoring` 네트워크 안에서 닿는 `컨테이너 이름:포트`다. 관리자 서버의 컨테이너 이름이나 포트를 바꾸면 이 값도 함께 바꾼다. `http://`는 붙이지 않는다.

사용자가 화면에서 바꾸는 값은 관리자 서버의 DB에 두고, 코어 설정에는 넣지 않는다. 다음 값이 그렇다. 관리자 서버가 목록 API로 내주거나 스스로 판단에 쓰므로 코어를 다시 띄우지 않는다.

- **감시 대상**: 주소, 확인 방식(HTTP, HTTPS, TCP, ping), 확인 주기(10~240초). 목록 API를 거쳐 30초 안에 반영된다.
- **사용량을 모을 서버**: 서버 주소, exporter 포트(기본 9100, Windows 9182), 수집 주기(10~240초). 목록 API를 거쳐 30초 안에 반영된다.
- **알림 설정**: 임계값, 지속 시간, 재알림 주기, 알림 받을 곳. 관리자 서버가 다음 판단부터 쓴다.

다음 값은 코어 이미지에 들어 있어 서버에서 바꾸지 않는다. 바꾸려면 저장소에서 고쳐 코어를 다시 빌드하고 모든 코어 서버를 갱신한다.

- 코어가 부르는 관리자 서버 API 경로 세 개
- 경보 규칙: 주소 확인 실패가 1분 이어지면 경보, 이어지면 5분마다 다시 보냄
- 코어의 포트(9090, 9093, 9115), 네트워크 이름 `monitoring`, 기록 보관 15일
- 주소 확인 방식의 세부(제한 시간 5초, IPv4만, HTTP는 2xx만 성공)

## 관리자 서버 구현

관리자 서버가 감시부터 알림까지 하려면 다음 여섯 가지를 만든다. 앞의 세 가지는 코어가 부르는 API라서 경로와 형식을 정해진 대로 만들고, 뒤의 세 가지는 관리자 서버 안에서 자유롭게 만든다.

| 만들 것 | 누가 부르는가 | 하는 일 | 자세한 형식 |
|---|---|---|---|
| 주소 감시 대상 목록 | 코어가 15초마다 `GET /api/prometheus/sd/blackbox` | 확인할 주소와 확인 주기를 JSON 목록으로 내줌 | [통합 계약](INTEGRATION.md#가용성-check-대상-get-apiprometheussdblackbox) |
| 서버 감시 대상 목록 | 코어가 30초마다 `GET /api/prometheus/sd/os-exporter` | 사용량을 모을 서버의 `주소:포트`와 수집 주기를 JSON 목록으로 내줌 | [통합 계약](INTEGRATION.md#os-exporter-수집-대상-get-apiprometheussdos-exporter) |
| 경보 수신 | 코어가 `POST /api/integrations/alertmanager/webhook` | token을 확인하고 경보를 기록한 뒤 알림을 보냄 | [통합 계약](INTEGRATION.md#alert-post-apiintegrationsalertmanagerwebhook) |
| 결과 조회 | 관리자 서버가 코어의 `GET /api/v1/query` | 주소 확인 결과와 사용률을 읽어 화면에 보임 | [통합 계약](INTEGRATION.md#코어가-제공할-것) |
| 임계값 판단 | 관리자 서버 안의 주기 작업 | 사용률을 읽어 임계값과 비교하고 넘으면 알림 | [통합 계약](INTEGRATION.md#리소스-임계값-판단) |
| 알림 발송과 재알림 | 관리자 서버 안 | 문자, 메신저 등으로 보내고 재알림 주기를 지킴 | 관리자 서버가 정함 |

관리자 서버는 다음 조건으로 띄운다.

- `monitoring` 네트워크에 붙는다. compose 파일이면 `networks`에 `monitoring`을 `external: true`로 둔다.
- 컨테이너 이름과 포트가 코어 설정의 관리자 서버 주소와 같다.
- 코어와 같은 경보 수신 token을 읽는다. 코드나 설정 파일에 값을 직접 쓰지 않는다.
- 목록 API 두 개는 코어가 인증 없이 부르므로 관리자 서버 포트를 밖에 열지 않는다([보안 설정](#보안-설정)).

DB 테이블은 이 저장소의 기준 migration(`backend/src/main/resources/db/migration/`의 `V1__baseline.sql`)과 [임계값 판단용 참고 DDL](INTEGRATION.md#참고용-ddl)을 참고한다.

### 가장 작은 구현 예

다음은 코어가 부르는 API 세 개만 만든 Node.js 예다. 고정된 대상 목록을 내주고, 받은 경보를 콘솔에 찍는다. 이 문서의 [설치 확인](#설치-확인)을 이 예로 시험했다. 실제 관리자 서버는 목록을 DB에서 만들고, 경보를 기록하고 알림을 보낸다.

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
      // 알림 전송에 실패해도 2xx를 돌려준다. 실패를 돌려주면 코어가 같은 경보를 계속 다시 보낸다.
      res.writeHead(200);
      res.end();
    });
    return;
  }
  res.writeHead(404);
  res.end();
}).listen(8080);
```

이 저장소에는 이 여섯 가지 가운데 임계값 판단을 뺀 다섯 가지를 갖춘 관리자 서버(Manager)가 있다. 저장소 폴더에서 `docker compose up -d --build`로 띄우고, 준비는 [개발 가이드의 준비](DEVELOPMENT.md#준비)에 있다.

## 설치 확인

코어와 node_exporter, 관리자 서버를 띄운 뒤 다음 순서로 감시부터 알림까지 동작하는지 확인한다. 명령은 코어 서버에서 실행한다.

1. 코어가 관리자 서버의 목록을 받았는지 본다. 브라우저로 `http://127.0.0.1:9090/targets`(다른 PC에서는 [SSH 터널](#포트))를 열면 `manager-blackbox`에 주소가, `manager-os`에 서버가 보이고 상태가 `UP`이다. 목록이 비어 있으면 코어의 로그에서 관리자 서버 주소로 요청이 실패했는지 본다.

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

4. 경보가 관리자 서버에 오는지 본다. 일부러 없는 주소(예: `http://does-not-exist.invalid/`)를 감시 대상에 넣으면 약 2분 뒤 관리자 서버가 `firing` 경보를 받는다. 목록 갱신 15초, 실패가 이어지는 1분, 경보를 묶는 30초를 기다리기 때문이다. 경보가 오지 않으면 Alertmanager 로그에서 관리자 서버로 보내지 못했는지 본다.

   ```sh
   docker compose logs alertmanager | grep -i "notify"
   ```

## OS exporter 설치

CPU, Memory, Storage를 볼 감시 대상 서버마다 OS exporter를 설치한다. Linux는 [node_exporter](https://github.com/prometheus/node_exporter)(기본 포트 9100, v1.12.1에서 확인), Windows는 [windows_exporter](https://github.com/prometheus-community/windows_exporter)(기본 포트 9182, 공식 문서의 metric 이름 기준)다. 설치한 뒤에는 관리자 서버에 그 서버를 등록해 사용량 수집을 켠다. 이 저장소의 Manager는 서버 상세에서 수집을 켜고 연결 시험을 누른다. 그 전에 [감시 대상 서버의 방화벽](#감시-대상-서버의-방화벽)을 먼저 설정한다.

Linux 서버에 필요한 파일은 [코어 묶음을 만들 때](#내부망-서버에-옮겨-실행) 함께 생기는 `dist/node-exporter-<버전>/` 폴더에 모두 있다. 인터넷이 없는 감시 대상 서버에는 이 폴더를 옮기고, 먼저 `sha256sum -c SHA256SUMS`로 파일이 온전한지 확인한다. 폴더에 든 파일은 다음과 같다.

| 파일 | 쓰는 때 |
|---|---|
| `node-exporter-image.tar`, `compose.yml` | Docker가 있는 서버 |
| `node_exporter-1.12.1.linux-amd64.tar.gz`, `node_exporter.service` | Docker가 없는 서버 |

### Docker가 있는 Linux 서버

node_exporter 묶음 폴더에서 이미지를 불러온 뒤 띄운다. 인터넷이 되는 서버면 이미지를 불러오지 않아도 되고, 저장소의 [`infrastructure/node-exporter/compose.yml`](../infrastructure/node-exporter/compose.yml)만 복사해 띄워도 된다. 서버의 네트워크와 프로세스를 그대로 보고, 루트 파일 시스템은 읽기 전용으로 붙인다. 쓰기 권한과 privileged 권한은 주지 않는다.

```sh
docker load -i node-exporter-image.tar
docker compose up -d
```

### Docker가 없는 Linux 서버

node_exporter 묶음 폴더에서 실행 파일을 풀어 설치한다. 인터넷이 되는 서버면 [공식 릴리스](https://github.com/prometheus/node_exporter/releases/tag/v1.12.1)에서 같은 파일을 받아도 된다.

```sh
tar xzf node_exporter-1.12.1.linux-amd64.tar.gz
sudo install -m 0755 node_exporter-1.12.1.linux-amd64/node_exporter /usr/local/bin/node_exporter
sudo useradd --system --no-create-home --shell /usr/sbin/nologin node_exporter
```

그다음 묶음의 systemd unit(저장소의 [`infrastructure/node-exporter/node_exporter.service`](../infrastructure/node-exporter/node_exporter.service)과 같다)을 복사하고 서비스로 켠다.

```sh
sudo cp node_exporter.service /etc/systemd/system/node_exporter.service
sudo systemctl daemon-reload
sudo systemctl enable --now node_exporter
```

### Windows 서버

[windows_exporter 릴리스](https://github.com/prometheus-community/windows_exporter/releases)의 MSI로 설치한다. 설치 방법과 옵션은 그 저장소의 README를 따른다.

### 설치 확인

코어 서버에서 다음을 실행해 exporter가 응답하는지 확인한다. `10.0.0.21`은 감시 대상 서버 주소의 예다. 응답 첫 줄들에 `# HELP`로 시작하는 줄이 보이면 된다.

```sh
curl -s http://10.0.0.21:9100/metrics | head
```

## 보안 설정

이 시스템은 대부분의 통신에 인증이 없다. 그래서 포트를 어디에 열지가 보안의 중심이다. 기본 원칙은 두 가지다. 코어 서버의 포트는 밖에 열지 않고, 감시 대상 서버의 exporter 포트는 코어 서버에만 연다.

### 포트

이 시스템이 쓰는 포트는 다음과 같다.

| 포트 | 듣는 것 | 열리는 곳 | 인증 | 누가 닿아야 하는가 |
|---|---|---|---|---|
| 9090 | Prometheus | 코어 서버의 `127.0.0.1` | 없음 | 코어 서버의 운영자, 같은 네트워크의 관리자 서버 |
| 9093 | Alertmanager | 코어 서버의 `127.0.0.1` | 없음 | 코어 서버의 운영자 |
| 9115 | Blackbox Exporter | 코어 서버의 `127.0.0.1` | 없음 | Prometheus(같은 네트워크) |
| 8080 | 관리자 서버 API(이 저장소의 Manager) | 코어 서버의 `127.0.0.1` | 화면용 API는 없음, webhook은 token | 코어(같은 네트워크), 화면 |
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

- 감시할 주소: Check 방식에 따라 ICMP(ping), 대상 TCP 포트, HTTP·HTTPS 포트
- 감시 대상 서버의 exporter 포트: 9100, 9182
- 관리자 서버가 Telegram으로 알리면 `api.telegram.org`의 443

### 감시 대상 서버의 방화벽

exporter는 인증과 암호화 없이 열린다([#15](https://github.com/subinggrae/server-monitor/issues/15)). 그래서 감시 대상 서버의 방화벽에서 exporter 포트를 코어 서버에만 연다. 관리자 서버의 연결 시험도 코어 서버에서 exporter를 읽으므로 코어 서버 하나만 허용하면 된다. 예를 들어 `ufw`를 쓰는 Linux 서버에서 코어 서버가 `10.0.0.5`면 다음과 같다.

```sh
sudo ufw allow from 10.0.0.5 to any port 9100 proto tcp
```

이 규칙은 `ufw`가 켜져 있고 들어오는 연결의 기본 정책이 `deny`일 때만 다른 서버를 막는다. `sudo ufw status verbose`로 확인한다. Windows 서버는 Windows 방화벽의 인바운드 규칙에서 9182 포트의 원격 주소를 코어 서버로 제한한다.

### 비밀값

비밀값은 `infrastructure/secrets/`의 파일로 둔다. 파일마다 하는 일은 다음과 같다.

| 파일 | 하는 일 | 읽는 쪽 |
|---|---|---|
| `alertmanager_webhook_token` | 코어가 관리자 서버의 webhook을 부를 때 붙이는 Bearer token | 코어(Alertmanager), 관리자 서버 |
| `manager_db_password` | Manager DB 비밀번호 | 이 저장소의 관리자 서버 |
| `manager_db_root_password` | 개발용 DB의 root 비밀번호 | 개발용 DB(`local-db` profile) |
| `telegram_bot_token` | Telegram Bot API token | 이 저장소의 관리자 서버 |
| `telegram_webhook_secret` | Telegram이 관리자 서버의 webhook을 부를 때 확인하는 값 | 이 저장소의 관리자 서버 |

비밀값은 다음처럼 다룬다.

- 값은 `openssl rand -hex 32`처럼 길고 예측할 수 없게 만든다. `*.example` 파일의 값을 그대로 쓰지 않는다.
- 이 파일들은 Git에 올리지 않고(`.gitignore`), 설정 파일, 문서, 로그, 메신저에 옮기지 않는다.
- 값이 새어 나갔으면 새 값으로 바꾸고 그 값을 읽는 쪽을 모두 다시 띄운다. webhook token이면 코어와 관리자 서버를 함께 다시 띄운다.

### 밖에서 들어오는 연결

이 저장소의 관리자 서버(Manager)는 Telegram 그룹의 `/register` 명령을 받으려고 Telegram webhook을 쓸 수 있다. 이 기능을 쓰면 Telegram이 관리자 서버에 닿을 수 있는 HTTPS 주소(`TELEGRAM_WEBHOOK_PUBLIC_URL`)가 필요하다. 이 주소는 코어 서버 앞의 리버스 프록시에서 `/api/integrations/telegram/webhook` 경로 하나만 넘기고, 나머지 경로는 막는다. 이 기능을 쓰지 않으면 밖에서 들어오는 연결은 없다.
