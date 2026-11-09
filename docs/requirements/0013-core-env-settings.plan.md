# 0013. 서버에서 바꾸는 코어 설정 값 구현 계획

- 요구사항: [0013. 서버에서 바꾸는 코어 설정 값](0013-core-env-settings.md)
- 상태: 진행 중

## 접근 방법

여는 값 여섯 개는 코어 설정 파일(`.env`)의 변수로 받는다. 이 변수를 Compose가 컨테이너 환경 변수와 `ports`에 넘기고, 이미지의 시작 스크립트 `render-config.sh`가 값을 검사한 뒤 설정 템플릿과 실행 인자에 펼친다. 앱 주소(`MONITORING_APP_ADDRESS`)를 펼치는 지금 방식([ADR 0007](../decisions/0007-core-app-contract.md), [ADR 0009](../decisions/0009-core-config-in-image.md))에 값을 더하고, YAML 전체나 Alert 규칙은 열지 않는다.

| 값 | 변수 | 받는 곳 | 펼치는 곳 |
|---|---|---|---|
| 기록 보관 기간 | `CORE_RETENTION_DAYS` | `prometheus` 컨테이너 환경 변수 | Prometheus 실행 인자 `--storage.tsdb.retention.time=<값>d` |
| 기록 보관 용량 | `CORE_RETENTION_SIZE_GB` | `prometheus` 컨테이너 환경 변수 | 값이 있을 때만 실행 인자 `--storage.tsdb.retention.size=<값>GB` |
| 확인 제한 시간 | `CORE_PROBE_TIMEOUT_SECONDS` | `blackbox-exporter` 컨테이너 환경 변수 | `blackbox.yml` 템플릿의 세 module `timeout: <값>s` |
| Prometheus 호스트 포트 | `CORE_PROMETHEUS_PORT` | Compose 변수 | `ports`의 `127.0.0.1:<값>:9090` |
| Alertmanager 호스트 포트 | `CORE_ALERTMANAGER_PORT` | Compose 변수 | `ports`의 `127.0.0.1:<값>:9093` |
| Blackbox Exporter 호스트 포트 | `CORE_BLACKBOX_PORT` | Compose 변수 | `ports`의 `127.0.0.1:<값>:9115` |

변수 이름은 이미 있는 `CORE_SECRETS_DIR`에 맞춰 `CORE_`로 시작하고 단위를 이름 끝에 붙여, 운영자가 값에 단위를 붙이지 않게 한다. 이름은 사람이 정했다(사람 확인: 2026-10-29).

### 값 검사를 두는 곳

요구사항은 두 곳에서 검사하라고 한다. 운영 명령은 띄우기 전에 여섯 값을 모두 검사하고(S8~S10), 운영 명령 없이 Compose로 띄우면 보관 기간·보관 용량·확인 제한 시간을 쓰는 컨테이너가 시작할 때 검사한다(S6, S11). 두 곳의 규칙이 어긋나지 않게 하는 방법으로 다음을 검토했다.

- **공용 검사 파일 하나를 두 곳이 읽음(권장)**: 값 이름, 기본값, 허용 범위, 이유, 안내 문구를 `infrastructure/core-settings.sh` 하나에 POSIX sh 함수로 둔다. `core-server.sh`는 묶음 폴더의 이 파일을, `render-config.sh`는 이미지에 넣은 같은 파일을 `.`로 읽는다. 규칙이 한곳에 있고, 빌드 PC의 검사(S7)도 이 파일에서 허용 범위 양 끝 값을 읽어 쓸 수 있다. 묶음에 파일이 하나 늘지만 체크섬이 함께 지킨다.
- **두 스크립트에 따로 씀**: 파일이 늘지 않는다 / 범위나 문구를 한쪽만 고치면 어긋나고, 그 어긋남을 시험이 같은 경우를 두 번 써야 잡는다.
- **운영 명령이 이미지 안의 검사를 임시 컨테이너로 실행**: 규칙이 이미지에만 있다 / 띄우기 전에 컨테이너를 하나 만들어야 하고(요구사항의 "컨테이너를 만들거나 바꾸지 않는다"와 어긋남), 이미지를 불러온 뒤에야 검사할 수 있으며, 포트 검사는 어차피 운영 명령에 남는다.

첫 번째를 고른다. 포트 범위와 겹침은 Compose 변수라 컨테이너가 받지 않으므로 운영 명령에서만 검사한다(S11에 적힌 대로).

검사 규칙은 다음과 같다.

- 값은 앞뒤 공백을 지운 뒤 비어 있으면 기본값으로 본다. 이 기능 전에 만든 `.env`에는 변수가 없으므로 기본값으로 돈다(S1).
- 형식은 앞에 0이 없는 단위 없는 10진 정수다. 정규식으로 보면 `^[1-9][0-9]*$`이고, `0`만 따로 받아 범위 오류로 안내한다(S9의 "0일"). 셸의 수 비교가 넘치지 않도록 자릿수를 제한한다. 포트·보관 기간·확인 제한 시간은 범위가 자릿수를 정하고, 보관 용량은 상한이 없어 9자리까지로 둔다(요구사항, 사람 확인: 2026-10-29).
- 형식이나 범위가 틀리면 변수 이름, 적힌 값, 허용 범위, 이유, 고치는 방법을 한국어로 표준 오류에 쓰고 실패한다. 적힌 값은 `printf '%s'`로만 내보내고 셸로 해석하지 않는다.
- 운영 명령은 지금처럼 셸 환경 변수가 있으면 그 값을, 없으면 `.env` 값을 읽는다(`env_value`). `.env`를 셸로 실행하지 않는다.

