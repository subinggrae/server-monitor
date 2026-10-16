# 0006. SNMP를 걷어내고 OS exporter로 서버 자원 수집 구현 계획

- 요구사항: [0006. SNMP를 걷어내고 OS exporter로 서버 자원 수집](0006-os-exporter.md)
- 상태: 승인

## 접근 방법

```text
화면 ── PUT /api/servers/{id}/exporter ──▶ Manager DB(server_targets.os, server_exporter_configs)
                                             │
Prometheus ◀── GET /api/prometheus/sd/os-exporter (30초마다) ── host:port, server_id, server_name,
   │                                                            environment, os, __scrape_interval__
   ├─ job manager-os: http://host:port/metrics (node_exporter / windows_exporter)
   └─ rules/server-resources.yml: manager:resource_usage_percent / _used_bytes / _size_bytes
                                             │
Manager ── PromQL(prometheus 패키지) ──▶ 화면의 사용률 표, 서버의 수집 상태
Manager ── GET http://host:port/metrics (연결 시험, 리소스 찾기만. 경로 고정, 한도 있음)
```

1. **수집 대상 전달**: 0001과 같은 방식이다. 수집 설정은 Manager DB에 두고, 새 HTTP SD가 entry마다 `__scrape_interval__`을 넘긴다. Prometheus가 exporter를 직접 수집하므로 snmp_exporter와 Manager가 쓰던 파일(`snmp-runtime`)이 없어진다.
2. **OS 공통 이름**: OS별 계산은 recording rule 한 파일에만 둔다. 화면(`ServerResourceService`)과 이후의 알림(0004, #14)은 공통 이름만 읽는다. 이름과 파일은 0004 계획과 ADR 0003(미병합)이 이미 정한 `manager:resource_usage_percent{server_id, resource_type, resource_key}`, `rules/server-resources.yml`과 맞춰 0004를 다시 설계하지 않게 한다.
3. **역할 분담**: 서버 생존은 Blackbox 감시 항목이 맡는다. 서버 화면은 `up{job="manager-os"}`로 정한 수집 상태만 보이고, 서버 생존처럼 보이는 `status`는 없앤다.
4. **연결 시험과 리소스 찾기**: Manager가 `http://{host}:{port}/metrics`를 Java `HttpClient`로 한 번 읽고 필요한 metric family만 해석한다.
5. **SNMP 제거**: V9 migration이 SNMP 설정과 리소스 선택을 지우고, 코드·의존성·컨테이너·volume·암호화 서비스·`APP_SECRET_KEY`를 한 변경에서 없앤다.

검토했지만 고르지 않은 방법은 다음과 같다.

- 화면용 PromQL을 Java에 따로 둠: OS별 계산식이 Java와 규칙 파일 두 곳에 생긴다. 규칙 파일과 Manager가 같은 배포에 들어가므로 규칙을 읽어도 문제가 없다.
- 수집 설정을 `server_targets` 열로 둠: 서버 자산 API가 수집 설정까지 떠안고, #15(exporter 인증)에서 열이 계속 는다. 서버당 한 행 테이블이 옛 `server_snmp_configs`와 같은 모양이라 코드 이동도 적다.
- Prometheus API로 리소스 찾기: 수집을 켜고 첫 수집이 끝나야 목록이 나온다. 요구사항 S2가 Manager의 직접 읽기를 정했다.
- 옛 `/api/prometheus/sd/snmp`를 빈 목록으로 남김: C8(SNMP가 남지 않음)과 맞지 않는다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/{mariadb,postgresql}/V9__os_exporter.sql` | 새 파일(데이터 모델 변경 참고) |
| `backend/.../server/` | `ServerOs`(새, LINUX·WINDOWS와 기본 포트), Entity·요청·응답의 `os`, 응답의 `status`·`snmpState`를 `collectionState`로 |
| `backend/.../exporter/`(새 패키지) | 수집 설정 Entity·Store·Controller·Service, `ExporterClient`(HTTP), `ExporterMetricsParser`, `FilesystemFilter`, `CollectionState` |
| `backend/.../snmp/` | 패키지 전체 삭제 |
| `backend/.../prometheus/` | `OsExporterServiceDiscoveryService`, `ResourceMetricQueryService`(새), SNMP SD·조회 클래스 삭제 |
| `backend/.../resource/` | 리소스 찾기를 exporter 읽기로, `snmpIndex` 삭제 |
| `backend/.../common/security/`, `config/` | `SecretEncryptionService`, `APP_SECRET_KEY`·secret key 설정, SNMP 설정 키 삭제 |
| `backend/build.gradle`, `Dockerfile`, `application.yml`, `.env.example` | snmp4j, `/runtime`, SNMP·secret key 설정 삭제 |
| `infrastructure/prometheus/prometheus.yml` | `manager-snmp` job을 `manager-os` job으로 |
| `infrastructure/prometheus/rules/server-resources.yml`, `.test.yml` | 새 recording rule과 `promtool` 테스트 |
| `infrastructure/snmp/`, `infrastructure/secrets/app_secret_key*` | 삭제 |
| `compose.yml` | `snmp-exporter`, `snmp-test-agent`, `snmp-runtime`, `app_secret_key` secret 삭제, `os-test` profile의 node_exporter 추가 |
| `frontend/src/lib/api.ts`, `components/StatusDot.tsx` | OS·수집 상태 타입, SNMP 타입 삭제 |
| `frontend/src/components/servers/` | `ServerCollectionSection`(새, SNMP 절 대신), 리소스 절, 서버 폼의 OS |
| `frontend/src/pages/` | 서버 상세(탭: 리소스, 수집), 서버 목록(수집 열, OS 열), 대시보드 서버 요약 |
| 문서 | [문서 갱신](#문서-갱신) |

### 데이터 모델 변경

- `server_targets`: `os VARCHAR(10) NOT NULL DEFAULT 'LINUX'`. 요청 DTO에서 `LINUX`, `WINDOWS`만 받는다(`String` + `@NotNull @Pattern`, enum으로 받으면 칸별 오류가 안 나옴).
- `server_exporter_configs`(새, 서버당 0~1행): `id`, `server_id`(UNIQUE, FK `ON DELETE CASCADE`), `enabled BOOLEAN NOT NULL DEFAULT FALSE`, `port INT NOT NULL`(1~65535), `scrape_interval_seconds INT NOT NULL DEFAULT 15`(10~240), `created_at`, `updated_at`. 행이 없으면 "설정 안 함"이고 GET은 OS 기본 포트와 15초를 기본값으로 돌려준다.
- `monitored_resources`: 모든 행 삭제, `snmp_index` 열 삭제. `resource_key`는 CPU `cpu`, Memory `memory`, Storage는 Linux mountpoint, Windows 드라이브(`C:`). 255자를 넘는 mountpoint는 목록에서 뺀다.
- `server_snmp_configs`: 삭제(`community_cipher` 포함).
- V9 문장 순서: `os` 추가 → `monitored_resources` 삭제 → `snmp_index` 삭제 → `server_snmp_configs` 삭제 → `server_exporter_configs` 생성. MariaDB DDL은 transaction에 묶이지 않으므로 모든 문장을 `IF [NOT] EXISTS`로 다시 실행해도 같게 쓴다.
- 수집 상태 `CollectionState`(서버 응답 `collectionState`):

| 값 | 조건 | 화면 문구 | 표시 |
|---|---|---|---|
| `NOT_CONFIGURED` | 수집 설정 행 없음 | 설정 안 함 | 빈 점 |
| `DISABLED` | 수집 설정이나 서버 감시가 꺼짐 | 수집 꺼짐 | 빈 점 |
| `COLLECTING` | `up{job="manager-os", server_id}` = 1 | 수집 중 | 정상 점 |
| `FAILED` | 켜져 있는데 `up`=0이거나 series 없음 | 수집 실패 | 경고 마름모(생존이 아니므로 위험 아이콘을 쓰지 않음) |
| `UNKNOWN` | Prometheus 조회 실패 | 알 수 없음 | 회색 점 |

- 서버 응답의 `status`(SNMP로 정하던 서버 상태)는 없앤다. 서버가 살아 있는지는 감시 항목이 보인다.

### 계약 변경

- HTTP SD(공개 API): `GET /api/prometheus/sd/snmp` 삭제, `GET /api/prometheus/sd/os-exporter` 추가. 수집과 서버 감시가 켜진 서버만 넣고, 고른 리소스가 있는지는 보지 않는다. label은 요구사항 C2(사람 승인)대로 `server_id`, `server_name`, `environment`, `os`, `__scrape_interval__`이다.

```json
[{"targets": ["10.0.0.5:9100"],
  "labels": {"server_id": "5", "server_name": "web01", "environment": "Production",
             "os": "LINUX", "__scrape_interval__": "30s"}}]
```

- `prometheus.yml`의 `manager-os` job: `metrics_path: /metrics`, `http_sd_configs`(refresh 30s), `sample_limit: 50000`(추측. windows_exporter의 service collector 크기를 보고 정함), `honor_labels`는 기본값(false)으로 두어 exporter가 대상 label을 위조하지 못하게 한다. `relabel_configs`는 없다.
- 공통 recording rule(ADR로 남김, 0004가 그대로 씀): `manager:resource_usage_percent`(0~100), `manager:resource_used_bytes`, `manager:resource_size_bytes`. label은 `server_id`, `resource_type`(CPU, MEMORY, STORAGE), `resource_key`뿐이다(`max by`로 끝냄).
- 화면용 API(이 저장소의 화면만 호출)

| 엔드포인트 | 변경 |
|---|---|
| `POST/PUT /api/servers` | 요청에 필수 `os` |
| `GET /api/servers`, `GET /api/servers/{id}` | `os`, `collectionState` 추가, `status`·`snmpState` 삭제 |
| `/api/servers/{id}/snmp`(GET, PUT, DELETE, `/test`) | 삭제 |
| `GET/PUT /api/servers/{id}/exporter` | 새 엔드포인트, 응답 `{configured, enabled, port, scrapeIntervalSeconds, defaultPort}`, 요청 `{enabled, port, scrapeIntervalSeconds}` |
| `POST /api/servers/{id}/exporter/test` | 새 엔드포인트(아래 연결 시험 목록) |
| `POST /api/servers/{id}/resources/discover` | 저장하고 켠 설정의 포트로 읽음, 응답에 `exporterType`·`version` 추가, 항목의 `snmpIndex` 삭제 |
| `PUT /api/servers/{id}/resources` | 항목에서 `snmpIndex` 삭제 |
| `GET /api/servers/{id}/resources` | 모양은 그대로, 값의 출처가 recording rule |

## 공통 recording rule

metric 이름 근거(2026-10-15 공식 저장소에서 확인한 것과 추측을 나눔):

| exporter | metric | 근거 |
|---|---|---|
| windows | `windows_cpu_time_total{core, mode}`(mode `idle` 등) | `docs/collector.cpu.md` 확인 |
| windows | `windows_memory_physical_total_bytes`, `windows_memory_available_bytes` | `docs/collector.memory.md` 확인 |
| windows | `windows_logical_disk_size_bytes{volume}`, `windows_logical_disk_free_bytes{volume}` | `docs/collector.logical_disk.md` 확인 |
| windows | `windows_exporter_build_info{version}` | `tools/e2e-output.txt`의 HELP 줄만 확인(값 줄 없음) |
| node | `node_cpu_seconds_total{cpu, mode}`, `node_memory_MemTotal_bytes` | `collector/fixtures/e2e-output.txt` 확인 |
| node | `node_exporter_build_info{version}` | 같은 표본의 HELP 줄만 확인 |
| node | `node_memory_MemAvailable_bytes` | **추측**(meminfo 이름 규칙), 표본으로 확인 |
| node | `node_filesystem_size_bytes`, `node_filesystem_avail_bytes`와 label `device`, `fstype`, `mountpoint` | **추측**(소스의 필드 이름만 확인), 표본으로 확인 |
| windows | v0.32.0의 memory metric 이름 변경 | 영향 미확인, 구현 전에 릴리스 변경 내역 확인 |

- CPU: `100 * (1 - avg by (server_id) (irate(<idle 카운터>[10m])))`. `irate`는 직전 두 수집 사이 평균이라 서버마다 다른 주기(10~240초)와 뜻이 맞는다(사람 확인: 2026-10-16). Windows도 idle 기준이고, 작업 관리자 값과 조금 다를 수 있음을 화면 안내에 쓴다.
- Memory: 크기 Total, 사용량 Total − Available(Linux는 cache·buffer 제외).
- Storage: 크기 size, 사용량 size − avail(ext4 예약 블록 때문에 `df`보다 조금 높을 수 있음). Linux 제외 fstype `tmpfs|ramfs|devtmpfs|overlay|squashfs|nsfs|autofs|fuse\..*` 등, Windows는 `^[A-Z]:$`만 받는다. 제외 목록은 규칙 파일과 Java `FilesystemFilter` 두 곳에 있으므로 Java 테스트가 규칙 파일을 읽어 같은지 확인한다.
- 화면은 `up{job="manager-os"}`과 위 세 규칙만 조회한다(PromQL은 `prometheus` 패키지에만).
- `promtool` 실행: `MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/infrastructure/prometheus/rules:/rules:ro" --entrypoint promtool <compose.yml의 Prometheus 이미지> test rules /rules/server-resources.test.yml`

## 연결 시험과 리소스 찾기

- 연결 시험 API: 요청 `{port}`(저장 전 값), 응답 `{success, outcome, httpStatus, exporterType, version, osMatches, message, responseTimeMs}`. `outcome`은 `OK`, `CONNECTION_REFUSED`, `TIMEOUT`, `HTTP_ERROR`, `UNREADABLE`, `NOT_OS_EXPORTER`, `UNKNOWN_HOST` 가운데 하나이고 본문은 돌려주지 않는다.
- 요청: `HttpClient`(리다이렉트 따르지 않음, 연결 3초, HTTP/1.1), URI는 `new URI("http", null, host, port, "/metrics", null, null)`, `GET`만, `Accept-Encoding: identity`.
- 한도: 헤더 5초, 전체 8초 마감, 본문 16 MiB, 한 줄 64 KiB(크기 값은 추측, 표본 측정으로 정함). 넘으면 `UNREADABLE`이나 `TIMEOUT`.
- 해석: 정한 family만 읽는다(build_info 둘, CPU 카운터 둘, Memory 크기 둘, 디스크 크기 둘). label escape 처리. 형식에 맞는 줄이 없으면 `UNREADABLE`, build_info가 없으면 `NOT_OS_EXPORTER`.
- 종류·버전은 build_info로 정하고, 고른 OS와 다르면 `osMatches:false`(저장은 막지 않음).
- 오류 문구는 분류와 확인할 것만 보이고, 예외 메시지·본문·헤더를 화면과 로그에 넣지 않는다.

## 화면(0005 화면 틀 기준)

- 서버 폼(`FormDialog`): 환경 옆에 OS 선택. 수정할 때 "OS를 바꾸면 포트 기본값과 리소스 찾기 방식이 바뀝니다" hint.
- 서버 상세(`DetailPage`): 탭 `resources`(리소스), `collection`(수집). 요약에 OS와 수집 상태. 메타에 마지막 수집 시각.
- 수집 절(`PageSection`): 방화벽 안내 `Notice`(Prometheus와 Monitoring Manager만 이 포트에 접근, OS에 맞는 설치 안내 링크), 체크박스·포트·수집 주기, `FormActions`의 [저장]과 보조 [연결 시험], 결과(성공은 `DetailList`, 실패·OS 불일치는 `Notice`).
- 리소스 절: 수집이 꺼져 있으면 [리소스 찾기] 비활성과 한 줄 안내, 빈 상태에 수집 탭으로 가는 동작. 탭 아래에서 같은 이름의 절 제목을 되풀이하지 않는다.
- 서버 목록: SNMP 열 대신 수집 열과 OS 열. SNMP를 말하는 안내는 두지 않는다.
- 대시보드: 서버 요약 문장을 수집 상태 기준으로.
- 0005 화면 판정에서 서버 상세에 남긴 지적(리소스 빈 상태의 중복 문단과 다음 동작 없음, 임계값 안내 위치, 탭과 같은 절 제목, 마지막 수집 시각)을 이 화면을 만들 때 함께 고친다.

## 위험과 되돌리기

- 배포 순서(PR 설명에만 적음, 사람 확인: 2026-10-15): Manager DB 백업 → `prometheus.yml`·규칙 반영과 Prometheus 재시작 → Manager 배포(V9) → `docker compose up -d --remove-orphans`, `docker volume rm snmp-runtime` → 대상 서버 snmpd 정리.
- migration은 되돌릴 수 없다(데이터 삭제). 되돌리려면 사람이 확인한 뒤 백업으로 DB를 복원하고 이전 이미지·설정을 되살린다. `APP_SECRET_KEY`를 지우므로 되돌릴 때는 이전 키 파일도 되살려야 백업의 `community_cipher`를 쓸 수 있다.
- MariaDB V9 부분 적용: `IF [NOT] EXISTS`로 다시 실행 가능하게 쓰고, 실패하면 `flyway repair` 뒤 다시 기동. C6에서 두 DB에 두 번씩 적용해 본다.
- 배포 직후 모든 서버가 "설정 안 함": 요구사항이 받아들인 동작이다.
- 수집을 켠 직후 잠시 "수집 실패": SD 갱신(30초)과 첫 수집 전에는 `up`이 없다. 저장 문구에 "1분 안에 수집을 시작합니다"를 쓴다.
- exporter 버전에 따른 metric 이름 변경: 규칙 테스트가 이름을 고정하고, 확인한 최소 버전을 화면 안내와 README에 적는다.
- SD label `server_name`, `os`가 원본 series에 남는다: 이름이나 OS를 바꾸면 원본 series가 새로 시작하지만, recording rule은 `server_id`로만 묶어 화면과 알림에는 영향이 없다.
- 0004(#14)와 충돌: 0004는 같은 규칙 파일에 Alert 규칙을 더한다. 0004를 다시 시작할 때 계획의 사용률 recording rule과 "계산식 두 곳" 위험, ADR 0003의 해당 문장을 고친다. migration 번호는 이 작업이 V9를 쓰고 0004는 그 다음을 쓴다.

### 보안 위협

- 바뀌는 신뢰 경계: 신뢰할 수 없는 입력 "장비의 SNMP 응답"이 "exporter의 `/metrics` 응답"으로 바뀐다. mountpoint와 volume 이름이 화면, `resource_key`, Prometheus label로 들어간다. Manager가 쓰는 파일이 없어진다. ARCHITECTURE.md의 신뢰 경계를 함께 고친다.
- 잘못될 수 있는 것과 막는 방법
  - 서버 쪽 요청 위조(CWE-918): 경로 `/metrics` 고정, `GET`만, 리다이렉트 따르지 않음, 본문·헤더를 돌려주지 않음, 크기·시간 한도. 테스트: 리다이렉트를 따르지 않음, 오류 응답에 본문 없음, 경로가 항상 `/metrics`.
  - 자원 고갈(CWE-400): 본문·줄 크기와 전체 시간 한도, Prometheus는 `sample_limit`. 테스트: 한도 초과, 느린 응답.
  - 입력 검증(CWE-20): 포트, 수집 주기(소수 거부), OS, 리소스 선택. 테스트: 경계값, 소수, 모르는 OS.
  - 로그 주입(CWE-117): exporter가 준 label 값을 로그에 넣지 않는다(리뷰).
  - 오류로 정보 노출(CWE-209): 예외 메시지를 응답에 넣지 않는다. 테스트: 실패 `message`가 정한 문구 가운데 하나.
  - 화면의 외부 문자열(CWE-79): React escape, `dangerouslySetInnerHTML` 없음(리뷰).
  - 평문 비밀값(CWE-312): `snmp-runtime` volume 삭제를 배포 절차에 쓴다. C8 코드 검색.
  - 대상 label 위조: `honor_labels` 기본값(리뷰).
- 받아들이는 위험(요구사항에서 사람이 2026-10-15에 정함): exporter가 인증 없이 평문 HTTP로 열림(CWE-319, CWE-306, #15에서 다룸). 연결 시험으로 내부 포트가 열렸는지 알 수 있음(SNMP 연결 시험과 같은 수준, 화면용 API는 `127.0.0.1`에만 묶임). 링크 로컬(169.254.0.0/16) 주소는 막지 않는다(사람 확인: 2026-10-16).

## 테스트 계획

Backend `cd backend && ./gradlew test`, Frontend `cd frontend && npm test`, 규칙은 위 `promtool` 명령. 기대 결과는 모두 통과다.

표본: Linux는 `os-test` 컨테이너의 실제 출력에서 이 계획이 읽는 family와 build_info만 남기고(호스트 정보 삭제), tmpfs·overlay 줄을 비교용으로 더한다. Windows는 공식 collector 문서의 이름과 label 모양으로 만든 최소 표본(`C:`, `D:`, `HarddiskVolume2`, build_info)이고, 머리 주석에 "실제 서버 출력으로 바꿔야 함"을 쓴다. 공식 표본을 옮기면 LICENSES.md에 기록한다.

| 완료 기준 | 테스트 | 확인 |
|---|---|---|
| C1 | `ServerTargetRequestTest`(os 누락·모르는 값), `ServerExporterRequestTest`(포트·주기 경계와 소수), `ServerExporterServiceTest`(설정 없으면 OS 기본값), 서버 폼·수집 절 컴포넌트 테스트 | `./gradlew test`, `npm test` |
| C2 | `OsExporterServiceDiscoveryServiceTest`: 켠 서버만, `host:port`, label 다섯 개 정확히, 꺼진 설정·서버·설정 없음 제외, 리소스를 고르지 않아도 포함 | `./gradlew test` |
| C3 | `ExporterMetricsParserTest`(종류·버전, HTML은 `UNREADABLE`, build_info 없음은 `NOT_OS_EXPORTER`, 긴 줄), `ExporterClientTest`(같은 프로세스의 JDK `HttpServer`: 302 미추종, 404, 느린 본문, 크기 초과, 닫힌 포트, 경로 고정), OS 불일치 | `./gradlew test`(TESTING.md에 같은 프로세스의 가짜 HTTP 서버 허용을 더함) |
| C4 | `ResourceDiscoveryServiceTest`(두 표본, 제외 대상, 255자 초과, selected), `FilesystemFilterRuleSyncTest` | `./gradlew test` |
| C5 | `server-resources.test.yml`(Linux·Windows CPU 같은 값→같은 결과, Memory available 기준, tmpfs·overlay·`HarddiskVolume2` 제외, 결과 label 셋뿐), `ResourceMetricQueryServiceTest` | `promtool`, `./gradlew test` |
| C6 | 수동: 이름이 다른 임시 MariaDB 11.4와 PostgreSQL 17에 V8까지 적용하고 데이터를 넣은 뒤 V9를 두 번 적용 | 결과를 진행 중 발견에 적음 |
| C7 | 수동: 아래 절차 | 결과를 진행 중 발견에 적음 |
| C8 | `git grep -n -i "snmp\|community\|APP_SECRET_KEY\|SecretEncryption"`의 남은 위치가 기록 문서(요구사항 0001·0006, 적용한 migration)뿐, 문서 검사(`--render`)와 전체 검증 통과 | 명령 결과 |
| C9 | `ServerTargetServiceTest`의 수집 상태 다섯 값, 수동 확인에서 exporter를 멈춰도 감시 항목 상태와 알림이 그대로 | `./gradlew test`, 수동 |

C7 절차(임시 compose project, Telegram은 `TELEGRAM_API_BASE_URL=http://127.0.0.1:1`로 막음):

1. `--profile local-db --profile os-test`로 띄운다(개발 DB와 겹치지 않는 project 이름).
2. 서버를 추가한다(호스트 `os-test-node-exporter`, OS Linux). [연결 시험]에서 node_exporter와 버전, OS를 Windows로 바꾸면 불일치 안내가 보이는지 본다.
3. 수집을 켜고 30초로 저장한다. 재시작 없이 1분 안에 Prometheus `/api/v1/targets`에 `manager-os` 대상, `scrapeInterval: 30s`, label 다섯 개가 보이는지 본다.
4. [리소스 찾기]에서 CPU, Memory, `/data`가 보이고 tmpfs가 없는지, 고르고 저장한 뒤 사용률이 보이는지 본다. `manager:resource_usage_percent`와 화면 값, 컨테이너 `/proc/meminfo`의 MemAvailable을 비교한다.
5. 수집 주기를 120초로 바꿔 재시작 없이 반영되는지 본다.
6. 이 서버 호스트를 대상으로 하는 ICMP 감시 항목을 만든 뒤 `docker stop os-test-node-exporter`를 하고, 수집 상태만 "수집 실패"가 되고 감시 항목은 그대로인지 본다(C9).
7. 화면 검사(`npm run test:screen`)를 실행한다.

`os-test` 컨테이너(안):

```yaml
os-test-node-exporter:
  image: quay.io/prometheus/node-exporter:v1.12.1   # Apache-2.0. 쓰기 전에 태그 확인
  container_name: os-test-node-exporter
  profiles: [os-test]
  # 컨테이너의 / 는 overlay라 기본 설정에서 빠진다. 디스크 목록을 보려고 volume을 /data에 붙인다.
  command: ["--collector.filesystem.mount-points-exclude=^/(dev|proc|run/credentials/.+|sys|etc|var/lib/docker/.+)($|/)"]
  volumes: [os-test-data:/data]
  networks: [monitoring]
```

## 문서 갱신

| 문서 | 고칠 것 |
|---|---|
| `docs/ARCHITECTURE.md` | 한눈에 보기, 실행 단위 표(snmp 둘 삭제, `os-test-node-exporter` 추가, manager-api 통신), `snmp-runtime` 문장, 감시 대상, 코드 지도(`snmp`→`exporter`, `common`의 `security` 삭제), 지켜야 할 경계(Manager가 쓰는 파일 없음, community 문장, `manager-os`, `/sd/os-exporter`, 공통 recording rule 계약, 생존은 감시 항목·자원은 exporter라는 역할 분담), 신뢰 경계, 공통 관심사의 비밀값 |
| `docs/assets/architecture-overview.svg` | SNMP 글자와 snmp_exporter 상자를 OS exporter로, `--render` 확인 |
| `docs/DEVELOPMENT.md` | SNMP 테스트 장비 명령을 `os-test` 명령으로, 규칙 테스트 명령, 비밀값 목록에서 `app_secret_key` |
| `docs/TESTING.md` | 규칙 테스트 종류, 단위 테스트의 가짜 HTTP 서버 허용, 점검 항목의 SNMP 예시 정리, exporter 응답 처리 점검 |
| `README.md` | "서버 SNMP 수집"을 "서버 자원 수집(OS exporter)"으로, exporter 설치와 방화벽 안내 링크 |
| `docs/PRODUCT.md` | 목적 문장, 영어로 쓰는 이름에서 SNMP·Community를 빼고 node_exporter·windows_exporter 추가 |
| `docs/TERMS.md` | OS exporter, 수집, 수집 설정, 수집 상태 문구 |
| `AGENTS.md` 프로젝트 고유 규칙 | 위험 영역의 SNMP 문구(아래 문구로 바꾸기로 함, 사람 확인: 2026-10-16) |

- AGENTS.md 문구: "`infrastructure/`의 Prometheus·Alertmanager·Blackbox·SNMP 설정"을 "Prometheus 설정과 규칙, Alertmanager·Blackbox 설정"으로, "SNMP community를 저장·전달·기록하는 방식"을 "exporter 접근 방식"으로 바꾼다.

## 작업 순서

- 순서: 요구사항 0005가 병합된 뒤 이 브랜치를 `main` 위로 옮겨 시작한다(사람 확인: 2026-10-15).
- [x] 계획 승인(2026-10-16). CPU `irate[10m]`, 링크 로컬 주소를 막지 않음, AGENTS.md 문구 변경을 함께 승인
- [ ] ADR 둘 초안(남길 결정). 번호는 0004, 0005(0003은 미병합 브랜치 `feature/resource-threshold-alert`가 씀)
- [ ] `os-test` 컨테이너 추가, 표본 두 개 만들고 "추측" metric 이름 확정. 다르면 계획을 고친다
- [ ] 규칙 테스트를 쓰고 실패 확인 → `server-resources.yml`(C5), TESTING.md·DEVELOPMENT.md 규칙 테스트
- [ ] C3·C4 테스트 실패 확인 → `exporter` 패키지의 클라이언트·파서·필터
- [ ] C1·C2·C9 테스트 실패 확인 → V9 migration(두 vendor), `ServerOs`, 수집 설정, SD, 수집 상태
- [ ] `ResourceMetricQueryService`, `ServerResourceService`, 리소스 찾기 교체
- [ ] SNMP·암호화 서비스·`APP_SECRET_KEY`·snmp4j·설정 키·Dockerfile `/runtime` 삭제(이 시점에 Backend 테스트 통과)
- [ ] `prometheus.yml` job 교체, `compose.yml`·`.env.example`·`infrastructure/snmp/`·`app_secret_key` 파일 정리
- [ ] Frontend 테스트 실패 확인 → api, 상태 표시, 서버 폼, 수집 절, 리소스 절, 상세, 목록, 대시보드
- [ ] 문서와 그림, `node scripts/check-docs.mjs --render`
- [ ] 수동 확인 C6, C7, 화면 검사, 전체 검증, C8 코드 검색
- [ ] 구현하지 않은 하위 에이전트의 리뷰(완료 기준, 이 계획, diff, CWE-918·400·20·117·209·79·312)
- [ ] PR 설명에 배포 절차를 쓴다. 요구사항 상태를 구현됨으로 바꾸고, ADR을 승인으로, #14에 0004가 고칠 점을 적고, 이 계획을 지움(병합 전 마지막 커밋)

## 진행 중 발견

- 계획은 2026-10-15에 하위 에이전트가 설계한 초안을 사람의 결정(생존은 Blackbox, 수집 상태 분리, SNMP 안내 문구 없음, 암호화 서비스와 `APP_SECRET_KEY` 삭제, 배포 절차는 PR 설명에만)으로 고쳐 썼다.
- 공식 e2e 표본에는 `*_build_info` 값 줄, `node_filesystem_*`, windows CPU·Memory·디스크 값이 없어 그대로 테스트 표본으로 쓸 수 없다.

## 남길 결정

- 서버 자원 사용률은 OS 공통 recording rule(이름 셋, label 셋, 단위, CPU idle·Memory available·Storage size−avail, 제외 목록)로 만들고 화면과 알림이 함께 읽는다. ADR 0003(미병합)의 "계산식은 `SnmpMetricQueryService`와 같다"를 대신한다.
- 서버 자원은 OS exporter로 수집하고, 수집 설정은 서버당 한 행의 `server_exporter_configs`에 두며, 연결 시험과 리소스 찾기는 Manager가 `/metrics`를 직접 한 번 읽는다(한도 포함). 서버 생존은 감시 항목이 맡고 서버 화면은 수집 상태만 보인다.
