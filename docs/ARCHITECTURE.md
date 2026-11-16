# 아키텍처

이 시스템이 어떤 부분으로 나뉘고, 각 부분이 어디에 있으며, 어떤 경계를 지켜야 하는지 설명한다. 처음 코드를 고치는 사람이 "이 기능은 어디를 고쳐야 하는가"를 빨리 찾게 하는 것이 목적이다.

## 한눈에 보기

모니터링 코어는 Prometheus, Blackbox Exporter, Alertmanager로 이루어진다. Prometheus는 관리자 서버에서 HTTP SD로 감시 대상 목록을 받아, Blackbox Exporter로 서버와 서비스가 살아 있는지 확인하고, 서버에 설치한 OS exporter(node_exporter, windows_exporter)에서 CPU, Memory, Storage를 수집한다. Alert 규칙이 장애를 판단하면 Alertmanager가 관리자 서버의 webhook으로 보낸다.

관리자 서버는 이 저장소에 없다. 사용자가 감시할 대상과 알림 받을 곳을 정하는 화면, 그 설정을 담는 DB, 담당자에게 알림을 보내는 일은 관리자 서버가 맡는다. 코어와 관리자 서버 사이의 약속은 [통합 계약](INTEGRATION.md)이고, 결정과 이유는 [ADR 0007](decisions/0007-core-app-contract.md)에 있다.

![관리자 서버가 감시 대상 목록을 주면 코어가 감시 대상을 확인하고 수집해 장애를 관리자 서버에 보낸다](assets/architecture-overview.svg)

## 실행 단위

따로 실행되는 단위와 그 기술, 통신 방식은 다음과 같다. 세 단위는 `infrastructure/compose.yml`로 함께 띄우고, 관리자 서버와는 같은 서버의 Docker 네트워크 `monitoring`으로 통신한다. 호스트에 여는 포트는 모두 `127.0.0.1`에만 묶는다.

| 단위 | 역할 | 기술 | 통신 |
|---|---|---|---|
| `prometheus` | HTTP SD로 대상을 받아 수집하고 Alert 규칙을 평가 | Prometheus | 관리자 서버의 HTTP SD 호출, exporter 수집, Alertmanager로 Alert 전송, 관리자 서버의 조회(HTTP API) |
| `alertmanager` | Alert를 묶어 관리자 서버 webhook 하나로 보냄 | Alertmanager | Bearer token을 붙인 webhook |
| `blackbox-exporter` | ICMP, TCP, HTTP, HTTPS 가용성 확인 | Blackbox Exporter | Prometheus가 probe 요청 |

표에 다 적지 못한 내용은 다음과 같다.