### 실행 인자와 설정에 펼치는 방법

- **Prometheus**: `Dockerfile`의 `CMD`에서 `--storage.tsdb.retention.time=15d`를 빼고, `render-config.sh`가 검사한 값으로 보관 인자를 만들어 `set --`로 실행 인자 끝에 덧붙인다. 인자는 따로 따옴표로 감싼 낱말 하나씩이라 값이 다른 인자로 갈라지지 않는다. 인자를 만드는 함수는 `core-settings.sh`에 둔다. 보관 용량이 비면 인자를 넣지 않아 Prometheus 기본값(제한 없음)이 된다.
  - 고르지 않은 방법: `CMD`에 `@CORE_RETENTION_DAYS@` 같은 자리표시를 두고 인자 안에서 바꾸는 방법. 보관 용량이 빌 때 인자 자체를 빼야 해서 규칙이 하나 더 생긴다.
- **Blackbox Exporter**: `blackbox.yml`의 세 `timeout: 5s`를 `timeout: @CORE_PROBE_TIMEOUT_SECONDS@s`로 바꿔 템플릿으로 넣고, 지금 Prometheus·Alertmanager처럼 `render-config.sh`가 `/tmp/blackbox.yml`에 펼친 뒤 `/bin/blackbox_exporter --config.file=/tmp/blackbox.yml`을 실행한다. 지금 Blackbox 이미지는 시작 스크립트를 쓰지 않으므로 `ENTRYPOINT`와 `CMD`가 새로 생긴다.
- **`render-config.sh`**: 이미지마다 `ENV CORE_SETTINGS="<이 이미지가 쓰는 변수 이름>"`을 두고, 그 변수만 검사해 펼친다. Alertmanager는 비워 둔다. Blackbox에는 앱 주소가 없으므로, 앱 주소는 템플릿에 `@MONITORING_APP_ADDRESS@`가 있을 때만 요구하고 펼친다. 펼치는 `sed`에는 검사를 통과한 숫자만 들어간다.
- **포트**: `compose.yml`의 `ports`를 `"127.0.0.1:${CORE_PROMETHEUS_PORT:-9090}:9090"` 꼴로 바꾼다. `127.0.0.1:`과 컨테이너 안 포트는 글자 그대로 남는다. `:-`라 빈 값도 기본값이 된다(S1).
- **Compose 환경 변수**: 지금 `x-render-config` anchor는 `environment` 전체를 merge하므로, 서비스에서 `environment`를 다시 쓰면 앱 주소가 사라진다. 앱 주소만 담은 anchor를 두고 각 서비스의 `environment` 안에서 `<<:`로 합친 뒤 `CORE_RETENTION_DAYS: ${CORE_RETENTION_DAYS:-}`처럼 더한다. 구현할 때 `docker compose config`로 merge 결과를 확인한다.

### 운영 명령의 확인과 표시

- **띄우기 순서**: `check_env` 다음에 값 검사(`check_settings`)를 넣고, `confirm_version` 다음, `docker compose up` 전에 줄임 확인(`confirm_retention`)을 넣는다. 검사에서 실패하면 이미지를 불러오지도 않는다.
- **줄임 확인(S12)**: 코어 컨테이너가 있으면(멈춘 것도 포함, `core_exists`) `docker inspect`로 `prometheus` 컨테이너의 환경 변수에서 지금 보관 기간과 용량을 읽는다. 변수가 없거나 비면 기본값(15일, 제한 없음)으로 본다. 이 기능 전의 컨테이너는 늘 15일·제한 없음이었으므로 맞다. 새 보관 기간이 더 짧거나, 새 용량이 더 작거나, 제한이 없던 용량을 처음 정하면 경고와 함께 묻는다(요구사항 S12). 묻는 방식은 `confirm_version`과 같다. 터미널이 아니면 실패로 끝나고, `y`가 아니면 아무것도 바꾸지 않고 끝난다.
- **기본값과 다른 값 표시**: 띄운 뒤와 `status`에서, 컨테이너가 실제로 받은 값을 읽어 기본값과 다른 것만 보인다. 보관 값과 확인 제한 시간은 `docker inspect`의 환경 변수, 포트는 `docker port <컨테이너> <안 포트>/tcp`로 읽는다. `.env`가 아니라 컨테이너에서 읽는 이유는 셸 환경 변수가 `.env`를 덮을 수 있어, 실제로 도는 값을 보여야 하기 때문이다(요구사항의 사용자 경험 3). 모두 기본값이면 한 줄로 그렇다고 보인다.

### 빌드 PC의 설정 검사(S7)

`scripts/build-core-bundle.mjs`의 `checkCoreImages`를 다음처럼 넓힌다.

