# 0026. 장애와 복구를 1분 안에 전하는 알림 전송 간격 구현 계획

- 요구사항: [0026. 장애와 복구를 1분 안에 전하는 알림 전송 간격](0026-alert-interval-1m.md)
- 상태: 진행 중

## 접근 방법

`infrastructure/alertmanager/alertmanager.yml`의 고정값 두 개만 바꾼다. `group_interval`은 `5m`에서 `1m`으로, `repeat_interval`은 `4m`에서 `40s`로 바꾸고, `group_wait: 30s`는 그대로 둔다. `repeat_interval`을 `group_interval`보다 짧게 두는 이유는 ADR 0001과 같다. Alertmanager는 묶음을 다시 살필 때마다 마지막 전송 뒤 `repeat_interval`이 지났는지 보는데, 두 값이 같으면 경과 시간이 조금 모자라 한 번씩 건너뛴다. Alertmanager는 지난 전송이 끝난 시각부터 경과 시간을 세므로, 관리자 서버의 응답 시간이 `group_interval - repeat_interval`보다 짧아야 건너뛰지 않는다. 요청서의 50초는 10초밖에 견디지 못해, 40초(20초 견딤)로 줄이고 통합 계약에 응답 시간 조건을 적는다(진행 중 발견).

검토했지만 고르지 않은 방법:

