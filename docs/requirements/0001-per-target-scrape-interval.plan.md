# 0001. 수집 대상마다 다른 수집 주기 구현 계획

- 요구사항: [0001. 수집 대상마다 다른 수집 주기](0001-per-target-scrape-interval.md)
- 상태: 진행 중

## 접근 방법

수집 주기를 Manager Database의 Check와 SNMP 설정에 저장하고, HTTP SD 응답의 entry마다 Prometheus 예약 label `__scrape_interval__`로 넘긴다. Prometheus는 SD가 준 `__scrape_interval__`이 있으면 job의 `scrape_interval` 대신 그 값으로 대상을 수집한다. SD 목록은 `refresh_interval`(Check 15초, SNMP 30초)마다 다시 가져오므로 재시작이나 reload 없이 반영된다. `__`로 시작하는 label은 relabel이 끝나면 지워지므로 time series의 label 집합은 바뀌지 않는다. SD label을 더하는 것은 사람이 2026-10-14에 승인했다.

`__scrape_timeout__`은 넣지 않는다. 허용하는 가장 짧은 주기(10초)가 전역 `scrape_timeout`(10초)과 같아서, Prometheus의 "timeout은 주기보다 길 수 없다" 조건을 항상 만족한다.

검토했지만 고르지 않은 방법은 다음과 같다.