- 값 세 벌(모두 비움, 허용 범위 아래 끝, 위 끝)마다 Prometheus 설정 검사(`promtool check config`), Alertmanager 설정 검사(`amtool check-config`), Blackbox 설정 검사(`blackbox_exporter --config.check`)를 실행한다. Blackbox 검사는 지금 없다. 아래 끝과 위 끝 값은 이미지 안의 `core-settings.sh`에서 읽는다. 보관 용량은 상한이 없으므로 위 끝에 형식 상한(9자리) 값을 쓴다.
- 같은 세 벌로 시작 스크립트를 실행 명령 대신 `printf`로 끝나게 돌려, Prometheus 보관 인자와 펼친 Blackbox 설정이 기대한 값인지 본다.
- 범위 밖 값 하나씩으로 Prometheus·Blackbox 이미지의 시작 스크립트를 돌려, 실패 종료 코드와 안내 문구를 확인하고 원래 프로세스가 실행되지 않았는지 본다(C5).
- 약속 검사: 확인 제한 시간 상한이 펼친 `prometheus.yml`의 `scrape_timeout`에서 0.5초를 뺀 값보다 작은지, 보관 기간 하한(일)이 규칙 파일들의 가장 긴 범위(지금 `[10m]`)보다 긴지, 포트 범위가 1024~65535 안이고 세 기본 포트가 서로 다른지 본다. promtool과 amtool은 이 약속을 모르므로 따로 본다.
- 묶음을 만들 때 실행용 compose 파일을 기본값과 양 끝 포트로 `docker compose config`에 넣어, 모든 `ports`의 `host_ip`가 `127.0.0.1`인지 본다.

### 사람이 정한 것

검토 중에 정한 것은 다음과 같다(사람 확인: 2026-10-29). 동작에 관한 결정은 요구사항 본문에도 반영했다.

- **변수 이름**: 위 표의 여섯 이름을 쓴다.
- **보관 용량의 형식 상한**: 9자리까지 받고, 넘거나 앞에 0이 붙은 값은 형식 오류다(요구사항 S8).
- **제한 없음에서 용량을 처음 정하는 경우**: 줄임으로 보고 확인을 묻는다(요구사항 S12).
- **이 기능 전 버전으로 되돌릴 때의 기록 손실**: 받아들이고 운영 가이드의 되돌리기에 경고를 쓴다(요구사항 S15).
- **INTEGRATION.md의 "확인마다 5초" 문장**: "기본 5초, 코어 서버에서 1~9초"로 고친다. API와 label은 바뀌지 않는다.

## 바뀌는 부분

바뀌는 파일과 내용은 다음과 같다.

| 위치 | 변경 |
|---|---|
| `infrastructure/core-settings.sh` | 새 파일: 여섯 값의 기본값, 허용 범위, 이유, 형식·범위 검사와 안내, Prometheus 보관 인자 만들기, 빌드 검사가 읽을 범위 목록 출력 |
| `infrastructure/render-config.sh` | `core-settings.sh`로 `CORE_SETTINGS`의 값을 검사해 템플릿의 자리표시와 Prometheus 보관 인자를 펼치고, 앱 주소는 템플릿에 자리표시가 있을 때만 요구 |
| `infrastructure/prometheus/Dockerfile` | `core-settings.sh` 복사, `ENV CORE_SETTINGS="CORE_RETENTION_DAYS CORE_RETENTION_SIZE_GB"`, `CMD`에서 보관 인자를 뺌 |
| `infrastructure/blackbox/Dockerfile` | `render-config.sh`, `core-settings.sh`, 템플릿 복사, `ENV CORE_SETTINGS="CORE_PROBE_TIMEOUT_SECONDS"`, 새 `ENTRYPOINT`와 `CMD` |
| `infrastructure/alertmanager/Dockerfile` | `render-config.sh`가 읽는 `core-settings.sh` 복사(펼칠 값은 없음) |
| `infrastructure/blackbox/blackbox.yml` | 세 module의 `timeout: 5s` → `timeout: @CORE_PROBE_TIMEOUT_SECONDS@s` |
| `infrastructure/compose.yml` | 세 `ports`를 변수로, `prometheus`와 `blackbox-exporter`에 새 환경 변수, 앱 주소 anchor 정리, 머리 주석 |
| `infrastructure/.env.example` | 여섯 변수를 주석 처리한 예시로 더하고, 변수마다 뜻, 단위, 기본값, 허용 범위, 바꾼 뒤 할 일을 적음 |
| `infrastructure/core-server.sh` | `core-settings.sh` 읽기, `check_settings`, `confirm_retention`, 기본값과 다른 값 표시(`start`, `status`), 머리 주석 |
| `infrastructure/tests/core-server.test.sh` | 새 값의 검사·확인·표시 시험, 가짜 `docker`에 환경 변수와 `port` 응답, 묶음에 `core-settings.sh` 복사 |
| `scripts/build-core-bundle.mjs` | 묶음에 `core-settings.sh` 싣기, 값 세 벌로 설정 검사, Blackbox 설정 검사, 시작 스크립트의 펼친 결과와 범위 밖 값 거부 확인, 약속 검사, 포트 바인딩 확인 |
| `docs/OPERATIONS.md` | 서버에서 바꾸는 값 표에 여섯 값, 빌드 전에 고치는 값에서 보관 기간·제한 시간·포트를 뺌, 처음 따라 하기의 디스크 안내, 운영 작업(값 바꾸기, 줄일 때 확인, 이전 버전으로 되돌릴 때 경고), 문제 해결(새 안내 문구, 쓰는 포트), 포트 표와 설치 확인의 `127.0.0.1:9090`에 바꾼 포트 안내 |
| `docs/ARCHITECTURE.md` | 실행 단위의 `render-config.sh` 설명, 지켜야 할 경계에 서버에서 여는 값은 이 여섯 개뿐이고 검사한 정수만 펼친다는 것 |
| `docs/DEVELOPMENT.md` | 코어 검사와 묶음의 검사 목록(값 세 벌, Blackbox 검사, 약속 검사) |
| `docs/INTEGRATION.md` | "확인마다 5초" 문장을 "기본 5초, 코어 서버에서 1~9초"로 |

