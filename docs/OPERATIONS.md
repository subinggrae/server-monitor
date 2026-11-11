# 운영 가이드

모니터링 코어와 OS exporter를 서버에 설치하고 운영하는 방법을 설명한다. Prometheus를 몰라도 이 문서의 순서대로 따라 하면 인터넷이 없는 서버에도 코어를 설치할 수 있다. 처음 설치한다면 [준비](#준비), [설치 전에 정할 값](#설치-전에-정할-값), [코어 설치](#코어-설치), [OS exporter 설치](#os-exporter-설치), [설치 확인](#설치-확인)을 차례로 읽는다. 코어에 붙일 관리자 서버를 만드는 방법은 [관리자 서버 만들기](ADMIN-SERVER.md)에 있다.

## 구성

이 시스템은 세 부분으로 되어 있다. 이 저장소는 코어와 node_exporter의 설치 묶음을 만들고, 관리자 서버는 코어를 쓰는 쪽이 만든다.

- **코어**: 감시할 주소가 응답하는지 확인하고, 서버 사용량을 모아 보관한다. 확인 실패가 이어지면 장애로 판단한다. Prometheus, Alertmanager, Blackbox Exporter 세 프로그램으로 되어 있다.
- **관리자 서버**: 무엇을 감시할지 코어에 알려 주고, 코어가 보낸 장애를 받아 담당자에게 알린다. 화면, API, DB를 갖춘 웹 서비스다.
- **OS exporter**: 감시 대상 서버의 CPU, Memory, 디스크 사용량을 코어가 읽을 수 있게 내보내는 작은 프로그램이다. Linux는 node_exporter, Windows는 windows_exporter를 쓴다. 웹 주소, TCP 포트, ping으로 살아 있는지만 보는 대상에는 설치하지 않는다.

코어와 관리자 서버는 한 서버에 각자 컨테이너로 띄우고, Docker 네트워크 `monitoring`으로만 통신한다. 서버 한 대에는 코어 하나와 관리자 서버 하나만 둔다. 둘을 다른 서버에 나눠 두는 구성은 지원하지 않는다([#15](https://github.com/subinggrae/server-monitor/issues/15)).

감시부터 알림까지는 다음 순서로 동작한다.

1. 코어가 관리자 서버에서 감시 대상 목록을 15~30초마다 받아 간다.
2. 코어가 대상마다 정한 주기(10~240초)로 주소를 확인하고, OS exporter에서 사용량을 모은다. 모은 기록은 기본 15일 보관한다.
3. 주소 확인 실패가 1분 넘게 이어지면 코어가 관리자 서버에 장애를 보낸다. 장애가 이어지면 5분마다 다시 보내고, 풀리면 복구를 보낸다.
4. 관리자 서버가 담당자에게 알린다. CPU·Memory·디스크 사용량이 임계값을 넘었는지는 코어가 아니라 관리자 서버가 판단한다.

## 준비

설치하기 전에 빌드 PC와 코어 서버에 다음을 갖춘다. 프로그램은 각자의 공식 문서대로 설치한다.

- **빌드 PC(Windows)**: 인터넷, Docker Desktop, Node.js 18 이상, Git, 이 저장소(`git clone https://github.com/subinggrae/server-monitor.git`)
- **코어 서버(x86-64 Linux)**:
  - Docker Engine과 Compose 2.24 이상. 코어의 compose 파일이 Compose 2.24부터 읽는 형식(`env_file`의 `required`)을 쓰기 때문이다. 이 저장소는 Docker Engine 29, Compose 5에서 확인했다.
  - 서버가 켜질 때 Docker도 켜지는 설정(`sudo systemctl enable --now docker`). 그래야 서버를 재시작해도 코어가 다시 뜬다.
  - `curl`(없으면 `wget`)과 CA 인증서 패키지(`ca-certificates`). 운영 명령이 코어가 정상인지 볼 때와 HTTPS 주소를 확인할 때 쓴다. Ubuntu, Debian, Amazon Linux 2023의 서버 이미지에는 기본으로 들어 있다.
  - `sudo`를 쓸 수 있는 계정
  - 감시할 주소와 감시 대상 서버의 exporter 포트(기본 9100, 9182)에 닿는 네트워크. 인터넷은 없어도 된다.

## 코어 서버 사양

코어가 쓰는 메모리와 CPU는 감시 대상 수에 따라 늘고, 디스크는 감시 대상 수와 보관 기간에 따라 늘어난다. 다음은 2026-11-10에 인터넷을 끊은 `docker:dind` 안에서 감시 대상 없이 코어만 띄워 잰 값이다.

| 항목 | 잰 값 |
|---|---|
| 서버로 옮길 묶음 폴더 | 약 96MB |
| 서버에 불러온 이미지 | 약 327MB(Prometheus 202MB, Alertmanager 75MB, Blackbox Exporter 50MB) |
| 메모리 | 약 52MiB(Prometheus 32MiB, Alertmanager 12MiB, Blackbox Exporter 7MiB) |
| CPU | 1% 미만 |

운영 규모의 메모리와 디스크는 아직 재지 않았다. 감시 대상을 등록한 뒤 다음 방법으로 재서 서버 크기를 정한다.

- **메모리**: 코어 서버에서 `docker stats --no-stream prometheus alertmanager blackbox-exporter`로 본다. 관리자 서버와 그 DB의 메모리도 더한다.
- **디스크**: `보관 기간(초) × 초당 저장하는 값의 수 × 1~2바이트`로 계산한다([Prometheus 저장소 문서](https://prometheus.io/docs/prometheus/latest/storage/#operational-aspects), 확인일 2026-11-09). 초당 저장하는 값의 수는 `http://127.0.0.1:9090`에서 `rate(prometheus_tsdb_head_samples_appended_total[1h])`를 조회해 얻는다. 디스크가 모자랄 것 같으면 [기록 보관 용량](#설치-전에-정할-값)을 정한다.

## 설치 전에 정할 값

코어를 설치할 때 꼭 정해야 하는 값은 관리자 서버 주소 하나다. 나머지는 기본값으로 시작해도 되지만, 다음에 해당하면 설치할 때 함께 바꾼다.

- **코어 서버에서 9090, 9093, 9115 포트를 이미 쓰고 있다**: 코어 포트를 비어 있는 포트로 바꾼다. 쓰고 있는 포트는 `ss -ltn`으로 볼 수 있다. 겹치면 `port is already allocated` 오류가 나고 코어가 뜨지 않는다.
- **기록을 둘 디스크가 넉넉하지 않다**: 기록 보관 기간을 줄이거나 보관 용량을 정한다. 나중에 줄이면 오래된 기록이 지워지므로 설치할 때 정해 두는 편이 낫다.

설정 값은 묶음 폴더의 `.env`에 적는다. 이 파일은 코어만 읽고, [코어 설치](#코어-설치)의 4단계에서 생기며 5단계에서 고친다. 적을 수 있는 값은 다음과 같다.

| 값 | `.env` 변수 | 기본값 | 허용 범위 |
|---|---|---|---|
| 관리자 서버 주소 | `MONITORING_APP_ADDRESS` | 없음(`install`이 물음) | `컨테이너 이름:포트` |
| 기록 보관 기간(일) | `CORE_RETENTION_DAYS` | 15 | 1~365 |
| 기록 보관 용량(GB) | `CORE_RETENTION_SIZE_GB` | 없음(제한하지 않음) | 1 이상 |
| 주소 확인 제한 시간(초) | `CORE_PROBE_TIMEOUT_SECONDS` | 5 | 1~9 |
| Prometheus 포트 | `CORE_PROMETHEUS_PORT` | 9090 | 1024~65535 |
| Alertmanager 포트 | `CORE_ALERTMANAGER_PORT` | 9093 | 1024~65535 |
| Blackbox Exporter 포트 | `CORE_BLACKBOX_PORT` | 9115 | 1024~65535 |
| token 폴더 | `CORE_SECRETS_DIR` | 설치 폴더의 `secrets/` | 폴더 경로 |

값마다 다음을 알아 둔다.

- **관리자 서버 주소**: 관리자 서버 컨테이너의 이름과 포트다. `manager-api:8080`처럼 적고 `http://`는 붙이지 않는다. 관리자 서버의 이름이나 포트가 바뀌면 이 값도 바꾼다.
- **기록 보관 기간과 용량**: 기간이 지나거나 용량이 차면 오래된 기록부터 지운다. 용량에는 디스크 크기가 아니라 기록이 쓸 크기를 적는다(1GB는 1024MB). 기록이 잠시 이 값을 넘을 수 있으므로 디스크의 80~85% 이하로 정한다.
- **주소 확인 제한 시간**: 이 시간 안에 응답이 없으면 확인 실패로 본다. ping, TCP, 웹 주소 확인에 모두 쓰고, 감시 항목마다 다르게 둘 수 없다. 최대 9초다.
- **포트**: 코어 서버에서 브라우저나 `curl`로 코어를 열 때 쓰는 포트다. 세 값은 서로 달라야 한다. 바꿔도 코어 서버 안에서만 열리고, 관리자 서버 설정은 그대로 둔다([바꾸지 않는 값](#바꾸지-않는-값)). 바꿨으면 이 문서의 9090 같은 포트를 바꾼 값으로 읽는다.
- **token 폴더**: 코어와 관리자 서버가 함께 읽는 token 파일의 폴더다. 보통은 바꾸지 않는다. 상대 경로를 적으면 묶음 폴더가 기준이다.

감시할 주소, 확인 주기, 알림 받을 곳, 임계값은 관리자 서버 화면에서 정한다. 바꿔도 코어를 다시 띄울 필요가 없고, 주소, 주기, exporter 포트는 30초 안에 코어에 반영된다.

## 코어 설치

빌드 PC에서 코어 묶음을 만들어 코어 서버로 옮기고, 코어 서버에서 설정한 뒤 띄운다. 명령은 그대로 복사하고, `<버전>`, `<계정>`처럼 꺾쇠로 감싼 부분만 자기 값으로 바꾼다. 설치는 다음 여섯 단계로 한다.

| 단계 | 하는 곳 |
|---|---|
| 1. 묶음 만들기 | 빌드 PC |
| 2. 묶음을 서버로 옮기기 | 빌드 PC |
| 3. 설치 폴더 만들기 | 코어 서버 |
| 4. token과 설정 파일 만들기 | 코어 서버 |
| 5. 설정 값 바꾸기 | 코어 서버 |
| 6. 코어 띄우기 | 코어 서버 |

### 1. 묶음 만들기

빌드 PC의 저장소 폴더에서 다음을 실행한다. PowerShell에서는 `.\core-dev.bat build`처럼 앞에 `.\`를 붙인다. 5~10분 걸린다.

```bat
core-dev.bat build
```

마지막에 `묶음을 만들었습니다`가 보이면 성공이다. `dist\`에 두 폴더가 생기는데, `monitoring-core-<버전>`은 코어 서버로 옮길 코어 묶음이고 `node-exporter-<버전>`은 [OS exporter 설치](#os-exporter-설치)에 쓸 묶음이다. 실패하면 다음을 확인한다.

- **`커밋하지 않은 변경이 있습니다`가 나온다**: 고친 파일을 커밋하거나, 고치지 않은 상태로 되돌린다.
- **`Docker에 연결하지 못했습니다`가 나온다**: Docker Desktop을 켠다.

### 2. 묶음을 서버로 옮기기

`dist\monitoring-core-<버전>` 폴더를 안의 `licenses`까지 통째로 코어 서버의 `/tmp/`로 옮긴다. `licenses`는 지우지 않는다([라이선스](#라이선스)). 예를 들어 저장소 폴더의 cmd에서 다음을 실행한다.

```bat
scp -r dist\monitoring-core-<버전> <계정>@<서버 주소>:/tmp/
```

### 3. 설치 폴더 만들기

빌드 PC의 cmd에서 코어 서버에 접속한다.

```bat
ssh <계정>@<서버 주소>
```

코어 서버에서 설치 폴더를 만들고, 묶음 폴더와 OS의 CA 인증서 파일을 그 안에 둔다. 다음 명령은 설치 폴더를 `/opt/monitoring`으로 둔 예다. 설치 폴더는 어디든 되고, 다른 폴더를 쓰면 명령의 `/opt/monitoring`만 바꾼다.

```sh
sudo mkdir -p /opt/monitoring/certs
sudo mv /tmp/monitoring-core-<버전> /opt/monitoring/
sudo cp -L /etc/ssl/certs/ca-certificates.crt /opt/monitoring/certs/ca-certificates.crt
sudo chmod 0644 /opt/monitoring/certs/ca-certificates.crt
```

CA 인증서 파일은 코어가 HTTPS 주소를 확인할 때 쓴다. 위 경로는 Ubuntu, Debian, Amazon Linux용이고, RHEL 계열은 `/etc/pki/tls/certs/ca-bundle.crt`를 복사한다. 이 복사는 처음 설치할 때 한 번만 한다.

묶음 폴더는 설치 폴더 바로 아래에 둔다. 그래야 운영 명령(`core-server.sh`)이 설치 폴더의 `secrets/`와 `certs/`를 찾고, 새 버전도 같은 token과 CA 인증서 파일을 쓴다.

### 4. token과 설정 파일 만들기

묶음 폴더로 가서 `install`을 실행한다.

```sh
cd /opt/monitoring/monitoring-core-<버전>
sudo sh core-server.sh install
```

`관리자 서버 주소(컨테이너 이름:포트, 예: manager-api:8080):`가 나오면 [관리자 서버 주소](#설치-전에-정할-값)를 넣는다. `install`은 설치 폴더의 `secrets/`에 token 파일을, 묶음 폴더에 `.env`를 만든다. token 값은 화면에 나오지 않는다. 이미 있는 파일은 그대로 두고, `.env`가 이미 있으면 주소를 묻지 않는다.

### 5. 설정 값 바꾸기

[설치 전에 정할 값](#설치-전에-정할-값)에서 기본값과 다르게 정한 값이 있으면 묶음 폴더의 `.env`를 편집기로 연다(예: `sudo vi .env`). 바꿀 줄 앞의 `#`을 지우고 값을 적는다. 다음은 기록을 30일 보관하고 Prometheus 포트를 19090으로 바꾸는 예다.

```text
CORE_RETENTION_DAYS=30
CORE_PROMETHEUS_PORT=19090
```

값은 다음처럼 적는다. 변수마다 설명은 `.env` 안의 주석에도 있다.

- 단위와 앞의 0 없이 숫자만 적는다. `30d`나 `030`은 틀린 값이다.
- 적지 않거나 비워 두면 기본값을 쓴다.
- 셸에 같은 이름의 환경 변수가 있으면 `.env`보다 그 값을 먼저 쓴다.

바꿀 값이 없으면 이 단계를 건너뛴다.

### 6. 코어 띄우기

묶음 폴더에서 `start`를 실행한다.

```sh
sudo sh core-server.sh start
```

`start`는 파일과 설정 값을 검사한 뒤 코어를 띄우고, 준비될 때까지 최대 3분 기다린다. 마지막 줄에 `코어가 떠 있습니다: 버전 …`이 보이면 성공이다. 5단계에서 바꾼 값은 `기본값과 다른 설정 값: …` 줄에서 확인한다.

실패하면 고칠 방법이 함께 나온다. 고친 뒤 `start`를 다시 실행하고, 그래도 안 되면 [문제 해결](#문제-해결)을 본다. 설정 값이 틀렸을 때는 떠 있던 코어를 건드리지 않는다.

`sudo sh core-server.sh status`로도 상태를 볼 수 있다. 세 줄(`prometheus`, `alertmanager`, `blackbox-exporter`)이 모두 `running, 준비됨`이면 정상이다. `docker ps`에는 `healthy`가 나오지 않으므로 `status`로 본다.

설치를 마친 서버의 폴더는 다음 모양이다. `rendered/`는 `start`가 띄울 때마다 `.env`로 다시 만드는 설정이고, 운영 명령은 이 일을 "설정을 펼친다"고 부른다. 직접 고쳐도 남지 않으므로 바꿀 값은 `.env`에 적는다.

```text
/opt/monitoring/                         설치 폴더(예)
├── secrets/alertmanager_webhook_token   코어와 관리자 서버가 함께 읽는 token
├── certs/ca-certificates.crt            OS에서 복사한 CA 인증서 파일
├── monitoring-core-<이전 버전>/          되돌릴 때 쓰도록 남겨 둔다
└── monitoring-core-<새 버전>/
    ├── .env                             설정 값
    ├── templates/                       설정 템플릿(고치지 않는다)
    └── rendered/                        펼친 설정(띄울 때마다 다시 만든다)
```

코어는 관리자 서버 없이도 뜨고, 감시 대상 없이 기다린다. 이어서 관리자 서버를 띄운다([관리자 서버 만들기의 코어와 연결하는 조건](ADMIN-SERVER.md#코어와-연결하는-조건)). 관리자 서버가 뜨면 15~30초 안에 코어가 감시 대상 목록을 받아 간다. 그다음 [OS exporter를 설치](#os-exporter-설치)하고 [설치 확인](#설치-확인)대로 동작을 본다.

코어는 실행 중에 인터넷에 나가지 않는다. 관리자 서버가 외부 메신저로 알린다면 관리자 서버만 그 서비스에 닿으면 된다.

## OS exporter 설치

CPU, Memory, 디스크 사용량을 볼 감시 대상 서버마다 OS exporter를 설치한다. 다음 순서로 한다.

1. 감시 대상 서버의 방화벽에서 exporter 포트를 코어 서버에만 연다([감시 대상 서버의 방화벽](#감시-대상-서버의-방화벽)).
2. 서버의 OS와 Docker 유무에 맞는 방법으로 OS exporter를 설치한다.
3. 코어 서버에서 [응답을 확인](#exporter-응답-확인)한다.
4. 관리자 서버에 그 서버를 등록하고 사용량 수집을 켠다.

설치 방법은 서버마다 다음 가운데 하나를 고른다.

| 서버 | OS exporter | 포트 | 설치 방법 |
|---|---|---|---|
| Linux, Docker 있음 | [node_exporter](https://github.com/prometheus/node_exporter) v1.12.1 | 9100 | [Docker가 있는 Linux 서버](#docker가-있는-linux-서버) |
| Linux, Docker 없음 | node_exporter v1.12.1 | 9100 | [Docker가 없는 Linux 서버](#docker가-없는-linux-서버) |
| Windows | [windows_exporter](https://github.com/prometheus-community/windows_exporter) | 9182 | [Windows 서버](#windows-서버) |

Linux 서버는 x86-64이고, Docker Compose v2나 systemd가 있어야 하며, 9100 포트가 비어 있어야 한다. Windows 서버는 windows_exporter를 설치할 권한과 비어 있는 9182 포트가 필요하다. windows_exporter는 공식 문서의 metric 이름을 기준으로 맞췄다.

### 감시 대상 서버의 방화벽

exporter는 인증과 암호화 없이 사용량을 내보낸다. 그래서 감시 대상 서버의 방화벽(AWS 보안 그룹, `ufw`, `firewalld`, Windows Defender 방화벽 등)에서 exporter 포트를 코어 서버에만 연다. 설정 방법은 그 방화벽의 문서를 따른다.

- **열 포트**: 관리자 서버에 그 서버의 exporter 포트로 등록한 포트다. 기본은 Linux 9100, Windows 9182다.
- **허용할 출발지**: 코어 서버 하나다. 관리자 서버의 연결 시험도 이것으로 된다. `0.0.0.0/0`이나 서브넷 전체로 열지 않는다.
- **방화벽이 둘일 때**: AWS 보안 그룹과 서버 안의 방화벽을 함께 쓰면 두 곳 모두에서 허용해야 닿는다.

다음 가운데 하나에 해당하면 방화벽만으로는 부족하므로 exporter에 TLS와 인증을 건다([#15](https://github.com/subinggrae/server-monitor/issues/15)).

- 고객사 보안 정책이 감시 트래픽의 암호화나 인증을 요구한다.
- exporter 트래픽이 인터넷을 지난다.
- 방화벽이나 보안 그룹으로 출발지를 제한할 수 없다.

### Docker가 있는 Linux 서버

코어 묶음을 만들 때 `dist/node-exporter-<버전>/` 폴더도 함께 생긴다. 이 폴더를 감시 대상 서버로 옮긴 뒤, 그 폴더에서 파일이 온전한지 확인하고 이미지를 불러와 띄운다.

```sh
sha256sum -c SHA256SUMS
docker load -i node-exporter-image.tar
docker compose up -d
```

이미지에는 node_exporter 실행 파일만 있고 셸은 없다. 컨테이너는 서버의 네트워크와 프로세스를 그대로 보고, 루트 파일 시스템은 읽기 전용으로 붙인다. 쓰기 권한과 privileged 권한은 주지 않는다.

이 이미지는 공개 저장소에 없다. 인터넷이 되는 서버에서도 묶음 폴더를 옮겨 쓴다. 묶음 대신 저장소의 [`infrastructure/node-exporter/`](../infrastructure/node-exporter/) 폴더(`compose.yml`, `Dockerfile`)를 복사해 그 폴더에서 `docker compose up -d --build`로 빌드해도 된다. 이때는 공식 이미지를 받으므로 인터넷이 필요하다.

### Docker가 없는 Linux 서버

같은 `node-exporter-<버전>` 폴더를 옮긴 뒤, 그 폴더에서 실행 파일을 설치하고 systemd 서비스로 켠다. 인터넷이 되는 서버라면 [공식 릴리스](https://github.com/prometheus/node_exporter/releases/tag/v1.12.1)에서 같은 압축 파일을 받아도 된다. 서비스 파일은 저장소의 [`infrastructure/node-exporter/node_exporter.service`](../infrastructure/node-exporter/node_exporter.service)와 같다.

```sh
sha256sum -c SHA256SUMS
tar xzf node_exporter-1.12.1.linux-amd64.tar.gz
sudo install -m 0755 node_exporter-1.12.1.linux-amd64/node_exporter /usr/local/bin/node_exporter
sudo useradd --system --no-create-home --shell /usr/sbin/nologin node_exporter
sudo cp node_exporter.service /etc/systemd/system/node_exporter.service
sudo systemctl daemon-reload
sudo systemctl enable --now node_exporter
```

### Windows 서버

[windows_exporter 릴리스](https://github.com/prometheus-community/windows_exporter/releases)의 MSI로 설치한다. 설치 방법과 옵션은 그 저장소의 README를 따른다.

### exporter 응답 확인

코어 서버에서 다음을 실행한다. `<대상 서버 주소>`와 `<exporter 포트>`는 예를 들어 `10.0.0.21`과 `9100`이다. 결과에 `# HELP`로 시작하는 줄이 보이면 exporter가 응답한다.

```sh
curl -s -m 5 http://<대상 서버 주소>:<exporter 포트>/metrics | head -n 1
```

방화벽이 제대로 막는지도 본다. 코어 서버가 아닌 같은 망의 다른 PC에서 같은 명령을 실행하면 연결에 실패해야 한다. 응답이 오면 방화벽 설정이 빠졌으므로 다시 확인한다.

## 설치 확인

코어, 관리자 서버, OS exporter를 모두 띄우고 감시 대상을 등록했으면 다음 순서로 감시부터 알림까지 동작하는지 확인한다. 명령은 코어 서버의 묶음 폴더에서 실행한다. 다른 PC에서 브라우저로 보려면 [SSH 터널](#포트)을 쓴다. 포트를 바꿨으면 명령의 9090을 `.env`의 `CORE_PROMETHEUS_PORT` 값으로 바꿔 쓴다.

1. 브라우저로 `http://127.0.0.1:9090/targets`를 열어 코어가 관리자 서버의 목록을 받았는지 본다. `manager-blackbox`에 주소가, `manager-os`에 서버가 보이고 상태가 `UP`이면 된다. 확인에 실패한 주소도 `UP`으로 보이므로 성공 여부는 2단계에서 본다. 목록이 비어 있으면 코어가 관리자 서버에 닿지 못하므로, 다음 명령으로 로그를 본다.

   ```sh
   docker compose logs prometheus | grep "Unable to refresh"
   ```

2. 주소 확인 결과를 본다. 대상마다 1(성공)이나 0(실패)이 나온다.

   ```sh
   curl -sG http://127.0.0.1:9090/api/v1/query --data-urlencode 'query=probe_success{job="manager-blackbox"}'
   ```

3. 서버 사용량을 본다. `server_id`에는 목록에 넣은 서버 id를 적는다. `resource_type`이 `CPU`, `MEMORY`, `STORAGE`인 값이 0~100으로 나오면 정상이다. 처음 수집한 뒤 30초쯤 지나야 나온다.

   ```sh
   curl -sG http://127.0.0.1:9090/api/v1/query --data-urlencode 'query=manager:resource_usage_percent{server_id="7"}'
   ```

4. 일부러 없는 주소(예: `http://does-not-exist.invalid/`)를 감시 대상에 넣고, 약 2분 뒤 관리자 서버가 `firing` 장애를 받는지 본다. 장애가 오지 않으면 다음 명령으로 코어가 관리자 서버에 보내지 못했는지 본다.

   ```sh
   docker compose logs alertmanager | grep -i "notify"
   ```

## 문제 해결

운영 명령은 대부분의 문제에서 고칠 방법을 함께 보여 준다. 다음 표는 그것으로 풀리지 않을 때와 운영 명령 없이 실행했을 때를 다룬다.

| 보이는 것 | 원인 | 해결 |
|---|---|---|
| `Docker에 연결하지 못했습니다` | Docker가 꺼져 있거나 이 사용자에게 `docker` 권한이 없음 | `sudo systemctl start docker`를 실행하거나, 사용자를 `docker` 그룹에 넣는다 |
| `서버가 켜질 때 Docker가 자동으로 켜지지 않습니다` | Docker 서비스가 자동 시작으로 설정되지 않음 | `sudo systemctl enable docker` |
| `설정 값이 올바르지 않습니다: CORE_…` | 값이 숫자가 아니거나, 단위나 앞의 0이 붙었거나, 허용 범위 밖임 | 안내대로 값을 고치거나 지운다(셸 환경 변수면 `unset <변수 이름>`) |
| `기록 보관 값을 줄이려면 터미널에서 직접 실행해 y를 입력해 주세요` | 보관 값을 줄였는데 스크립트 등에서 실행함 | 줄이려는 것이 맞으면 터미널에서 실행해 `y`를 넣고, 아니면 `.env`를 되돌린다 |
| `port is already allocated`, `address already in use` | 다른 프로그램이 그 포트를 쓰거나, `.env`의 세 포트 가운데 둘이 같음 | `.env`의 그 포트를 비어 있는 포트로 바꾸고 `start`를 다시 실행한다 |
| `CA 인증서 파일이 없습니다`, `CA 인증서 파일이 비어 있습니다` | 설치 폴더의 `certs/`에 파일을 복사하지 않음 | [3. 설치 폴더 만들기](#3-설치-폴더-만들기)대로 복사한다 |
| `CA 인증서 파일 자리에 폴더가 있습니다` | 파일을 복사하기 전에 Compose로 띄워 Docker가 빈 폴더를 만듦 | `docker compose down` 뒤 그 폴더를 지우고 파일을 복사한다 |
| `컨테이너가 CA 인증서 파일을 읽을 수 없습니다` | 복사한 파일의 권한이 0600 등임 | 파일 권한을 0644로 바꾼다 |
| `펼친 설정 파일 자리에 폴더가 있습니다` | 펼친 설정 없이 Compose로 띄워 Docker가 빈 폴더를 만듦 | `docker compose down` 뒤 안내의 `sudo rmdir` 명령으로 폴더를 지우고 다시 실행한다 |
| `펼친 설정 폴더가 심볼릭 링크입니다`, `펼친 설정 파일이 심볼릭 링크입니다` | `rendered/`나 그 안의 파일이 다른 곳을 가리킴 | 링크를 지우고 다시 실행한다 |
| `env file …/rendered/core.env not found` | `render` 없이 `docker compose up -d`를 실행함 | `sh core-server.sh render` 뒤 다시 띄우거나, `sh core-server.sh start`로 띄운다 |
| `curl과 wget이 없어 코어가 정상인지 확인할 수 없습니다` | 코어 서버에 `curl`과 `wget`이 모두 없음 | 코어 서버에 `curl`을 설치한다 |
| `정상 상태가 되지 않았습니다` | 3분 안에 준비되지 않은 프로그램이 있음 | 안내의 `docker logs <컨테이너>`로 로그를 본다 |
| `exec: "sh": executable file not found` | `docker exec`로 코어 컨테이너의 셸을 열려 함 | 셸 대신 로그와 `127.0.0.1`의 HTTP API로 본다 |
| `token 파일이 없습니다` | token을 만들지 않았거나 예전처럼 묶음 폴더의 `secrets/`에 둠 | 처음 설치면 `install`, 쓰던 코어가 있으면 [운영 명령이 없던 묶음에서 갱신](#운영-명령이-없던-묶음에서-갱신)대로 token을 옮긴다 |
| `설정 파일(.env)이 없습니다. 새 버전으로 갱신하는 중이면` | 갱신하면서 이전 폴더의 `.env`를 복사하지 않음 | [새 버전으로 갱신](#새-버전으로-갱신)대로 `.env`를 복사한다 |
| `alertmanager_webhook_token`이 폴더임 | `render` 뒤 token 파일을 지우고 Compose로 띄워 Docker가 빈 폴더를 만듦 | `docker compose down` 뒤 그 폴더를 지우고 token 파일을 만든다 |
| `No such image` | 코어 이미지를 불러오지 않음 | `docker load -i core-images.tar` |
| `sha256sum`이 `FAILED` | 옮기는 중에 파일이 망가짐 | 묶음을 다시 옮긴다 |
| 관리자 서버가 `network monitoring not found`로 뜨지 않음 | 관리자 서버를 코어보다 먼저 띄움 | 코어를 먼저 띄운 뒤 관리자 서버를 띄운다 |
| 장애 수신 API가 모두 `401` | 코어와 관리자 서버가 서로 다른 token 파일을 읽음 | 두 쪽이 같은 파일을 읽게 하고 둘 다 다시 띄운다 |

## 보안 설정

이 시스템은 대부분의 통신에 인증이 없어서, 포트를 어디에 여는지가 보안의 중심이다. 원칙은 두 가지다. 코어 서버의 포트는 밖에 열지 않고, 감시 대상 서버의 exporter 포트는 코어 서버에만 연다.

### 포트

이 시스템이 쓰는 포트는 다음과 같다. 코어의 세 포트는 기본값이며 `.env`에서 바꿀 수 있다([설치 전에 정할 값](#설치-전에-정할-값)).

| 포트 | 듣는 것 | 열리는 곳 | 인증 | 닿아야 하는 쪽 |
|---|---|---|---|---|
| 9090 | Prometheus | 코어 서버의 `127.0.0.1` | 없음 | 코어 서버의 운영자, 관리자 서버(같은 네트워크) |
| 9093 | Alertmanager | 코어 서버의 `127.0.0.1` | 없음 | 코어 서버의 운영자 |
| 9115 | Blackbox Exporter | 코어 서버의 `127.0.0.1` | 없음 | Prometheus(같은 네트워크) |
| 관리자 서버가 정함 | 관리자 서버 | `monitoring` 네트워크, 화면은 `127.0.0.1` 권장 | 장애 수신 API만 token | 코어(같은 네트워크), 화면을 쓰는 사람 |
| 9100 | node_exporter | 감시 대상 Linux 서버의 모든 주소 | 없음 | 코어 서버 |
| 9182 | windows_exporter | 감시 대상 Windows 서버의 모든 주소 | 없음 | 코어 서버 |

코어 포트는 코어 서버 안(`127.0.0.1`)에서만 열리고, `.env`로 포트 번호를 바꿔도 같다. `compose.yml`의 `127.0.0.1`도 바꾸지 않는다. Prometheus와 Alertmanager에는 인증이 없어서, 밖에 열면 누구나 설정과 감시 결과를 보고 바꿀 수 있다.

다른 PC에서 Prometheus나 관리자 서버 화면을 보려면 SSH 터널을 쓴다. 다음은 운영자 PC에서 실행해 코어 서버의 Prometheus를 PC의 `http://localhost:9090`으로 여는 예다.

```sh
ssh -L 9090:127.0.0.1:9090 <계정>@<코어 서버 주소>
```

여러 사람이 화면을 써야 해서 포트를 열어야 한다면, 인증을 거는 리버스 프록시(사내 SSO 등)를 앞에 두고 프록시만 연다. 이 구성은 이 저장소가 제공하지 않는다.

### 코어 서버의 방화벽

코어 서버로 들어오는 연결은 운영자의 SSH만 허용한다. 나가는 연결은 다음을 허용한다.

- 감시할 주소: 확인 방식에 따라 ICMP(ping), 대상 TCP 포트, HTTP·HTTPS 포트
- 감시 대상 서버의 exporter 포트: 기본 9100, 9182(관리자 서버에서 바꿨으면 그 포트)
- 관리자 서버가 외부 서비스(메신저 등)로 알리면 그 서비스

감시 대상 서버의 방화벽은 [OS exporter 설치의 감시 대상 서버의 방화벽](#감시-대상-서버의-방화벽)대로 설정한다.

### 비밀값

코어에 필요한 비밀값은 장애 수신 token(`alertmanager_webhook_token`) 하나다. 코어가 관리자 서버의 장애 수신 API를 부를 때 붙이는 Bearer token이고, 코어와 관리자 서버가 같은 파일을 읽는다. token은 다음처럼 다룬다.

- 값은 `openssl rand -hex 32`처럼 길고 예측할 수 없게 만든다. `*.example` 파일의 값을 그대로 쓰지 않는다.
- token 파일은 Git에 올리지 않고, 설정 파일, 문서, 로그, 메신저에 옮기지 않는다.
- 값이 새어 나갔으면 [token 바꾸기](#token-바꾸기)대로 바로 바꾼다.

## 서버에서 바꿀 수 없는 값

다음 값은 설치 담당자가 정하지 않는다. 바꿔야 할 때 어디서 바꾸는지만 알아 둔다.

### 빌드 전에 저장소에서 고치는 값

다음 기준은 코어 이미지에 들어 있어서 서버에서 바꿀 수 없다. 바꾸려면 저장소에서 고친 뒤 묶음을 다시 만들고, 모든 코어 서버를 [새 버전으로 갱신](#새-버전으로-갱신)한다. 저장소에서 띄웠다면 `core-dev.bat start`로 다시 띄운다.

- **장애 기준**: 주소 확인 실패가 1분 이어지면 장애로 보고, 장애가 이어지면 관리자 서버에 5분마다 다시 보낸다. 그래서 관리자 서버의 알림 다시 보내는 간격은 5분보다 짧게 둘 수 없다. 이 기준은 관리자 서버 화면에서 바꾸지 않는다([ARCHITECTURE.md의 지켜야 할 경계](ARCHITECTURE.md#지켜야-할-경계)).
- **주소 확인 방식**: IPv4로만 확인하고, HTTP는 2xx 응답만 성공으로 본다.

### 바꾸지 않는 값

다음 값은 코어와 관리자 서버가 서로 약속한 값이라 바꾸지 않는다.

- **코어 컨테이너 이름**: `prometheus`, `alertmanager`, `blackbox-exporter`. 관리자 서버의 컨테이너 이름을 이와 겹치게 짓지 않는다.
- **네트워크 이름**: `monitoring`
- **컨테이너 안 포트**: 9090, 9093, 9115. 관리자 서버와 코어가 `monitoring` 네트워크 안에서 부르는 포트이고, `.env`에서 코어 포트를 바꿔도 그대로다.
- **관리자 서버 API 경로 세 개**: 관리자 서버 안의 경로가 다르면 앞단(리버스 프록시 등)에서 이 경로를 받아 넘긴다.

## 운영 작업

명령은 지금 띄운 버전의 묶음 폴더에서 `sudo`를 붙여 실행한다. 표와 문장에서는 `sudo`를 생략했다. 저장소에서 띄웠다면 `core-dev.bat`을 쓰거나 `docker compose`에 `-f infrastructure/compose.yml`을 붙인다.

자주 하는 일은 다음과 같다.

| 하려는 일 | 방법 |
|---|---|
| 상태 보기 | `sh core-server.sh status` |
| 설정 값 바꾸기 | `.env`를 고친 뒤 `sh core-server.sh start` |
| 멈추기 | `docker compose down` |
| 다시 띄우기 | `sh core-server.sh start` |
| 로그 보기 | `docker logs -f prometheus`(다른 프로그램은 컨테이너 이름만 바꿈) |
| 기록까지 지우기 | `docker compose down -v`(되돌릴 수 없음) |

`status`는 떠 있는 버전과 커밋, 세 컨테이너의 상태와 준비 여부, 기본값과 다른 설정 값을 보여 준다. 설정 값을 바꿔도 쌓은 기록은 이어지지만, 보관 기간이나 용량을 줄이면 오래된 기록이 지워진다([기록 보관 값 줄이기](#기록-보관-값-줄이기)). `docker compose down`으로 멈추면 기록은 남고, 서버를 재시작해도 코어가 다시 뜨지 않는다.

Docker 자동 시작이 켜져 있으면 서버를 재시작해도 코어가 다시 뜬다. 켜진 뒤 `status`로 세 줄이 `running, 준비됨`인지 본다.

코어 컨테이너에는 셸이 없어 `docker exec`로 안에서 명령을 실행할 수 없다. 대신 로그와 `127.0.0.1`의 HTTP API(예: `curl -s http://127.0.0.1:9090/api/v1/status/flags`)로 본다.

### 기록 보관 값 줄이기

보관 기간이나 용량을 줄이면 오래된 기록이 지워지고, 되돌릴 수 없다. 제한이 없던 용량을 처음 정할 때도 같다. 그래서 `start`가 `기록 보관 값을 줄입니다`를 보여 주고 계속할지 묻는다. `y`를 넣어야 띄운다.

`docker compose down`으로 멈춘 뒤에는 묻지 않으므로, 띄우기 전에 `.env` 값을 직접 확인한다.

### 새 버전으로 갱신

새 묶음 폴더를 이전 묶음 폴더 옆에 옮긴 뒤, 새 폴더에서 다음을 실행한다. token과 CA 인증서 파일은 설치 폴더에 있으므로 옮기지 않는다. 쌓은 기록과 장애 상태는 이어진다. 이전 `.env`에 없던 값은 기본값을 쓴다.

```sh
sudo cp ../monitoring-core-<이전 버전>/.env .
sudo sh core-server.sh start
```

이전 폴더는 되돌릴 때 쓰도록 남긴다. 운영 명령은 오래된 버전을 지우지 않으므로, 띄운 버전과 직전 버전만 남기고 더 오래된 묶음 폴더와 이미지는 다음처럼 지운다. 지금 띄운 버전은 `status`로, 남은 버전은 `ls <설치 폴더>`와 `docker image ls monitoring-core-prometheus`로 본다.

```sh
sudo rm -rf <설치 폴더>/monitoring-core-<오래된 버전>
sudo docker image rm monitoring-core-prometheus:<오래된 버전> monitoring-core-alertmanager:<오래된 버전> monitoring-core-blackbox-exporter:<오래된 버전>
```

### 이전 버전으로 되돌리기

이전 묶음 폴더에서 `sh core-server.sh start`를 실행한다. 확인 없이 바로 이전 버전으로 바뀌고, 쌓은 기록, 장애 상태, token, CA 인증서 파일이 이어진다. 이전 폴더의 `.env`를 쓰므로, 지금 폴더에서 바꾼 값을 이어 쓰려면 이전 폴더의 `.env`에도 같게 적는다. `templates/`가 없는 예전 묶음으로 되돌리면 그 묶음의 운영 명령이 이전 버전으로 바꿀지 물으므로 `y`를 넣는다.

서버에서 코어 값을 바꿀 수 없던 예전 버전으로 되돌리면, 그 버전은 기록 보관 15일, 용량 제한 없음, 제한 시간 5초, 포트 9090·9093·9115로 돈다. 보관 기간을 15일보다 늘려 두었다면 15일이 지난 기록이 확인 없이 지워지고, 포트를 바꿔 두었다면 다른 프로그램과 겹칠 수 있다. 그래서 이런 되돌리기는 꼭 필요할 때만 한다.

### 운영 명령이 없던 묶음에서 갱신

예전 묶음은 token을 묶음 폴더 안의 `secrets/`에 둘 수 있었다. 이런 서버에서 token을 옮기지 않고 `install`을 실행하면 새 token이 생겨 관리자 서버와 어긋난다. 그래서 다음 순서로 갱신한다.

1. 설치 폴더(예: `/opt/monitoring`)를 정하고 새 묶음 폴더를 그 안에 둔다.
2. 예전 묶음 폴더의 token 파일을 설치 폴더의 `secrets/`로 옮긴다(폴더 0700, 파일 0644). 이전 `.env`에 `CORE_SECRETS_DIR`를 적어 두었으면 그 경로를 그대로 쓰므로 옮기지 않는다.
3. 관리자 서버가 읽는 token 경로를 새 위치로 바꾼다.
4. 이전 폴더의 `.env`를 새 폴더에 복사하고 `sh core-server.sh start`를 실행한 뒤, 관리자 서버를 다시 띄운다.

### token 바꾸기

묶음 폴더에서 다음을 실행한 뒤 관리자 서버도 다시 띄운다. 첫 명령은 새 token을 만들고(값은 화면에 나오지 않는다), 두 번째 명령은 Alertmanager가 새 token을 읽게 한다. `.env`의 `CORE_SECRETS_DIR`에 다른 폴더를 적었다면 `../secrets` 대신 그 폴더를 쓴다.

```sh
sudo sh -c 'umask 022; f=../secrets/alertmanager_webhook_token; od -An -tx1 -N32 /dev/urandom | tr -d " \n" > "$f.new" && echo >> "$f.new" && mv -f "$f.new" "$f"'
sudo docker compose up -d --force-recreate alertmanager
```

관리자 서버는 시작할 때 token을 읽으므로, 다시 띄울 때까지 코어가 보낸 장애를 거부한다.

### CA 인증서 다시 복사

OS의 CA 인증서를 갱신하거나 사내 인증 기관을 OS에 등록했으면, [3. 설치 폴더 만들기](#3-설치-폴더-만들기)대로 설치 폴더의 `certs/`에 다시 복사하고 `sh core-server.sh start`를 실행한다. 이때 세 컨테이너를 다시 만들어 잠깐 감시가 멈춘다.

### node_exporter 갱신과 멈추기

방법은 설치 방식마다 다르다. 멈춘 서버는 관리자 서버에서도 수집을 끈다. 끄지 않으면 수집 실패가 계속 기록된다. 설치 방식별 명령은 다음과 같다.

| 설치 방식 | 갱신 | 멈추기 |
|---|---|---|
| Docker | 새 묶음 폴더에서 `docker load -i node-exporter-image.tar` 뒤 `docker compose up -d` | 묶음 폴더에서 `docker compose down` |
| systemd | 새 실행 파일을 `sudo install`로 같은 경로에 덮어쓴 뒤 `sudo systemctl restart node_exporter` | `sudo systemctl disable --now node_exporter` |

## 다른 실행 방법

보통은 [코어 설치](#코어-설치)대로 운영 명령을 쓴다. 다음은 운영 명령 없이 Compose로 띄우는 방법과, 저장소에서 바로 띄우는 방법이다.

### 운영 명령 없이 실행

[코어 설치](#코어-설치)의 4~6단계 대신 묶음 폴더에서 다음을 실행한다. Compose로 띄우기 전에 반드시 `render`로 설정을 펼친다. 3단계에서 CA 인증서 파일을 복사했으면 `../certs`를 다루는 세 줄은 건너뛴다.

```sh
install -d -m 0700 ../secrets
openssl rand -hex 32 > ../secrets/alertmanager_webhook_token
chmod 0644 ../secrets/alertmanager_webhook_token
install -d -m 0755 ../certs
cp -L /etc/ssl/certs/ca-certificates.crt ../certs/ca-certificates.crt   # RHEL 계열은 /etc/pki/tls/certs/ca-bundle.crt
chmod 0644 ../certs/ca-certificates.crt
cp .env.example .env              # MONITORING_APP_ADDRESS를 관리자 서버의 컨테이너 이름:포트로 고친다
docker load -i core-images.tar
sh core-server.sh render          # 파일이 온전한지 보고, 값과 token, CA 인증서 파일을 검사해 rendered/에 펼친다
docker compose up -d
curl -fsS http://127.0.0.1:9090/-/ready   # Alertmanager는 9093/-/ready, Blackbox Exporter는 9115/-/healthy
```

`render`는 `start`처럼 값과 파일을 검사하지만 컨테이너는 건드리지 않는다. 다만 서버가 재시작되면 떠 있던 코어도 새 설정을 읽으므로, 보관 기간이나 용량을 줄였으면 `render`도 계속할지 묻는다.

이 방식에서 주의할 점은 다음과 같다.

- `.env`를 바꾸거나 CA 인증서 파일을 다시 복사했으면 `render`와 `docker compose up -d`를 다시 실행한다.
- `render` 없이 `docker compose up -d`를 실행하면 Compose가 `env file …/rendered/core.env not found`로 멈추고 아무것도 만들지 않는다.
- `render`와 `docker compose up -d` 사이에 token 파일을 지우지 않는다. 지우면 Alertmanager가 token 없이 떠서 관리자 서버가 장애를 거부한다.

### 저장소에서 실행

인터넷이 되는 서버나 개발 PC에서는 저장소 폴더에서 바로 빌드해 띄운다. 먼저 설정 파일과 token 파일을 만든다.

```sh
cp infrastructure/.env.example infrastructure/.env
openssl rand -hex 32 > infrastructure/secrets/alertmanager_webhook_token
chmod 0644 infrastructure/secrets/alertmanager_webhook_token
```

Windows에서는 `core-dev.bat start`로 띄우고 `core-dev.bat stop`으로 멈춘다. `start`가 설정 펼치기와 CA 인증서 파일 준비까지 한다.

다른 OS에서는 같은 일을 하는 다음 명령을 쓴다. 앞의 두 줄은 그 PC의 CA 인증서 파일을 복사하는 명령이라 처음 한 번만 실행한다.

```sh
mkdir -p infrastructure/certs
cp -L /etc/ssl/certs/ca-certificates.crt infrastructure/certs/   # RHEL 계열은 /etc/pki/tls/certs/ca-bundle.crt, macOS는 /etc/ssl/cert.pem
sh infrastructure/core-server.sh render
docker compose -f infrastructure/compose.yml up -d --build
docker compose -f infrastructure/compose.yml ps
```

## 라이선스

묶음 폴더의 `licenses/`는 지우지 않고 묶음과 함께 옮긴다. 코어와 node_exporter 묶음을 다른 회사의 프로젝트에 줄 때 라이선스 조건을 지키는 데 필요하기 때문이다. `licenses/`에는 묶음에 든 프로그램의 라이선스 전문과 고지가 있고, 무엇이 들었는지는 `licenses/README.txt`에 있다. 코어 묶음에는 Prometheus, Alertmanager, Blackbox Exporter가, node_exporter 묶음에는 node_exporter가 들어 있다. 이미지에는 그 프로그램의 실행 파일만 있고 셸과 다른 프로그램은 없다. Blackbox Exporter가 쓰는 CA 인증서는 코어 서버 OS의 파일이라 묶음에 들어가지 않는다. 라이선스 기록은 [LICENSES.md](LICENSES.md)에 있다.
