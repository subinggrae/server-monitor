# 0001. 수집 대상마다 다른 수집 주기

- 상태: 구현됨
- 작성일: 2026-10-14
- 관련: [ADR 0002](../decisions/0002-scrape-interval-via-http-sd.md)

## 배경

지금은 모든 수집 대상을 같은 주기로 수집한다. Prometheus 설정 파일(`infrastructure/prometheus/prometheus.yml`)의 전역 수집 주기 15초 하나를 Check와 SNMP 서버가 함께 쓴다. 중요한 서비스는 더 자주, 부담을 줄여야 하는 장비는 더 드물게 수집하고 싶어도 대상마다 다르게 정할 수 없다.

설정 파일에 대상별 주기를 적으면 Prometheus를 다시 띄우거나 설정을 다시 읽혀야 하고, 사용자가 화면에서 바꾼 설정 때문에 `infrastructure/`의 파일을 고치지 않는다는 경계([ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계))도 어긴다.

## 목표

- 사용자가 화면에서 Blackbox Check마다, SNMP 서버마다 수집 주기를 정한다. Check 하나의 확인 주소들은 같은 주기로 수집한다.
- 정한 주기는 Manager Database에 저장되고, Prometheus나 다른 실행 단위를 다시 띄우지 않아도 반영된다.
- 주기를 정하지 않은 대상은 지금과 같은 15초로 수집한다.

## 하지 않는 것

- `infrastructure/`의 Prometheus 설정 파일과 Alert 규칙을 대상마다 고치거나 만드는 것
- Blackbox probe의 timeout을 대상마다 다르게 하는 것. timeout은 Blackbox module 단위 설정이다.
- Alert 규칙의 판단 시간(`for: 1m`)을 대상의 주기에 맞춰 바꾸는 것. 그래서 주기가 길수록 Alert가 늦게 온다(최대 수집 주기 + 1분).
- 수집 주기가 아닌 다른 수집 설정(timeout, module 등)을 화면에서 바꾸는 것

## 사용자 경험

1. 사용자가 Check를 등록하거나 고치는 화면에서 수집 주기를 초 단위로 입력한다. 처음 값은 15초이고, 10초에서 240초(4분)까지 입력할 수 있다는 안내가 보인다.
2. SNMP 서버의 SNMP 설정 화면에서도 같은 방식으로 수집 주기를 입력한다.
3. 범위 밖 값을 입력하고 저장하면 입력 칸 아래에 허용 범위를 알려 주는 오류가 보인다.
4. 저장하면 화면에 저장한 주기가 보이고, Prometheus가 대상 목록을 다시 가져온 뒤(Check 15초, SNMP 30초마다) 새 주기로 수집이 시작된다.
5. 목록이나 상세 화면에서 대상마다 정한 주기를 확인한다.

## 동작

### 정상 동작

- **S1. Check의 수집 주기 정하기**
  - 상황: 사용자가 Check를 등록하거나 고친다.
  - 행동: 10초에서 240초 사이의 정수 초로 수집 주기를 정하고 저장한다.
  - 결과: 저장한 주기가 Manager Database에 남고, Prometheus가 다음 대상 목록 갱신부터 그 Check의 모든 확인 주소를 그 주기로 수집한다.
- **S2. SNMP 서버의 수집 주기 정하기**
  - 상황: 사용자가 서버의 SNMP 설정을 등록하거나 고친다.
  - 행동: 10초에서 240초 사이의 정수 초로 수집 주기를 정하고 저장한다.
  - 결과: 저장한 주기가 Manager Database에 남고, Prometheus가 다음 대상 목록 갱신부터 그 서버를 그 주기로 수집한다.
- **S3. 기존 대상**
  - 상황: 이 기능 전에 등록한 Check와 SNMP 설정이 있다.
  - 행동: 기능을 배포한다.
  - 결과: 기존 대상은 15초로 수집되고, 화면에도 15초로 보인다.
- **S4. 재시작 없이 반영**
  - 상황: Prometheus가 실행 중이다.
  - 행동: 사용자가 화면에서 주기를 바꾼다.
  - 결과: Prometheus를 다시 띄우거나 설정을 다시 읽히지 않아도 새 주기로 수집한다. 수집 대상의 time series는 끊기지 않는다.