### 데이터 모델 변경

해당 없음. Manager Database와 Prometheus 데이터 volume의 형식은 바뀌지 않는다.

### 계약 변경

관리자 서버와의 통합 계약은 바뀌지 않는다. 바뀌는 것은 운영자가 쓰는 코어 설정 파일이다.

```text
전                                       후
.env                                     .env
  MONITORING_APP_ADDRESS                   MONITORING_APP_ADDRESS
  CORE_SECRETS_DIR                         CORE_SECRETS_DIR
                                           CORE_RETENTION_DAYS         (비면 15)
                                           CORE_RETENTION_SIZE_GB      (비면 제한 없음)
                                           CORE_PROBE_TIMEOUT_SECONDS  (비면 5)
                                           CORE_PROMETHEUS_PORT        (비면 9090)
                                           CORE_ALERTMANAGER_PORT      (비면 9093)
                                           CORE_BLACKBOX_PORT          (비면 9115)

prometheus 실행 인자                      prometheus 실행 인자
  --storage.tsdb.retention.time=15d        --storage.tsdb.retention.time=<값>d
                                           [--storage.tsdb.retention.size=<값>GB]
blackbox: /etc/blackbox_exporter/        blackbox: 템플릿을 /tmp/blackbox.yml로 펼침
  config.yml(timeout: 5s 고정)             (timeout: <값>s)
ports: 127.0.0.1:9090:9090 등 고정        ports: 127.0.0.1:<값>:9090 등
```

## 위험과 되돌리기