- 코어 이미지는 공식 이미지에서 실행 파일만 빈 이미지(`scratch`)로 옮겨 빌드하고, Prometheus 이미지에는 Alert 규칙도 넣는다. 셸, BusyBox, `promtool`, `amtool`, CA 묶음, 시간대 자료는 없다. 개발과 내부망 배포가 같은 이미지를 쓴다([ADR 0009](decisions/0009-core-config-in-image.md), [ADR 0012](decisions/0012-core-render-outside-image.md)). node_exporter 이미지도 같은 방법으로 빌드한다. 내부망 서버에는 Windows 빌드 PC에서 `core-dev.bat build`로 만든 묶음을 옮기고, 묶음 안의 `core-server.sh`로 설치하고 띄운다.
- 코어가 부르는 관리자 서버 주소는 `infrastructure/.env`의 `MONITORING_APP_ADDRESS` 하나다. 이미지에 셸이 없으므로, 띄우기 전에 운영 명령(`core-server.sh`의 `render`, `start`)이 컨테이너 밖에서 설정 템플릿에 펼쳐 `rendered/`에 쓰고, `compose.yml`이 펼친 설정을 읽기 전용으로 붙인다. 묶음에서는 템플릿이 묶음 폴더의 `templates/`에 있다.
- 코어 서버에서 바꾸는 코어 값(기록 보관 기간·용량, 주소 확인 제한 시간, 호스트 포트 세 개)도 `.env`에서 받는다. 운영 명령이 `core-settings.sh`로 여섯 값을 모두 검사하고, 앞의 세 값을 Prometheus 설정 템플릿의 보관 항목(`storage.tsdb.retention`)과 Blackbox 설정 템플릿에 펼친다. 호스트 포트는 `compose.yml`의 `ports`가 받는다.
- Blackbox Exporter의 HTTPS 확인은 코어 서버 OS의 CA 인증서 파일을 쓴다. 운영자가 운영 가이드대로 설치 폴더의 `certs/`에 복사하고, `compose.yml`이 Blackbox Exporter에만 붙인다. 운영 명령은 파일이 있는지만 확인한다. 정상 확인은 컨테이너 healthcheck 대신 운영 명령이 코어 서버에서 `127.0.0.1`의 호스트 포트로 준비 확인 주소를 부른다.
- OS exporter는 코어가 띄우지 않는다. 감시 대상 서버에 사람이 설치하고, Prometheus가 HTTP SD로 받은 주소를 직접 수집한다. Linux 서버용 배포 묶음은 `infrastructure/node-exporter/compose.yml`이고, 설치 안내는 [운영 가이드의 OS exporter 설치](OPERATIONS.md#os-exporter-설치)에 있다.

외부 시스템은 다음과 같다.

- **관리자 서버**: 감시 대상 목록 API 두 개와 장애 수신 webhook을 제공하고, Prometheus HTTP API로 결과를 조회한다. 만드는 방법은 [관리자 서버 만들기](ADMIN-SERVER.md)에 있다.
- **감시 대상**: 관리자 서버에 등록한 서버와 서비스다. Blackbox Exporter가 살아 있는지 확인하고, 서버에 설치한 OS exporter를 Prometheus가 수집한다.
- **Grafana**: 코어에 없다. 운영자가 따로 두면 Prometheus의 기록을 읽어 추세를 본다.

## API와 데이터 모델

API 명세와 데이터 모델의 원본 위치는 다음과 같다.

- 코어와 관리자 서버 사이의 계약: [INTEGRATION.md](INTEGRATION.md)
- 코어가 쌓는 기록: Prometheus의 time series(`prometheus-data` volume)와 Alertmanager의 상태(`alertmanager-data` volume). 코어에는 DB가 없다.
- 관리자 서버 DB의 참고용 DDL: `docs/reference-schema/`. 관리자 서버가 자기 용어사전에 맞게 고쳐 쓴다.

## 코드 지도

디렉터리와 주요 파일의 책임은 다음과 같다.

| 위치 | 책임 |
|---|---|
| `infrastructure/` | 모니터링 코어(`compose.yml`, 구성 요소마다 `Dockerfile`), Prometheus 설정 템플릿과 규칙(규칙 테스트는 `prometheus/tests/`), Alertmanager, Blackbox 설정, 묶음에 싣는 라이선스 파일(`licenses/`), node_exporter 배포 묶음(`node-exporter/`), 비밀값 예시 파일 |
| `infrastructure/core-server.sh` | 묶음에 들어가 코어 서버(Linux)에서 설치 준비, 설정 펼치기, 띄우기, 상태 보기(시험은 `infrastructure/tests/`) |
| `infrastructure/core-settings.sh` | 서버에서 바꾸는 코어 값의 기본값, 허용 범위, 검사 |
| `scripts/build-core-bundle.mjs` | 코어 이미지 빌드와 검사, 내부망 서버로 옮길 코어·node_exporter 묶음 |
| `core-dev.bat` | Windows 빌드 PC에서 묶음 만들기, 설정 검사, 개발용 코어 띄우기·멈추기 |
| `scripts/check-docs.mjs` | 문서 구조, 링크, 표, 그림 검사 |
| `docs/reference-schema/` | 관리자 서버가 참고하는 기본 테이블(`base-tables.sql`), 리소스 임계값(`resource-thresholds.sql`), 알림 메시지 템플릿(`alert-message-templates.sql`) DDL(자동 검사 없음) |

## 지켜야 할 경계

코드를 고칠 때 지켜야 할 경계는 다음과 같다.

- 사용자가 관리자 서버에서 바꾼 설정 때문에 `infrastructure/`의 설정 파일과 Alert 규칙을 만들거나 고치지 않는다. Check 대상과 수집 대상 서버는 HTTP SD로 받고, 알림 받을 곳은 관리자 서버의 DB가 정한다. 코어는 관리자 서버의 설정을 파일로 받지 않는다.
- 알림 받을 곳(메신저 방 id, 전화번호 등)과 알림 수단의 비밀값은 Alert 규칙, Alertmanager 설정, Prometheus label에 넣지 않는다. 코어의 비밀값은 webhook token 하나다.
- 서버가 살아 있는지는 감시 항목(Blackbox)이 판단하고 알린다. OS exporter 수집 상태(`up{job="manager-os"}`)에는 Alert 규칙을 두지 않는다. 수집 실패를 서버 장애로 알리지 않는다.
- 서버 자원 값은 `infrastructure/prometheus/rules/server-resources.yml`의 공통 recording rule(`manager:resource_usage_percent`, `manager:resource_used_bytes`, `manager:resource_size_bytes`, label `server_id`, `resource_type`, `resource_key`)로 관리자 서버에 내준다. OS별 계산은 그 파일에만 둔다. 결정과 이유는 [ADR 0005](decisions/0005-common-resource-recording-rules.md)에 있다.
- 서버 리소스 임계값은 관리자 서버가 `manager:resource_usage_percent`를 읽어 판단하고 직접 알린다. 임계값은 사용자 설정이므로 `infrastructure/`의 Alert 규칙에 리소스 임계값 규칙을 두지 않는다. 판단 규칙과 참고용 DDL은 [INTEGRATION.md의 리소스 임계값 판단](INTEGRATION.md#리소스-임계값-판단)에, 결정과 이유는 [ADR 0008](decisions/0008-resource-threshold-in-app.md)에 있다.
- `manager-os` job은 keep 규칙으로 위 recording rule이 읽는 series만 저장한다. 다른 지표가 필요해지면 keep 목록과 규칙을 함께 고친다. 결정과 이유는 [ADR 0004](decisions/0004-os-exporter-collection.md)에 있다.
- Check 대상 하나가 HTTP SD entry 하나다. 대상의 id와 SD label은 Prometheus time series의 이름표이므로, 바꾸면 쌓아 온 time series가 끊긴다.
- 감시 항목의 Alert 규칙과 Alertmanager `group_by`는 SD label 가운데 id(`check_id`, `check_target_id`)만 쓴다. 사용자가 바꾸는 이름, 환경, 확인 방식, 주소가 Alert label에 있으면 값을 바꿀 때 장애가 끊기기 때문이다. 알림의 감시 항목 이름, 환경, 주소는 관리자 서버가 두 id로 찾은 자기 DB 값으로 채운다. 결정과 이유는 [ADR 0010](decisions/0010-alert-labels-by-check-id.md)에 있다.
- Check와 OS exporter 수집 서버의 수집 주기(10~240초)는 관리자 서버의 DB에 두고, SD entry의 `__scrape_interval__` label로 Prometheus에 전한다. `__`로 시작하는 label은 relabel 뒤에 지워져 time series 이름표가 되지 않는다. 그래서 `manager-blackbox`, `manager-os` job은 `prometheus.yml`의 `scrape_interval`을 쓰지 않는다. `prometheus.yml`의 `scrape_timeout`은 수집 주기 하한(10초)보다 길게 두지 않는다. timeout이 주기보다 긴 대상을 Prometheus가 수집하지 않기 때문이다. 결정과 이유는 [ADR 0002](decisions/0002-scrape-interval-via-http-sd.md)에 있다.
- 장애가 이어지는 동안 알림을 다시 보내는 간격(5~1440분)은 Check마다 관리자 서버의 DB에 둔다. Alertmanager는 이어지는 Alert를 `group_interval`(5분)마다 webhook으로 다시 보내고, 관리자 서버가 마지막으로 보낸 뒤 그 Check의 간격이 지났는지 보고 보낸다. 그래서 `alertmanager.yml`의 `repeat_interval`(4분)은 `group_interval`보다 짧게 두고, 알림 다시 보내는 간격의 하한은 `group_interval`보다 짧게 두지 않는다. `repeat_interval`을 `group_interval`과 같게 두면 Alertmanager가 한 번씩 건너뛰어 10분마다 보낸다. 결정과 이유는 [ADR 0001](decisions/0001-alert-repeat-in-manager.md)에 있다.
- 감시 항목의 장애 판단 규칙(실패가 얼마나 이어지면 장애인지 등)은 코어의 Alert 규칙(`infrastructure/prometheus/rules/monitoring-checks.yml`)에 두고, 바꾸려면 코어를 다시 빌드한다. 사용자가 관리자 서버에서 정하는 것은 위의 수집 주기와 알림 다시 보내는 간격뿐이다(사람 확인: 2026-10-29, [#5](https://github.com/subinggrae/server-monitor/issues/5)). 서버 리소스 임계값은 이 규칙의 예외로, 위대로 관리자 서버가 판단한다.
- 코어 설정 파일에 관리자 서버 주소를 직접 적지 않는다. 주소는 `MONITORING_APP_ADDRESS`로만 받고, 운영 명령의 펼치기 작업이 `core-settings.sh`의 `core_valid_address`로 `컨테이너 이름:포트` 형식을 검사한 값만 펼친다. 결정과 이유는 [ADR 0011](decisions/0011-core-address-format-check.md)과 [ADR 0012](decisions/0012-core-render-outside-image.md)에 있다. 코어와 관리자 서버 사이에 오가는 것은 [INTEGRATION.md](INTEGRATION.md)에 적은 것뿐이다.
- 코어 서버의 `.env`에서 바꾸는 코어 값은 기록 보관 기간(`CORE_RETENTION_DAYS`), 기록 보관 용량(`CORE_RETENTION_SIZE_GB`), 주소 확인 제한 시간(`CORE_PROBE_TIMEOUT_SECONDS`), 호스트 포트 세 개(`CORE_PROMETHEUS_PORT`, `CORE_ALERTMANAGER_PORT`, `CORE_BLACKBOX_PORT`)뿐이다(요구사항 [0013](requirements/0013-core-env-settings.md)). 운영 명령의 펼치기 작업은 `infrastructure/core-settings.sh`로 형식(단위 없고 앞에 0이 없는 정수)과 허용 범위를 검사하고, 검사를 통과한 정수만 설정 템플릿에 펼치고 실행 인자는 더하지 않는다. YAML 전체, Alert 규칙과 장애 판단 기준, Alertmanager의 `group_*`·`repeat_interval`, Prometheus의 수집·평가 주기와 `scrape_timeout`, keep 규칙, 컨테이너 안 포트, `127.0.0.1` 바인딩은 서버에서 열지 않는다. 허용 범위는 위 경계의 약속(예: 확인 제한 시간은 `scrape_timeout`에서 0.5초를 뺀 값 이하)을 지키도록 정하고, `scripts/build-core-bundle.mjs`의 설정 검사가 그 약속을 다시 확인한다. 값을 더하거나 허용 범위를 바꾸면 두 곳을 함께 고친다.
- API를 호출하는 쪽은 다음과 같다. 받던 입력을 거부하거나 응답을 바꾸는 변경이 호환성을 깨는지 이 범위로 판단한다.
  - 관리자 서버의 `/api/prometheus/sd/blackbox`, `/api/prometheus/sd/os-exporter`: Prometheus가 호출한다(`infrastructure/prometheus/prometheus.yml`). 공개 API로 보고, 계약은 [INTEGRATION.md](INTEGRATION.md)에 있다.
  - 관리자 서버의 `/api/integrations/alertmanager/webhook`: Alertmanager가 호출한다. 공개 API로 보고, 계약은 [INTEGRATION.md](INTEGRATION.md)에 있다.
  - 코어의 Prometheus HTTP API와 recording rule 이름: 관리자 서버가 호출하고 읽는다. 공개 API로 본다.
- 신뢰 경계는 다음과 같다.
  - 신뢰할 수 없는 입력은 관리자 서버의 HTTP SD 응답(대상 주소와 label), 감시 대상의 응답(probe 결과, OS exporter의 `/metrics`)으로 들어온다. Prometheus는 `sample_limit`과 keep 규칙으로 OS exporter에서 저장하는 series를 제한한다.
  - 코어 서버의 `.env`와 셸 환경 변수는 설치 폴더에 쓸 수 있는 운영자만 바꾸지만, 복사해 붙인 값이나 셸에 남은 환경 변수처럼 의도하지 않은 값이 들어올 수 있다. 그래서 운영 명령은 `.env`를 셸로 실행하지 않고 읽으며, 코어 값은 위 검사를 통과한 정수만 YAML 설정과 Compose `ports`에 넘긴다.
  - OS exporter는 인증 없이 열린다. 운영자가 대상 서버의 방화벽이나 AWS 보안 그룹에서 코어 서버만 접근하게 한다. exporter 트래픽이 VPC나 사내망 밖을 지나거나 고객사가 암호화·인증을 요구하면 TLS와 인증을 건다([#15](https://github.com/subinggrae/server-monitor/issues/15), 사람 확인: 2026-10-29).
  - Alertmanager는 관리자 서버의 webhook을 부를 때 Bearer token을 붙인다. 관리자 서버가 이 token으로 호출하는 쪽을 확인한다.
  - 코어의 감시 결과를 받는 곳은 관리자 서버뿐이다. 장애는 webhook으로 보내고, 수집 결과는 관리자 서버가 Prometheus HTTP API로 읽는다. 담당자에게 가는 알림은 관리자 서버가 보낸다.

## 공통 관심사

여러 구성 요소에 걸친 처리 방식은 다음과 같다.

- **인증·권한**: Prometheus, Alertmanager, Blackbox Exporter의 HTTP API에는 인증이 없다. 호스트 포트를 `127.0.0.1`에만 묶고, 관리자 서버는 `monitoring` 네트워크 안에서만 부른다.
- **비밀값**: webhook token을 `infrastructure/secrets/`(묶음에서는 설치 폴더의 `secrets/`)의 파일로 두고 Docker secret으로 Alertmanager에만 붙인다. Alertmanager는 `credentials_file`로 읽어 설정 파일에 값이 들어가지 않는다. 운영 명령은 token 값을 출력하지 않는다.
- **로그**: 세 구성 요소의 로그는 컨테이너 로그(`docker logs`)로 본다. 접속기록과 감사 로그는 없다.
- **시간대**: 이미지에 시간대 자료가 없어 코어는 UTC로 동작한다. 화면과 알림에 보일 시각은 관리자 서버가 바꾼다.
- **설정**: 서버에서 바꾸는 값은 `.env`, 비밀값은 `secrets/`에 둔다. 나머지 설정은 이미지와 템플릿에 들어 있고 서버에서 바꾸지 않는다.