### 예외 동작

- **S5. 허용하지 않는 주기**
  - 상황: 수집 주기에 10초보다 짧거나 240초보다 긴 값, 정수가 아닌 값을 넣는다.
  - 행동: 화면이나 API로 Check나 SNMP 설정 저장 요청을 보낸다.
  - 결과: 저장을 거부하고 수집 주기 입력 칸 아래에 허용 범위를 알려 주는 오류를 보인다. 기존 설정은 바뀌지 않는다.
- **S6. 주기를 보내지 않은 요청**
  - 상황: 수집 주기 값이 없는 저장 요청이 들어온다.
  - 행동: Check나 SNMP 설정 저장 요청을 보낸다.
  - 결과: 등록이면 15초로 저장하고, 수정이면 기존 주기를 그대로 둔다.

### 보안

- 접근 권한: 기존 Check와 SNMP 설정 화면과 같다. 화면용 API에는 인증이 없고 포트를 `127.0.0.1`에만 묶는다.
- 외부 입력: 화면용 API로 들어오는 수집 주기 값. 10초에서 240초 사이의 정수만 받는다.
- 민감한 정보: 없음
- 남길 보안 이벤트: 없음

### 바뀌지 않는 것

- HTTP SD 응답의 기존 label(`check_id`, `check_target_id`, `check_type`, `module`, `environment`, `check_name`, `server_id`, `server_name`, `snmp_auth`, `snmp_modules`)과 대상 주소. 이미 쌓인 time series가 끊기지 않는다.
- Alert 규칙과 Alertmanager로 가는 label
- `blackbox-exporter`, `manager-api` job의 수집 주기(15초)
- SNMP community를 SD 응답과 Prometheus label에 넣지 않는 것

## 완료 기준

- [ ] C1. Check를 저장하면 정한 수집 주기가 저장되고, Check 조회 API 응답에 그 주기가 들어 있다. (S1) — 확인: Check 응답 단위 테스트
- [ ] C2. SNMP 설정을 저장하면 정한 수집 주기가 저장되고, SNMP 설정 조회 API 응답에 그 주기가 들어 있다. (S2) — 확인: SNMP 설정 응답 단위 테스트
- [ ] C3. Blackbox SD 응답의 entry마다 그 Check의 수집 주기가 Prometheus가 읽는 수집 주기 label로 들어 있고, 기존 label과 대상 주소는 그대로다. (S1, S4) — 확인: `BlackboxServiceDiscoveryService` 테스트
- [ ] C4. SNMP SD 응답의 entry마다 그 서버의 수집 주기가 같은 방식으로 들어 있고, 기존 label과 대상 주소는 그대로다. (S2, S4) — 확인: `SnmpServiceDiscoveryService` 테스트
- [ ] C5. 기존 Check와 SNMP 설정은 migration 뒤 15초다. MariaDB와 PostgreSQL에서 같다. (S3) — 확인: 두 DB에 migration을 적용하고 수동 조회
- [ ] C6. 허용하지 않는 주기를 보내면 400과 수집 주기 필드 오류를 돌려주고 저장된 값이 바뀌지 않는다. (S5) — 확인: 요청 검증 단위 테스트
- [ ] C7. 주기가 없는 등록 요청은 15초로, 수정 요청은 기존 값으로 저장된다. (S6) — 확인: Entity 단위 테스트
- [ ] C8. 화면에서 Check와 SNMP 설정의 수집 주기를 입력하고 저장할 수 있고, 범위 밖 값이면 입력 칸 아래에 오류가 보이고, 저장한 주기가 보인다. (S1, S2, S5) — 확인: 컴포넌트 테스트
- [ ] C9. 실행 중인 Prometheus에서 화면으로 주기를 바꾸면, 다시 띄우지 않고 Prometheus의 대상 목록(`/api/v1/targets`)에 새 주기가 보이고 기존 time series가 이어진다. (S4) — 확인: `docker compose`로 띄운 환경에서 수동 확인

## 확인 필요

없음
