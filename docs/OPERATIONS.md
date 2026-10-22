# 운영 가이드

모니터링 코어, 앱, OS exporter를 서버에 설치하고 안전하게 운영하는 방법을 설명한다. 서버를 준비하고 띄우는 운영자가 이 문서만 보고 설치와 보안 설정을 마칠 수 있게 하는 것이 목적이다. 코어에 붙일 앱을 만드는 개발자는 [통합 계약](INTEGRATION.md)을 함께 읽는다.

## 구성

서버는 두 종류다. 코어와 앱을 함께 두는 서버 한 대와, 감시할 서버 여러 대다.

| 서버 | 띄우는 것 | 하는 일 |
|---|---|---|
| 코어 서버 | 코어(Prometheus, Alertmanager, Blackbox Exporter), 앱 | 감시 대상을 확인하고 수집해 Alert를 만들고, 앱이 알린다 |
| 감시 대상 서버 | OS exporter(Linux는 node_exporter, Windows는 windows_exporter) | 서버의 CPU, Memory, Storage 값을 코어가 읽게 내보낸다 |

코어와 앱은 같은 서버에 둔다. 둘은 그 서버의 Docker 네트워크 `monitoring`으로만 통신하고, 다른 서버에 나눠 두는 구성은 지원하지 않는다([#15](https://github.com/subinggrae/server-monitor/issues/15)). 코어 하나에는 앱 하나만 붙는다. 가용성만 확인하는 대상(웹 주소, TCP 포트, ping)에는 아무것도 설치하지 않는다.

## 서버 조건

코어 서버는 다음을 갖춘다.

- **Docker**: Docker Engine과 Docker Compose v2. Linux 서버를 기준으로 한다. Windows의 Docker Desktop은 개발 환경에서만 쓴다.
- **CPU**: x86-64. 코어 이미지는 x86-64에서 확인했다.
- **디스크**: 확인·수집 기록을 15일 보관할 공간. 대상 수에 비례해 늘어난다.
- **네트워크**: 감시할 주소와 감시 대상 서버의 exporter 포트에 닿아야 한다. 코어 자체는 인터넷에 나가지 않는다. 앱이 Telegram으로 알리면 앱은 Telegram Bot API(`api.telegram.org`)에 닿아야 한다.

아래 방법은 코어 서버가 인터넷에서 이미지를 받을 수 있어야 한다. 인터넷이 없는 내부망 서버에 옮겨 설치하는 방법은 요구사항 0010에서 만들고 있고, 만들면 이 문서에 더한다.

## 코어 실행

코어는 앱보다 먼저 띄운다. 코어가 `monitoring` 네트워크를 만들고 앱이 그 네트워크에 붙기 때문이다. 다음 절차는 코어 서버에 저장소를 받은 상태에서 저장소 폴더에서 실행한다.

1. 코어 설정 파일을 만든다.

   ```sh
   cp infrastructure/.env.example infrastructure/.env
   ```

2. `infrastructure/.env`의 `MONITORING_APP_ADDRESS`를 앱 주소로 고친다. `monitoring` 네트워크 안에서 닿는 `컨테이너 이름:포트`이고, 이 저장소의 앱이면 기본값 `manager-api:8080` 그대로 둔다. `http://`는 붙이지 않는다.
3. webhook token 파일을 만든다. 코어가 앱에 Alert를 보낼 때 붙이는 값이고, 같은 파일을 앱도 읽는다.

   ```sh
   openssl rand -hex 32 > infrastructure/secrets/alertmanager_webhook_token
   ```

4. 코어를 띄운다.

   ```sh
   docker compose -f infrastructure/compose.yml up -d
   ```

5. 세 컨테이너(`prometheus`, `alertmanager`, `blackbox-exporter`)의 상태가 `healthy`인지 확인한다.

   ```sh
   docker compose -f infrastructure/compose.yml ps
   ```

앱이 없어도 코어는 정상으로 뜬다. 감시 대상이 없는 상태로 기다리다가, 앱이 뜨면 15~30초 안에 대상 목록을 받아 간다. 앱을 띄운 뒤에는 코어 서버에서 `http://127.0.0.1:9090/targets`를 열어 대상이 `UP`인지 본다.

코어를 운영하면서 하는 일은 다음과 같다.

- **앱 주소나 token 바꾸기**: 파일을 고친 뒤 `docker compose -f infrastructure/compose.yml up -d`로 다시 띄운다. token을 바꿨으면 앱도 다시 띄운다.
- **Alert 규칙이나 Prometheus 설정 바꾸기**: 저장소에서 고친 뒤 `docker compose -f infrastructure/compose.yml restart prometheus`를 실행한다.
- **멈추기**: `docker compose -f infrastructure/compose.yml down`. 쌓은 기록은 volume에 남는다.
- **기록까지 지우기**: `docker compose -f infrastructure/compose.yml down -v`. 되돌릴 수 없다.
- **로그 보기**: `docker compose -f infrastructure/compose.yml logs -f prometheus`

## 앱 실행

앱은 코어가 부르는 API 세 개를 제공해야 한다. 감시 대상 목록 두 개와 Alert를 받는 webhook 하나다. 경로, 형식, 인증은 [통합 계약의 앱이 제공할 API](INTEGRATION.md#앱이-제공할-api)에 있고, 이 경로는 바꾸지 않는다. 앱은 다음 조건으로 띄운다.

- `monitoring` 네트워크에 붙는다(compose의 `networks`에 `external: true`).
- 컨테이너 이름과 포트가 코어 설정의 `MONITORING_APP_ADDRESS`와 같다.
- 코어와 같은 webhook token 파일을 읽는다.
- 코어의 조회 API는 `http://prometheus:9090`으로 부른다.

이 저장소의 앱(Manager)은 저장소 폴더에서 다음처럼 띄운다. 비밀값 파일과 `.env` 준비는 [개발 가이드의 준비](DEVELOPMENT.md#준비)에 있다.

```sh
docker compose up -d --build
```

## OS exporter 설치

CPU, Memory, Storage를 볼 감시 대상 서버마다 OS exporter를 설치한다. Linux는 [node_exporter](https://github.com/prometheus/node_exporter)(기본 포트 9100, v1.12.1에서 확인), Windows는 [windows_exporter](https://github.com/prometheus-community/windows_exporter)(기본 포트 9182, 공식 문서의 metric 이름 기준)다. 설치한 뒤에는 앱 화면의 서버 상세에서 수집을 켜고 연결 시험을 누른다. 그 전에 [감시 대상 서버의 방화벽](#감시-대상-서버의-방화벽)을 먼저 설정한다.

### Docker가 있는 Linux 서버

저장소의 [`infrastructure/node-exporter/compose.yml`](../infrastructure/node-exporter/compose.yml)을 서버에 복사하고 그 폴더에서 띄운다. 서버의 네트워크와 프로세스를 그대로 보고, 루트 파일 시스템은 읽기 전용으로 붙인다. 쓰기 권한과 privileged 권한은 주지 않는다.

```sh
docker compose -f compose.yml up -d
```

### Docker가 없는 Linux 서버

공식 릴리스에서 바이너리를 받아 설치한다. 다음은 x86-64 서버의 예다.

```sh
VERSION=1.12.1
curl -LO https://github.com/prometheus/node_exporter/releases/download/v$VERSION/node_exporter-$VERSION.linux-amd64.tar.gz
tar xzf node_exporter-$VERSION.linux-amd64.tar.gz
sudo install -m 0755 node_exporter-$VERSION.linux-amd64/node_exporter /usr/local/bin/node_exporter
sudo useradd --system --no-create-home --shell /usr/sbin/nologin node_exporter
```

그다음 `/etc/systemd/system/node_exporter.service`를 만들고 `sudo systemctl enable --now node_exporter`로 켠다.

```ini
[Unit]
Description=node_exporter
After=network-online.target

[Service]
User=node_exporter
ExecStart=/usr/local/bin/node_exporter
Restart=on-failure

[Install]
WantedBy=multi-user.target
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
| 9090 | Prometheus | 코어 서버의 `127.0.0.1` | 없음 | 코어 서버의 운영자, 같은 네트워크의 앱 |
| 9093 | Alertmanager | 코어 서버의 `127.0.0.1` | 없음 | 코어 서버의 운영자 |
| 9115 | Blackbox Exporter | 코어 서버의 `127.0.0.1` | 없음 | Prometheus(같은 네트워크) |
| 8080 | 앱 API(이 저장소의 Manager) | 코어 서버의 `127.0.0.1` | 화면용 API는 없음, webhook은 token | 코어(같은 네트워크), 화면 |
| 8081 | 앱 화면(이 저장소의 Manager) | 코어 서버의 `127.0.0.1` | 없음 | 화면을 쓰는 사람 |
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
- 앱이 Telegram으로 알리면 `api.telegram.org`의 443

### 감시 대상 서버의 방화벽

exporter는 인증과 암호화 없이 열린다([#15](https://github.com/subinggrae/server-monitor/issues/15)). 그래서 감시 대상 서버의 방화벽에서 exporter 포트를 코어 서버에만 연다. 앱의 연결 시험도 코어 서버에서 exporter를 읽으므로 코어 서버 하나만 허용하면 된다. 예를 들어 `ufw`를 쓰는 Linux 서버에서 코어 서버가 `10.0.0.5`면 다음과 같다.

```sh
sudo ufw allow from 10.0.0.5 to any port 9100 proto tcp
```

이 규칙은 `ufw`가 켜져 있고 들어오는 연결의 기본 정책이 `deny`일 때만 다른 서버를 막는다. `sudo ufw status verbose`로 확인한다. Windows 서버는 Windows 방화벽의 인바운드 규칙에서 9182 포트의 원격 주소를 코어 서버로 제한한다.

### 비밀값

비밀값은 `infrastructure/secrets/`의 파일로 둔다. 파일마다 하는 일은 다음과 같다.

| 파일 | 하는 일 | 읽는 쪽 |
|---|---|---|
| `alertmanager_webhook_token` | 코어가 앱 webhook을 부를 때 붙이는 Bearer token | 코어(Alertmanager), 앱 |
| `manager_db_password` | Manager DB 비밀번호 | 이 저장소의 앱 |
| `manager_db_root_password` | 개발용 DB의 root 비밀번호 | 개발용 DB(`local-db` profile) |
| `telegram_bot_token` | Telegram Bot API token | 이 저장소의 앱 |
| `telegram_webhook_secret` | Telegram이 앱 webhook을 부를 때 확인하는 값 | 이 저장소의 앱 |

비밀값은 다음처럼 다룬다.

- 값은 `openssl rand -hex 32`처럼 길고 예측할 수 없게 만든다. `*.example` 파일의 값을 그대로 쓰지 않는다.
- 이 파일들은 Git에 올리지 않고(`.gitignore`), 설정 파일, 문서, 로그, 메신저에 옮기지 않는다.
- 값이 새어 나갔으면 새 값으로 바꾸고 그 값을 읽는 쪽을 모두 다시 띄운다. webhook token이면 코어와 앱을 함께 다시 띄운다.

### 밖에서 들어오는 연결

이 저장소의 앱은 Telegram 그룹의 `/register` 명령을 받으려고 Telegram webhook을 쓸 수 있다. 이 기능을 쓰면 Telegram이 앱에 닿을 수 있는 HTTPS 주소(`TELEGRAM_WEBHOOK_PUBLIC_URL`)가 필요하다. 이 주소는 코어 서버 앞의 리버스 프록시에서 `/api/integrations/telegram/webhook` 경로 하나만 넘기고, 나머지 경로는 막는다. 이 기능을 쓰지 않으면 밖에서 들어오는 연결은 없다.
