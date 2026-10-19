# Monitoring Manager

Prometheus 기반 감시 설정을 화면에서 관리하는 도구다. 사내 운영 담당자가 서버와 서비스의 가용성 Check, 서버 자원 수집(OS exporter), Telegram 알림을 등록하고, 지금 문제인 Check를 확인한다.

서버의 CPU, Memory, Storage는 대상 서버에 설치한 OS exporter에서 수집한다. 설치 방법은 [OS exporter 설치](#os-exporter-설치)에 있다.

시스템은 모니터링 코어(Prometheus, Blackbox Exporter, Alertmanager)와 앱(이 저장소의 Manager API, 화면, DB)으로 나뉜다. 다른 앱도 [통합 계약](docs/INTEGRATION.md)대로 코어에 붙을 수 있다.

## 시작하기

[개발 가이드의 준비](docs/DEVELOPMENT.md#준비)대로 `.env`, `infrastructure/.env`, `infrastructure/secrets/`의 비밀값 파일을 만든다. 그다음 저장소 폴더에서 코어를 먼저 띄우고 앱을 띄운 뒤, 브라우저로 http://localhost:8081 을 연다. 두 번째 명령은 개발용 MariaDB까지 함께 띄운다.

```sh
docker compose -f infrastructure/compose.yml up -d
docker compose --profile local-db up -d --build
```

준비 과정과 전체 명령은 [개발 가이드](docs/DEVELOPMENT.md)에 있다.

## OS exporter 설치

감시 대상 서버마다 OS exporter를 설치한다. Linux는 [node_exporter](https://github.com/prometheus/node_exporter)(기본 포트 9100, v1.12.1에서 확인), Windows는 [windows_exporter](https://github.com/prometheus-community/windows_exporter)(기본 포트 9182, 공식 문서의 metric 이름 기준)다. 설치한 뒤 화면의 서버 상세에서 수집을 켜고 연결 시험을 누른다.

exporter는 인증 없이 열린다. 그래서 대상 서버의 방화벽에서 그 포트를 코어(Prometheus)와 앱이 있는 호스트에만 연다. 예를 들어 `ufw`를 쓰는 Linux 서버에서 코어 호스트가 `10.0.0.5`면 다음과 같다.

```sh
sudo ufw allow from 10.0.0.5 to any port 9100 proto tcp
```

**Docker가 있는 Linux 서버**: 저장소의 [`infrastructure/node-exporter/compose.yml`](infrastructure/node-exporter/compose.yml)을 서버에 복사하고 그 폴더에서 띄운다. 서버의 네트워크와 프로세스를 그대로 보고, 루트 파일 시스템은 읽기 전용으로 붙인다.

```sh
docker compose -f compose.yml up -d
```

**Docker가 없는 Linux 서버**: 공식 릴리스에서 바이너리를 받고, 같은 릴리스의 `sha256sums.txt`로 파일이 바뀌지 않았는지 확인한 뒤 설치한다. 다음은 x86-64 서버의 예다.

```sh
VERSION=1.12.1
curl -LO https://github.com/prometheus/node_exporter/releases/download/v$VERSION/node_exporter-$VERSION.linux-amd64.tar.gz
curl -LO https://github.com/prometheus/node_exporter/releases/download/v$VERSION/sha256sums.txt
sha256sum -c --ignore-missing sha256sums.txt
tar xzf node_exporter-$VERSION.linux-amd64.tar.gz
sudo install -m 0755 node_exporter-$VERSION.linux-amd64/node_exporter /usr/local/bin/node_exporter
sudo useradd --system --no-create-home --shell /usr/sbin/nologin node_exporter
```

`sha256sum`이 `OK`를 보이지 않으면 설치하지 않는다. 그다음 `/etc/systemd/system/node_exporter.service`를 만들고 `sudo systemctl enable --now node_exporter`로 켠다.

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

**Windows 서버**: [windows_exporter 릴리스](https://github.com/prometheus-community/windows_exporter/releases)의 MSI로 설치한다. 설치 방법과 옵션은 그 저장소의 README를 따른다.

## 문서

알고 싶은 것에 따라 다음 문서를 읽는다.

| 알고 싶은 것 | 문서 |
|---|---|
| 시스템 구조와 코드 위치 | [아키텍처](docs/ARCHITECTURE.md) |
| 개발 환경, 명령, 코드 작성 기준, 코드 안의 글, 커밋·리뷰와 반영 절차 | [개발 가이드](docs/DEVELOPMENT.md) |
| 테스트 종류와 작성 기준 | [테스트](docs/TESTING.md) |
| 제품의 대상, 사용 환경, 말투 | [제품](docs/PRODUCT.md) |
| 이 제품의 시각 체계 | [디자인](docs/design/DESIGN.md) |
| 화면 규칙, 점검, 디자인 방향을 정하는 방법 | [디자인 규칙](docs/design/RULES.md), [디자인 점검](docs/design/REVIEW.md), [디자인 방향 정하기](docs/design/DIRECTION.md) |
| AI와 함께 작업하는 방식 | [AI 작업 지침](AGENTS.md) |
| 문서 작성 규칙과 한국어 규칙 | [문서 작성 가이드](docs/WRITING.md) |
| 정한 용어와 표기 | [용어](docs/TERMS.md) |
| 바깥 코드와 에셋의 라이선스 | [라이선스](docs/LICENSES.md) |
| 그림 작성 규칙 | [그림 작성 가이드](docs/DIAGRAMS.md) |
| 기능별 요구사항 | `docs/requirements/` |
| 주요 결정과 이유 | `docs/decisions/` |