- **`repeat_interval: 50s`(요청서 값)**: 응답 지연을 10초밖에 견디지 못한다.
- **`repeat_interval: 30s` 이하**: 전송 기록의 만료(`repeat_interval`의 2배)가 `group_interval` 이하가 되어 복구 webhook을 잃을 수 있다.
- **`group_interval`을 `.env`로 엶**: 요구사항 0013이 막았고, 관리자 서버의 재알림 하한과의 관계를 서버마다 깰 수 있다.
- **빌드 검사에 두 값의 관계 검사를 더함**: 요청에 없고, ADR 0001이 리뷰에서 확인하기로 했다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/alertmanager/alertmanager.yml` | `group_interval: 5m` → `1m`, `repeat_interval: 4m` → `40s`, 두 값의 주석 |
| `docs/INTEGRATION.md` | API 표의 "5분마다", 같은 장애가 다시 올 때의 설명, 묶음 전송 설명, 같은 감시 항목의 새 장애(최대 5분 뒤), 복구 webhook 시간(5분 안, 10분 안) |
| `docs/OPERATIONS.md` | 감시 흐름의 "5분마다", 장애 기준과 복구 시점 |
| `docs/ARCHITECTURE.md` | 지켜야 할 경계의 `group_interval`(5분), `repeat_interval`(4분) 문장 |
| `docs/decisions/0017-alert-interval-1m.md` | ADR 0001, 0010의 문장 일부를 대신하는 새 ADR |

### 데이터 모델 변경

해당 없음

### 계약 변경

- webhook 요청 형식은 그대로다. 이어지는 장애를 보내는 간격이 5분에서 1분으로, 복구와 같은 감시 항목의 새 장애가 오기까지의 최대 시간이 5분에서 1분으로 준다. 확인 결과가 끊긴 장애의 복구 webhook은 10분 안에서 6분 안으로 준다.

## 위험과 되돌리기

- 관리자 서버의 webhook 처리량이 5배가 된다: 관리자 서버 쪽 사람이 감당한다고 확인했다(2026-11-25). 문제가 생기면 이전 버전으로 되돌린다.
- 되돌리기: 운영 가이드의 이전 버전으로 되돌리기를 따른다. 관리자 서버와 배포 순서는 상관없다. Alertmanager 설정은 펼친 파일로 붙고, 운영 명령이 설정 해시가 바뀐 것을 보고 컨테이너를 다시 만든다.

### 보안 위협

해당 없음. 인증·권한, 개인정보, 외부 입력, 의존성을 건드리지 않는다.

## 테스트 계획

- **C1(빌드 검사)**: `node scripts/build-monitoring-bundle.mjs --check`가 통과한다(Alertmanager 설정 검사 `amtool check-config` 포함). diff에서 세 값과 주석을 리뷰한다.
- **C2(개발 환경 수동 확인)**: 빌드 PC에서 Node로 가짜 관리자 서버를 띄운다. 주소 감시 대상 목록(HTTP SD)과 webhook만 받고, webhook마다 받은 시각, `status`, 주소 id만 기록한다. 요청 헤더와 token은 기록하지 않는다. 다음 순서로 본다.
  1. `MONITORING_APP_ADDRESS=host.docker.internal:<포트>`를 셸 환경 변수로 주고 `monitoring-dev.bat start`로 띄운다. `.env`는 고치지 않는다.
  2. 감시 항목 하나에 닿지 않는 주소 하나를 15초 주기로 내준다. 첫 webhook이 장애 판정 30초 뒤에 오고, 그 뒤로 1분 간격으로 5번 넘게 오는지 본다.
  3. 같은 감시 항목에 닿지 않는 주소 하나를 더 내준다. 새 주소의 장애가 판정 뒤 1분 안에 같은 요청에 들어오는지 본다.
  4. 첫 주소를 닿는 주소(Prometheus 준비 확인 주소)로 바꾼다. 복구 판정 뒤 1분 안에 그 주소의 복구가 오는지 본다.
  - 판정 시각은 Prometheus의 `ALERTS_FOR_STATE`와 Alertmanager API(`/api/v2/alerts`)로 본다. 확인 뒤 모니터링 시스템과 가짜 관리자 서버를 끈다.
- **C3, C4(문서 리뷰)**: 바뀌는 부분의 문서를 읽고, 남은 "5분마다", "4분", "5분 안", "10분 안"이 전송 간격을 뜻하지 않는지 `grep`으로 확인한다.
- **C5(전체 검증)**: `node scripts/build-monitoring-bundle.mjs --check`와 `node scripts/check-docs.mjs`가 통과한다.

## 작업 순서

- [x] `alertmanager.yml`의 두 값과 주석을 바꾸고 C1을 확인한다
- [x] 개발 환경에서 C2를 확인하고, 본 시각을 진행 중 발견에 적는다
- [x] INTEGRATION.md, OPERATIONS.md, ARCHITECTURE.md를 고친다
- [x] 구현에서 바뀐 점이 있으면 ADR 0017(계획과 함께 씀)에 반영하고 상태를 승인으로 바꾼다
- [ ] 전체 검증, 자체 리뷰, 새 세션 리뷰

## 진행 중 발견

- 2026-11-25 개발 환경 확인(C2). 가짜 관리자 서버를 빌드 PC의 `127.0.0.1:18080`에 띄우고 `MONITORING_APP_ADDRESS=host.docker.internal:18080`으로 붙였다. 가짜 서버의 token 파일은 worktree에 새로 만든 시험용 값이고 기록하지 않았다. 시각은 UTC다.
  - Alertmanager API(`/api/v2/status`)의 설정이 `group_wait: 30s`, `group_interval: 1m`, `repeat_interval: 50s`였다.
  - 주소 11의 장애 판정(`ALERTS_FOR_STATE`) 00:31:01, 첫 webhook 00:31:31(30초 뒤), 그 뒤 00:32:31부터 00:37:31까지 1분 간격으로 빠짐없이 왔다.
  - 같은 감시 항목에 주소 12를 더하자 판정 00:38:16, webhook 00:38:31에 주소 11과 함께 왔다(1분 안).
  - 주소 11을 닿는 주소로 바꾸자 복구 판정 00:39:16, 복구 webhook 00:39:31에 왔다(1분 안). 같은 요청에 주소 12의 장애가 함께 왔다.
- 독립 리뷰(2026-11-25, Alertmanager v0.34.1 소스 기준): Alertmanager는 다시 보낼지를 묶음 타이머 시각과 마지막 전송이 끝난 시각의 차이로 판단한다. 그래서 관리자 서버의 webhook 응답과 재시도가 합쳐 약 10초(1분 − 50초)를 넘으면 다음 판단에서 한 번 건너뛰고, 이어지는 장애가 2분마다 간다. 복구와 새 장애는 영향을 받지 않는다. 개발 환경 확인의 가짜 서버는 바로 응답해 이 조건을 보지 못했다. 사람이 40초로 줄이고 통합 계약에 "webhook은 20초 안에 응답"을 적기로 정했다(2026-11-25). 40초로 바꾼 뒤의 개발 환경 확인은 다시 하지 않았다. 가짜 서버가 바로 응답하므로 1분 간격은 50초 때와 같다.
- 같은 리뷰에서 복구 시간의 기준 시점과 규칙 평가 간격(15초)이 빠진 문장을 고쳤다(확인 결과가 끊긴 장애의 복구는 6분 15초 안).
- 다른 문서에서 5분 전송 간격을 전제로 쓴 곳은 `grep`으로 찾았고, ADMIN-SERVER.md, PRODUCT.md, TERMS.md, README.md에는 없었다.

## 남길 결정

- Alertmanager가 묶음을 다시 살피는 간격을 1분, 다시 보내는 간격을 40초로 고정하고, 관리자 서버의 webhook 응답 시간 조건(20초)을 통합 계약에 둔다. 관리자 서버의 재알림 하한 5분과의 관계는 ADR 0001대로 지킨다(ADR 0017).