- 보관 기간이나 용량을 줄여 기록이 지워짐: 운영 명령이 확인을 받은 뒤에만 띄운다(S12). `docker compose down`으로 컨테이너가 없어진 뒤에는 지난 값을 읽을 곳이 없어 묻지 못한다. 요구사항 S12는 코어가 떠 있을 때만 다루므로, 운영 가이드에 이 경우를 적는다.
- 이 기능 전 버전으로 되돌림: 이전 버전은 새 변수를 모르므로 보관 15일, 기본 포트로 돌아가고 확인 없이 기록이 지워진다. 사람이 이 위험을 받아들였다(요구사항 S15). 운영 가이드의 되돌리기에 경고를 쓴다.
- Prometheus의 `GB`가 2의 거듭제곱 단위임: Prometheus 문서는 `--storage.tsdb.retention.size`의 `GB`를 1024 기준으로 설명한다(작업 1에서 v3.14.0 문서와 동작으로 확인). 확인되면 `.env.example`과 운영 가이드에 "1GB는 1024MB"라고 쓴다. 보관 용량은 블록만이 아니라 WAL도 세는지, 잠시 넘을 수 있는지도 같이 확인해 디스크 안내에 쓴다.
- Blackbox가 시작하지 못함: 운영 명령 없이 틀린 값으로 띄우면 Blackbox가 다시 시작을 되풀이하고, 그동안 모든 감시 항목의 확인이 실패해 장애가 한꺼번에 갈 수 있다. 운영 명령은 띄우기 전에 막는다. 운영 가이드의 운영 명령 없이 실행에 이 영향을 쓴다.
- 포트를 바꾼 뒤 운영자의 SSH 터널, 북마크, 설치 확인 명령이 옛 포트를 씀: 띄우기와 상태 명령이 바뀐 포트를 보이고, 운영 가이드의 설치 확인과 포트 절에 바꾼 포트로 읽으라고 쓴다. 관리자 서버가 부르는 `http://prometheus:9090`과 healthcheck는 컨테이너 안 포트라 바뀌지 않는다.
- Compose가 포트 변수의 이상한 값을 받아들임: 운영 명령 없이 띄우면 범위 검사가 없다(S11). `127.0.0.1:`이 글자 그대로라 바인딩 주소는 바뀌지 않을 것으로 보지만 확인하지 않았다. 작업 1에서 `0.0.0.0:9090`, `9090-9100`, `[::]:9090` 같은 값으로 `docker compose config`를 돌려 거부되거나 `host_ip`가 `127.0.0.1`로 남는지 본다. 바인딩이 바뀌는 값이 있으면 멈추고 묻는다.
- 0012 계획([#22](https://github.com/subinggrae/server-monitor/issues/22))과 겹치는 파일: 0012는 Alert 규칙(`monitoring-checks.yml`), `alertmanager.yml`, `infrastructure/prometheus/tests/`의 규칙 테스트, `INTEGRATION.md`, `OPERATIONS.md`를 고친다.
  - `OPERATIONS.md`: 두 계획 모두 고친다. 0012는 label 예시와 배포 때의 1회성 영향을, 이 계획은 값 표·운영 작업·문제 해결·포트·설치 확인을 고친다. 운영 작업과 설치 확인은 두 쪽이 같은 절을 고칠 수 있다.
  - `INTEGRATION.md`: 이 계획은 "확인마다 5초" 문장을, 0012는 label 표를 고친다. 절이 달라 겹칠 가능성은 작지만 나중 쪽이 rebase할 때 확인한다.
  - `infrastructure/prometheus/tests/`: 이 계획은 고치지 않지만, 빌드 검사가 값 세 벌로 돌 때 0012가 새로 만드는 `monitoring-checks.test.yml`도 함께 돈다. 규칙 테스트는 보관 값과 무관하므로 영향이 없을 것으로 본다.
  - 장애 메시지 문구: 0012는 Alert 규칙의 `summary` 문구를 바꾼다. 요구사항 0013의 바뀌지 않는 것은 이 변경으로 바꾸지 않는 것이라고 밝혀 두었고, 문구와 Alert label은 0012가 바꾼다고 적었다. 0012가 먼저 병합되면 0013의 리뷰는 0012가 바꾼 문구를 기준으로 본다. 이 계획은 `monitoring-checks.yml`을 고치지 않는다.
  - ADR 번호: 0012가 ADR 0010을 쓴다. 이 계획은 ADR을 쓰지 않는다. 나중에 필요해지면 0011을 쓴다.
  - 병합 순서: 먼저 승인된 쪽이 먼저 병합하고, 나중 쪽은 기본 브랜치에 rebase한 뒤 `core-dev.bat check`와 `node scripts/check-docs.mjs`를 다시 돌린다. 두 변경 모두 코어 서버 갱신이 필요하므로, 가까운 시기라면 한 묶음으로 배포하면 운영자의 갱신이 한 번으로 준다.
- 되돌리기: 이 변경을 revert하면 보관 15일과 기본 포트, 5초로 돌아간다. 데이터 volume은 그대로라 기록은 남지만, 보관 기간을 늘려 두었던 서버는 15일을 넘는 기록을 잃는다. 이미 배포한 서버는 이전 묶음 폴더에서 `start`를 실행한다. `.env`에 남은 새 변수는 이전 버전이 무시한다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 운영자가 쓰는 `.env`와 셸 환경 변수의 새 값이 운영 명령(`core-server.sh`), Compose `ports`, 컨테이너 환경 변수를 거쳐 시작 스크립트(`render-config.sh`)에서 YAML 설정과 Prometheus 실행 인자로 펼쳐진다. 이 값을 쓸 수 있는 사람은 묶음 폴더에 쓸 수 있는 운영자뿐이지만, 복사해 붙인 값, 다른 문서의 예시, 셸에 남은 환경 변수처럼 운영자가 의도하지 않은 값이 들어올 수 있다. [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)에 서버에서 여는 값과 검사 규칙을 더한다.
- 잘못될 수 있는 것과 막는 방법:
  - 설정을 바깥 값이 정함(CWE-15), 형식·범위를 검사하지 않음(CWE-20): 운영 명령과 시작 스크립트가 같은 `core-settings.sh`로 형식과 범위를 검사하고, 틀리면 아무것도 바꾸지 않거나 시작하지 않는다. 시험: C4, C5, C7의 범위 밖 값.
  - 값에 넣은 줄바꿈이나 YAML 문법으로 Blackbox 설정의 다른 항목을 바꿈(CWE-74): 검사를 통과한 숫자만 `sed`의 바꿀 글자로 쓴다. `sed` 구분자(`|`), `&`, 줄바꿈, `prober:` 같은 YAML을 넣은 값이 거부되는지 시험한다.
  - 값에 공백을 넣어 Prometheus 실행 인자를 더함(CWE-88). 예: 보관 기간에 `15 --web.enable-admin-api`. 숫자만 받고, 인자를 따옴표로 감싼 낱말 하나로 만든다. 그런 값이 거부되고, 펼친 인자 목록에 다른 인자가 없는지 시험한다.
  - 운영 명령이 값을 셸로 해석함(CWE-78): `.env`를 `.`로 실행하지 않는 지금 방식(`env_value`)을 유지하고, 값을 `eval`이나 따옴표 없는 확장에 넣지 않는다. `$(…)`와 백틱을 넣은 값이 실행되지 않고 형식 오류가 되는지 시험한다.
  - 포트 값으로 코어 포트가 `127.0.0.1` 밖에 열림(CWE-1327): `compose.yml`의 `127.0.0.1:`을 글자 그대로 두고, 운영 명령은 숫자 포트만 받는다. 빌드 검사가 실행용 compose 파일의 모든 `host_ip`가 `127.0.0.1`인지 보고, 작업 1에서 운영 명령 없이 띄울 때의 이상한 값도 확인한다.
- 받아들이는 위험: 운영 명령 없이 Compose로 띄울 때 포트의 허용 범위와 겹침은 검사하지 않는다. 요구사항 S11에서 사람이 승인했다. 이 기능 전 버전으로 되돌릴 때의 기록 손실은 사람이 받아들였다(요구사항 S15, 사람 확인: 2026-10-29).

## 테스트 계획

`core-server.test.sh`는 지금처럼 `core-dev.bat check` 안에서 이미지의 BusyBox `sh`로 돈다. 수동 확인은 Windows 빌드 PC의 개발용 코어(`core-dev.bat start`)에서 하고, C3만 묶음 두 개를 쓴다.

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | 빌드 검사, 운영 명령 자동 시험, 개발용 코어 수동 확인 | `core-dev.bat check` 통과와 아래 C1의 수동 결과 |
| C2 | 개발용 코어 수동 확인(값 바꿔 띄우기, 느린 대상) | 아래 C2의 결과 |
| C3 | 묶음 두 개로 수동 확인 | 아래 C3의 결과 |
| C4 | 운영 명령 자동 시험(`core-server.test.sh`)과 검사를 지운 사본 | `core-dev.bat check` 통과, 사본마다 시험 실패 |
| C5 | 빌드 검사의 범위 밖 값 시작, 운영 명령 없이 띄우는 수동 확인 | `core-dev.bat check` 통과와 아래 C5의 수동 결과 |
| C6 | 운영 명령 자동 시험(`core-server.test.sh`) | `core-dev.bat check` 통과 |
| C7 | 빌드 검사의 값 세 벌·약속 검사·포트 바인딩, 망가뜨려 확인 | `core-dev.bat check` 통과, 망가뜨린 동안 실패 |
| C8 | 문서 검사와 리뷰 | `node scripts/check-docs.mjs` 통과 |
| C9 | 묶음 만들기의 비밀값 검사와 리뷰 | `core-dev.bat build --allow-dirty` 통과 |

기준마다 확인하는 내용은 다음과 같다.

- **C1**: 빌드 검사에서 값을 모두 비우면 보관 인자가 `--storage.tsdb.retention.time=15d` 하나이고 `retention.size`가 없으며, 펼친 Blackbox 설정에 `timeout: 5s`가 세 개, 실행용 compose의 포트가 `127.0.0.1:9090:9090` 등이다. 운영 명령 시험은 새 변수가 없는 `.env`와 빈 값만 있는 `.env`로 띄우기가 진행되는지 본다. 수동으로는 이 기능 전 `.env`로 띄워 `curl -s http://127.0.0.1:9090/api/v1/status/flags`의 `storage.tsdb.retention.time`이 `15d`, `storage.tsdb.retention.size`가 `0B`, `curl -s http://127.0.0.1:9115/config`의 `timeout`이 `5s`이고, `sh core-server.sh status`가 모두 기본값이라고 보이는지 본다.
- **C2**: `.env`에 30, 50, 8, 19090, 19093, 19115를 적고 `core-dev.bat start`로 띄운다. flags API에서 `30d`와 `50GB`(Prometheus 표기는 작업 1에서 확인), `http://127.0.0.1:19115/config`에서 세 `timeout: 8s`, `docker port`에서 바뀐 포트, `docker compose ps`에서 세 컨테이너 healthy를 본다. `http://127.0.0.1:19090/targets`에서 Alertmanager와 `blackbox-exporter:9115` 대상이 그대로인지도 본다. 느린 대상은 닿지 않는 주소(예: `10.255.255.1:80`)를 `http://127.0.0.1:19115/probe?module=tcp_connect&target=…`로 확인해, `probe_duration_seconds`가 약 8초이고 값을 5로 되돌리면 약 5초인지 본다.
- **C3**: 버전이 다른 묶음 v1(보관 30일)과 v2를 한 설치 폴더에 두고 v2에서 `install`, `start`를 실행한다. v2의 `.env`에 30이 이어지고, flags API가 `30d`이며, `/api/v1/status/tsdb`의 `minTime`이 갱신 전과 같은지 본다. 15일에서 30일로 늘려 띄운 뒤에도 `minTime`이 같은지 본다.
- **C4**: 형식 오류(`15d`, `5s`, `abc`, `-1`, `7.5`, `015`, 공백, 줄바꿈, `$(…)`, 10자리 용량), 범위 오류(보관 0·366, 용량 0, 제한 시간 0·10, 포트 80·1023·65536), 포트 겹침(세 쌍과 셋 모두), 셸 환경 변수가 `.env`를 덮는 경우를 만든다. 경우마다 종료 코드가 0이 아니고, 출력에 변수 이름·적힌 값·허용 범위·고치는 방법이 있으며, 가짜 `docker`에 `compose`와 `load` 호출이 없어야 한다. 제한 시간이면 수집 제한 시간(10초) 이유가, 겹침이면 겹친 포트와 구성 요소 이름이 있어야 한다. 검사를 하나씩 지운 사본마다 시험이 실패하는지 보고 결과를 진행 중 발견에 쓴다.
- **C5**: 빌드 검사에서 `CORE_RETENTION_DAYS=0`, `CORE_RETENTION_SIZE_GB=abc`, `CORE_PROBE_TIMEOUT_SECONDS=10`마다 종료 코드가 0이 아니고, 오류 출력에 변수 이름과 안내가 있으며, 원래 프로세스가 실행되지 않아야 한다. 수동으로는 틀린 `.env`로 `docker compose up -d`를 실행해, `docker compose ps`에서 그 컨테이너가 다시 시작 중이고 `docker logs prometheus`(또는 `blackbox-exporter`)에 같은 안내가 있는지 본다.
- **C6**: 떠 있는 코어가 30일·100GB인 상태에서 줄인 값으로 `start`를 실행한다. 터미널이 아니면 실패하고 `compose` 호출이 없어야 한다. 터미널에서 `n`이면 종료 코드 0이고 `compose` 호출이 없으며, `y`면 `compose up`이 호출된다. 같거나 늘린 값은 묻지 않는다. 환경 변수가 없는 예전 컨테이너는 15일·제한 없음으로 보고 비교한다. 제한이 없던 코어에 용량을 처음 정하면 묻는지도 시험한다.
- **C7**: `core-dev.bat check`가 세 벌마다 `promtool check config`, `amtool check-config`, `blackbox_exporter --config.check`를 통과했다고 보인다. `core-settings.sh`의 확인 제한 시간 상한을 10으로, 보관 기간 하한을 0으로 잠시 바꾸면 약속 검사가 실패하고, 되돌리면 통과해야 한다. `core-dev.bat build --allow-dirty`에서 실행용 compose의 `host_ip`가 모두 `127.0.0.1`이어야 한다.
- **C8**: `.env.example`의 여섯 변수마다 뜻, 단위, 기본값, 허용 범위가 있는지, 운영 가이드의 값 표·운영 작업·문제 해결과 ARCHITECTURE.md의 경계가 맞는지, 운영 가이드의 되돌리기에 이 기능 전 버전으로 되돌릴 때의 경고(S15)가 있는지, INTEGRATION.md의 확인 제한 시간 문장이 바뀌었는지 리뷰에서 본다.
- **C9**: 묶음과 이미지의 비밀값 검사가 통과해야 한다. 리뷰에서 Dockerfile의 `ENV`에 변수 이름 목록만 있고 서버마다 다른 값이 없는지, 새 값이 이미지가 아니라 컨테이너 환경 변수에만 있는지 본다.

운영 명령 자동 시험에는 띄우기와 상태의 표시도 넣는다. 가짜 `docker`가 기본값과 다른 환경 변수와 포트를 돌려줄 때 그 값만 보이고, 모두 기본값이면 기본값이라고 보이는지 본다.

## 작업 순서

- [x] 1. 사전 확인: Prometheus v3.14.0의 `--storage.tsdb.retention.size` 단위(`GB`가 1024 기준인지)와 WAL 포함 여부, Blackbox Exporter v0.28.0의 `--config.check`, Blackbox 이미지의 `/bin/sh`와 `/tmp` 쓰기, Compose가 `127.0.0.1:${VAR}:9090`에 이상한 값(`0.0.0.0:9090`, `9090-9100`, `[::]:9090`)을 넣었을 때의 결과, `environment` 안의 `<<:` merge. 결과와 근거를 진행 중 발견에 쓰고, 바인딩이 바뀌는 값이 있으면 멈추고 묻는다.
- [x] 2. 운영 명령 시험 먼저: `core-server.test.sh`에 C4, C6과 표시 시험을 더하고, 가짜 `docker`에 환경 변수와 `port` 응답을 더한다. 지금 `core-server.sh`로 새 시험이 실패하는지 본다.
- [x] 3. `core-settings.sh`를 쓰고, `core-server.sh`에 `check_settings`, `confirm_retention`, 표시를 더한다. 빌드 스크립트가 묶음에 `core-settings.sh`를 싣게 한다. 2의 시험이 통과하고, 검사를 지운 사본마다 시험이 실패하는지 본다.
- [ ] 4. 빌드 검사 먼저: `build-core-bundle.mjs`에 값 세 벌의 설정 검사, Blackbox 검사, 시작 스크립트의 펼친 결과와 범위 밖 값 거부, 약속 검사, 포트 바인딩 확인을 더한다. 지금 이미지로 실패하는지 본다.
- [ ] 5. `render-config.sh`, 세 Dockerfile, `blackbox.yml` 템플릿, `compose.yml`을 바꾼다. 4의 검사가 통과하고, C7의 망가뜨려 확인을 한다.
- [ ] 6. `.env.example`, `OPERATIONS.md`, `ARCHITECTURE.md`, `DEVELOPMENT.md``INTEGRATION.md`를 고친다. `node scripts/check-docs.mjs`를 통과시킨다.
- [ ] 7. 수동 확인: C1, C2, C5의 운영 명령 없이 띄우기, C3의 묶음 두 개. 결과를 요구사항의 완료 기준에 쓴다. 띄운 개발용 코어와 시험용 컨테이너는 끝나면 멈춘다.
- [ ] 8. 전체 검증(DEVELOPMENT.md의 전체 검증 행과 `core-dev.bat check`)과 새 세션의 리뷰. 리뷰어에게 완료 기준, 이 계획, diff, 검증한 커밋 SHA와 결과, 위 보안 위협의 CWE를 준다.

## 진행 중 발견

작업 1(사전 확인, 2026-11-09)에서 확인한 사실은 다음과 같다. 바인딩 주소가 바뀌는 값은 없어 멈추지 않았다.

- **Prometheus 보관 용량의 단위와 범위**: v3.14.0 이미지의 `prometheus --help`가 `--storage.tsdb.retention.size`를 "Based on powers-of-2, so 1KB is 1024B"로 설명한다. [v3.14.0 `docs/storage.md`](https://github.com/prometheus/prometheus/blob/v3.14.0/docs/storage.md)는 지우는 것은 블록뿐이지만 WAL과 m-mapped chunk도 크기에 센다고 하고, 디스크 크기가 잠시 이 값을 넘을 수 있으며, 이 값을 디스크의 80~85% 이하로 두라고 권한다. `.env.example`과 운영 가이드에 이 세 가지를 쓴다.
- **Prometheus 보관 인자는 deprecated**: 같은 `--help`가 `--storage.tsdb.retention.time`과 `--storage.tsdb.retention.size`를 `[DEPRECATED]`로 표시하고, 설정 파일의 `storage.tsdb.retention` 항목을 쓰라고 한다. 지금 `Dockerfile`도 이미 deprecated인 `retention.time` 인자를 쓰고, 고정한 v3.14.0에서는 두 인자가 동작하며, 완료 기준 C1·C2가 flags API로 값을 확인하므로 계획대로 실행 인자로 펼친다. Prometheus를 올려 인자가 없어지면 Prometheus가 시작하지 못하므로, 그때 설정 파일 항목으로 옮긴다.
- **Blackbox Exporter v0.28.0**: `--config.check`가 있다. 이미지에 BusyBox `/bin/sh`가 있고, 이미지 사용자가 root(`User` 빈 값)이며 `/tmp`에 쓸 수 있다.
- **Compose v5.1.0의 포트 변수**: `"127.0.0.1:${CORE_PROMETHEUS_PORT:-9090}:9090"`에 `0.0.0.0:9090`, `[::]:9090`, `9090:9091`, `1:2:3`을 넣으면 `invalid IP address`로, `abc`, `-1`, `65536`, ` 9090`, `9090/udp`를 넣으면 `invalid hostPort`로 거부한다. `9090-9100`(범위), `80`, `0`, `015`(15로 읽음)는 받아들인다. 어느 값이든 `host_ip`는 `127.0.0.1`로 남는다. 받아들이는 값은 운영 명령 없이 띄울 때만 들어온다(요구사항 S11에서 받아들인 위험).
- **`environment` 안의 `<<:` merge**: `environment:` 아래에 `<<: *앱 주소 anchor`를 두고 다른 변수를 더하면 두 쪽이 모두 남는다(`docker compose config`로 확인).

구현하면서 계획과 다르게 고른 것은 다음과 같다. 요구사항은 바뀌지 않는다.

- **검사한 값을 Compose에 넘김**: 운영 명령은 검사를 통과한 값(빈 값은 기본값)을 `export`한 뒤 `docker compose up`을 실행한다. 운영 명령의 `.env` 읽기(`env_value`)가 Compose와 조금이라도 다르면 검사한 값과 띄우는 값이 어긋날 수 있기 때문이다. 셸 환경 변수가 `.env`를 덮는 순서는 그대로다.
- **포트 표시를 `docker port` 대신 `docker inspect`의 `HostConfig.PortBindings`로 읽음**: `docker port`는 멈춘 컨테이너의 포트를 보이지 않아, `status`가 멈춘 코어의 바꾼 포트를 보이지 못하기 때문이다.
- **token 바꾸기도 값을 검사함**: `rotate-token`은 Alertmanager를 `docker compose up`으로 다시 만들어 그때 `.env`의 호스트 포트를 쓴다. 그래서 `start`와 같은 값 검사(`check_settings`)를 거친다. 틀린 값이 있으면 token을 바꾸지 않는다.
- **표시하는 범위 문구**: 보관 용량의 허용 범위는 `1 이상 999999999 이하(GB)`로 보인다.

작업 3의 검사를 지운 사본(C4)마다 운영 명령 시험의 실패 수는 다음과 같다. 모두 실패했다.

| 지운 검사 | 실패한 시험 수(전체 100) |
|---|---|
| 형식 검사(숫자가 아님, 앞에 0) | 10 |
| 앞에 0이 붙은 값 검사만 | 1 |
| 자릿수 상한 | 1 |
| 범위 검사 | 8 |
| 앞뒤 공백 지우기 | 1 |
| 띄우기의 값 검사 호출 | 56 |
| token 바꾸기의 값 검사 호출 | 1 |
| 포트 겹침 검사 | 4 |
| 검사한 값 `export` | 42 |
| 줄임 확인 호출 | 7 |
| 제한 없던 용량을 처음 정할 때의 확인 | 1 |
| 줄임 확인의 터미널 검사 | 5 |
| 띄운 뒤 설정 값 표시 | 4 |
| 표시의 기본값 비교 | 3 |

## 남길 결정

없음. 새 ADR은 쓰지 않는다. 서버마다 다른 값을 실행할 때 받는다는 결정은 [ADR 0009](../decisions/0009-core-config-in-image.md)에, 시작 스크립트가 템플릿에 펼친다는 결정은 [ADR 0007](../decisions/0007-core-app-contract.md)에 이미 있다. 이번 계획은 그 방식에 값을 더하는 것이고, 공용 검사 파일은 클라이언트나 데이터를 고치지 않고 되돌릴 수 있다. 서버에서 여는 값의 목록과 검사 규칙은 ARCHITECTURE.md의 지켜야 할 경계에, 변수의 뜻과 범위는 `.env.example`과 운영 가이드에 남긴다.