- 주기마다 job을 나눠 `prometheus.yml`에 적는 방법: 허용 값마다 job이 생기고, 사용자가 화면에서 고른 값 때문에 `infrastructure/` 파일을 고쳐야 하며 reload도 필요하다. 지켜야 할 경계를 어긴다.
- SD endpoint를 주기마다 나누는 방법(`/sd/blackbox?interval=30s`): 결국 job이 주기마다 필요해서 위와 같다.
- relabel로 다른 label에서 `__scrape_interval__`을 만드는 방법: SD에 일반 label을 하나 더 넣어야 하고 그 label이 time series에 남거나 따로 지워야 한다. 예약 label을 바로 주는 것보다 이점이 없다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/{mariadb,postgresql}/V7__scrape_interval.sql` | `monitoring_checks`, `server_snmp_configs`에 `scrape_interval_seconds` 열 추가 |
| `backend/.../check/MonitoringCheck.java`, `MonitoringCheckRequest.java`, `MonitoringCheckResponse.java`, `MonitoringCheckService.java` | 수집 주기 필드, 검증, 응답, 기본값과 수정 시 유지 |
| `backend/.../snmp/ServerSnmpConfig.java`, `ServerSnmpRequest.java`, `ServerSnmpResponse.java`, `ServerSnmpConfigStore.java` | 같은 내용 |
| `backend/.../prometheus/BlackboxServiceDiscoveryService.java`, `SnmpServiceDiscoveryService.java` | SD entry에 `__scrape_interval__` 추가 |
| `backend/build.gradle` | 테스트 의존성(`spring-boot-starter-test`, `junit-platform-launcher`), test 작업의 시간대·로케일 |
| `backend/src/test/java/...` | 새 단위 테스트 |
| `frontend/package.json`, `package-lock.json`, `vitest.config.ts` | Vitest, Testing Library, jsdom 개발 의존성과 `test` 스크립트, `check`에 test 추가 |
| `frontend/src/lib/api.ts` | `MonitoringCheck`, `MonitoringCheckInput`, `ServerSnmp`, `ServerSnmpInput`에 `scrapeIntervalSeconds` |
| `frontend/src/components/checks/CheckFormDialog.tsx`, `servers/ServerSnmpSection.tsx` | 수집 주기 입력 칸과 칸별 오류 |
| `frontend/src/pages/CheckDetailPage.tsx` | Check 상세에 수집 주기 표시 |
| `frontend/src/**/*.test.tsx` | 새 컴포넌트 테스트 |
| `infrastructure/prometheus/prometheus.yml` | 두 job의 실제 주기를 SD의 `__scrape_interval__`이 정한다는 주석만 더함 |
| `docs/ARCHITECTURE.md` | 지켜야 할 경계에 수집 주기 전달 방식 한 줄 |
| `docs/TESTING.md`, `docs/DEVELOPMENT.md` | 테스트 종류 표, 위치와 이름 규칙, 시간대 설정, 명령 표의 테스트 명령 |

### 데이터 모델 변경

- `monitoring_checks.scrape_interval_seconds INT NOT NULL DEFAULT 15`를 더한다. 기존 행은 15가 된다.
- `server_snmp_configs.scrape_interval_seconds INT NOT NULL DEFAULT 15`를 더한다. 기존 행은 15가 된다.
- 허용 범위 10~240은 요청 DTO의 Bean Validation(`@Min(10)`, `@Max(240)`)으로 검증한다. DB 제약은 두지 않는다. 기존 열들도 범위를 DTO에서만 검증한다.
- Entity: 생성자에 `null`이 오면 15, `update`에 `null`이 오면 기존 값을 유지한다(요구사항 S6).

### 계약 변경

- 화면용 API(이 저장소의 화면만 호출)
  - `POST /api/checks`, `PUT /api/checks/{id}` 요청에 선택 필드 `scrapeIntervalSeconds`(정수, 10~240)를 더한다. 범위 밖이면 400과 `fieldErrors.scrapeIntervalSeconds`
  - `GET /api/checks`, `GET /api/checks/{id}` 응답에 `scrapeIntervalSeconds`를 더한다.
  - `PUT /api/servers/{id}/snmp` 요청과 `GET` 응답에 같은 필드를 더한다. 설정이 없으면 응답 값은 `null`이다.
- HTTP SD(Prometheus가 호출, 공개 API)
  - `/api/prometheus/sd/blackbox`, `/api/prometheus/sd/snmp`의 entry마다 `"__scrape_interval__": "{초}s"`를 더한다. 기존 label과 `targets`는 그대로다.

전:

```json
{"targets": ["10.0.0.1"], "labels": {"check_id": "3", "check_target_id": "7", "...": "..."}}
```

후:

```json
{"targets": ["10.0.0.1"], "labels": {"check_id": "3", "check_target_id": "7", "...": "...", "__scrape_interval__": "30s"}}
```

## 위험과 되돌리기

- Prometheus가 `__scrape_interval__`을 받지 않는 경우: v3.14.0 문서와 실제 `/api/v1/targets`의 `scrapeInterval`로 확인한다(C9). 받지 않으면 구현을 멈추고 묻는다.
- 주기가 길수록 Alert가 늦게 온다(최대 240초 + `for: 1m`): 요구사항의 하지 않는 것에 적었다. 화면 안내 문구에도 쓴다.
- Prometheus 조회 기본 lookback(5분)보다 긴 주기를 받으면 값이 비는 구간이 생긴다: 상한을 240초로 둬서 막는다.
- JSON의 소수(예: `15.5`)를 Jackson이 정수로 잘라 받을 수 있다: 화면은 정수만 보내도록 검증한다. API에서 실제로 잘라 받는지는 구현 중에 확인하고 진행 중 발견에 쓴다. 전역 Jackson 설정은 이번 범위가 아니다.
- migration 되돌리기: 새 열만 더하므로 기존 데이터는 바뀌지 않는다. 배포를 되돌릴 때는 이전 버전 애플리케이션이 새 열을 무시하므로 열을 그대로 둬도 동작한다(`ddl-auto: validate`는 Entity에 없는 열을 문제 삼지 않는다). Flyway 이력을 되돌려야 하면 사람이 확인한 뒤 열을 지우고 `flyway_schema_history`의 V7 행을 지운다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 없음. 화면용 API에 정수 필드 하나가 들어오고, 그 값이 SD 응답을 거쳐 Prometheus로 간다.
- 잘못될 수 있는 것과 막는 방법
  - 범위를 벗어난 주기(CWE-20): 1초 같은 값이 들어가면 대상과 exporter에 부하가 생기고, 큰 값은 감시 공백을 만든다. → 서버에서 `@Min(10)`, `@Max(240)`으로 거부한다. 화면 검증은 보조다.
  - SD label 값 주입(CWE-20): label 값은 서버가 정수에서 만든 문자열(`"{n}s"`)뿐이라 사용자 문자열이 들어가지 않는다.
- 받아들이는 위험: 없음

## 테스트 계획

Backend 테스트는 `cd backend && ./gradlew test`, Frontend 테스트는 `cd frontend && npm test`로 실행한다. 기대 결과는 모두 통과다. 범위 밖 값의 필드 오류를 ProblemDetail로 바꾸는 일은 기존 `ApiExceptionHandler`가 맡으므로, C6은 Validator 단위 테스트로 확인한다.

| 완료 기준 | 테스트 | 확인 |
|---|---|---|
| C1 | `MonitoringCheckResponseTest`: 응답에 Check의 수집 주기가 들어 있다 | `./gradlew test` |
| C2 | `ServerSnmpResponseTest`: 응답에 SNMP 설정의 수집 주기가 들어 있다 | `./gradlew test` |
| C3 | `BlackboxServiceDiscoveryServiceTest`: entry마다 `__scrape_interval__`이 Check의 주기로 들어 있고 기존 label과 targets가 그대로다 | `./gradlew test` |
| C4 | `SnmpServiceDiscoveryServiceTest`: 같은 내용을 SNMP로 | `./gradlew test` |
| C5 | 수동: MariaDB(`local-db` profile)와 임시 PostgreSQL 컨테이너에 기존 데이터를 둔 채 V7을 적용하고 두 열이 15인지 조회 | 결과를 보고에 적는다 |
| C6 | `MonitoringCheckRequestTest`, `ServerSnmpRequestTest`: Validator로 9, 241은 위반, 10, 240은 통과 | `./gradlew test` |
| C7 | `MonitoringCheckTest`, `ServerSnmpConfigTest`: 생성할 때 `null`이면 15, 수정할 때 `null`이면 기존 값 | `./gradlew test` |
| C8 | `CheckFormDialog.test.tsx`, `ServerSnmpSection.test.tsx`, `CheckDetailPage.test.tsx`: 요청에 주기를 담음, 범위 밖 오류 표시, 저장한 주기 표시 | `npm test` |
| C9 | 수동: `docker compose --profile local-db up -d --build`로 띄우고 화면에서 주기를 30초로 바꾼 뒤, 재시작 없이 `/api/v1/targets`의 `scrapeInterval`이 `30s`인지, `probe_success`의 label 집합이 그대로인지 확인 | 결과를 보고에 적는다 |

## 작업 순서

- [ ] 테스트 도구 도입: Backend `build.gradle` 테스트 의존성과 시간대, Frontend Vitest 설정과 `test`·`check` 스크립트. 의존성의 레지스트리 존재, 설치 스크립트, 라이선스 확인. TESTING.md와 DEVELOPMENT.md에 테스트 종류, 위치와 이름, 명령을 더함
- [ ] Backend 완료 기준 테스트 작성(C1~C4, C6, C7)과 실패 확인
- [ ] migration V7(두 vendor)과 Entity, DTO, 서비스 구현
- [ ] SD 응답에 `__scrape_interval__` 추가
- [ ] Frontend 컴포넌트 테스트 작성(C8)과 실패 확인
- [ ] Frontend 타입, 폼 입력 칸, Check 상세 표시 구현
- [ ] `prometheus.yml` 주석과 ARCHITECTURE.md 갱신
- [ ] 수동 확인(C5, C9), 전체 검증, 새 세션이나 하위 에이전트의 리뷰
- [ ] 남길 결정을 ADR로 옮기고 이 계획을 지움(병합 전 마지막 커밋)

## 진행 중 발견

없음

## 남길 결정

- 대상별 수집 주기는 Manager Database에 저장하고 HTTP SD의 `__scrape_interval__`로 Prometheus에 전한다. 허용 범위는 10~240초이고 `__scrape_timeout__`은 쓰지 않는다.
