# 0026. 장애와 복구를 1분 안에 전하는 알림 전송 간격

- 상태: 구현됨
- 작성일: 2026-11-25
- 관련: [0003](0003-alert-repeat-interval.md), [0013](0013-core-env-settings.md), [0024](0024-consecutive-failure-down.md), [ADR 0001](../decisions/0001-alert-repeat-in-manager.md), [ADR 0010](../decisions/0010-alert-labels-by-check-id.md), [INTEGRATION.md](../INTEGRATION.md)

## 배경

Alertmanager는 감시 항목(`alertname`, `check_id`)마다 Alert를 묶어 관리자 서버의 webhook으로 보낸다. 묶음이 처음 생기면 30초(`group_wait`) 기다렸다 보내고, 그 뒤로는 묶음을 다시 살피는 간격(`group_interval`, 5분)마다 바뀐 것을 보낸다. 그래서 다음 둘이 최대 5분 늦는다.

- 복구: 확인이 성공해 Prometheus가 복구로 판정해도, 복구 Alert는 다음 묶음 전송 때 간다. 관리자 화면에 장애가 최대 5분 남는다.
- 같은 감시 항목의 새 장애: 다른 주소가 이미 장애인 감시 항목에 새 장애가 생기면, 30초가 아니라 다음 묶음 전송 때 간다.

관리자 서버 쪽 사람이 묶음을 다시 살피는 간격을 1분으로 줄이기로 정했다(사람 확인: 2026-11-25, 관리자 서버 Testigo_Mng). 이어지는 Alert를 다시 보내는 간격(`repeat_interval`)은 `group_interval`보다 짧게 두어야 한다. 같게 두면 다음 시점에 경과 시간이 조금 모자라 한 번씩 건너뛴다([ADR 0001](../decisions/0001-alert-repeat-in-manager.md)). 관리자 서버의 알림 다시 보내는 간격 하한(5분)은 `group_interval`보다 짧으면 안 되는데, 1분으로 줄이면 이 관계는 그대로 지켜진다.

요청서는 `repeat_interval`을 50초로 정했다. 구현 리뷰에서, Alertmanager가 지난 전송이 끝난 시각(관리자 서버의 응답 뒤)부터 경과 시간을 세므로 응답이 10초를 넘으면 한 번씩 건너뛴다는 것을 찾았다. 그래서 40초로 줄이고, 관리자 서버가 webhook에 20초 안에 응답한다는 조건을 통합 계약에 적기로 했다(사람 확인: 2026-11-25, 이 저장소). 30초 이하는 전송 기록이 `group_interval`보다 먼저 만료돼 복구를 보내지 못할 수 있어 쓰지 않는다.

1분 간격은 아래 문서의 문장과 부딪친다. 이 요구사항과 그 ADR은 아래 문장만 대신하고, 이전 문서는 고치지 않는다.

- [ADR 0001](../decisions/0001-alert-repeat-in-manager.md)의 결정: "`repeat_interval`을 ... `group_interval`(5분)보다 짧은 4분으로 고정해, Alertmanager가 이어지는 Alert를 5분마다 webhook으로 보내게 한다", 감수할 점의 "이어지는 Alert마다 5분에 한 번 webhook과 DB 쓰기가 생긴다", "실제 재발송은 주기보다 최대 5분 늦거나 1분 이르다", "webhook마다(5분) 다시 시도". Alertmanager는 짧은 간격으로 계속 보내고 관리자 서버가 감시 항목별 간격으로 판단한다는 결정과 `repeat_interval` < `group_interval` ≤ 재알림 하한이라는 관계는 그대로다.
- [0003](0003-alert-repeat-interval.md)의 사용자 경험: "하한 5분은 Alertmanager가 같은 묶음을 다시 보내는 간격(`group_interval: 5m`)이다", "정한 주기보다 최대 5분 늦을 수 있다", S3의 "5분마다 쌓여". 하한 5분과 1분 허용 오차는 그대로다.
- [ADR 0010](../decisions/0010-alert-labels-by-check-id.md)의 감수할 점: 지우거나 끈 장애의 복구 webhook이 "5~10분 뒤에 오고", 정상으로 돌아온 대상의 "복구 webhook은 5분 안에 온다". 복구 판정 시점은 그대로다.
- [0024](0024-consecutive-failure-down.md)의 S10과 바뀌지 않는 것: "5분마다 다시 보낸다"

[0013](0013-core-env-settings.md)이 정한 대로 이 값들은 `.env`로 열지 않고 고정값만 바꾼다.

## 목표

- 이어지는 장애를 관리자 서버에 1분마다 다시 보낸다.
- 복구로 판정한 뒤 1분 안에 관리자 서버에 복구를 보낸다.
- 같은 감시 항목에 장애가 이미 있을 때 새로 생긴 주소의 장애를 1분 안에 함께 보낸다.
- 처음 생긴 장애는 지금처럼 30초 기다렸다 보낸다.
- 관리자 서버를 만드는 개발자가 [INTEGRATION.md](../INTEGRATION.md)에서 바뀐 전송 간격과 그에 따른 시간(복구 webhook이 오기까지, 재알림이 정한 간격보다 이를 수 있는 폭)을 찾는다.

## 하지 않는 것

- 처음 기다리는 시간(`group_wait`, 30초)과 묶는 기준(`group_by`)을 바꾸는 것
- 관리자 서버의 알림 다시 보내는 간격 하한(5분)과 허용 오차(1분)를 바꾸는 것(사람 확인: 2026-11-25)
- 전송 간격을 모니터링 서버의 `.env`에서 바꾸게 여는 것([0013](0013-core-env-settings.md))
- Alert 규칙과 장애·복구 판정 시점을 바꾸는 것. 지우거나 끈 대상은 지금처럼 마지막 확인 결과가 5분 지난 뒤 복구로 판정한다.
- 관리자 서버 저장소(Testigo_Mng)를 고치는 것

## 사용자 경험

관리자 서버를 만드는 개발자, 모니터링 시스템을 설치하는 운영자, 알림을 받는 담당자 관점이다.

1. 운영자가 새 버전의 묶음을 받아 모든 모니터링 서버를 [새 버전으로 갱신](../OPERATIONS.md#새-버전으로-갱신)한다. `.env`는 바꾸지 않는다. 관리자 서버와 배포 순서는 상관없다. 관리자 서버는 지금도 webhook마다 감시 항목별 간격이 지났는지 보고 알리므로, 1분마다 오는 webhook을 그대로 처리한다(사람 확인: 2026-11-25).
2. 담당자는 대상이 살아나면 지금보다 빨리(최대 5분 → 1분) 관리자 화면에서 장애가 풀린 것을 본다.
3. 재알림은 정한 간격과 같거나 최대 1분 이르게 간다. 지금은 webhook이 5분마다 와서 정한 간격보다 최대 4분 늦게 갈 수 있다. 이르게 가는 것은 관리자 서버의 1분 허용 오차 때문이며, 관리자 서버 요구사항에서 이미 받아들인 범위다(사람 확인: 2026-11-25).

## 동작

아래에서 "묶음"은 Alertmanager가 `alertname`과 `check_id`로 묶은 Alert들이다. 시간에는 webhook을 보내고 받는 데 걸리는 시간이 들어가지 않는다.

### 정상 동작

- **S1. 장애가 이어짐**
  - 상황: 감시 항목의 장애가 이어진다.
  - 행동: Alertmanager가 묶음을 다시 살핀다.
  - 결과: 첫 webhook 뒤로 1분마다 같은 장애를 webhook으로 다시 보낸다. 한 번씩 건너뛰지 않는다.
- **S2. 복구**
  - 상황: 장애였던 주소가 Prometheus에서 복구로 판정된다.
  - 행동: Alertmanager가 묶음을 다시 살핀다.
  - 결과: 복구 판정 뒤 1분 안에 복구 Alert(`status: resolved`)를 보낸다.
- **S3. 같은 감시 항목에 새 장애**
  - 상황: 감시 항목의 주소 하나가 이미 장애이고, 같은 감시 항목의 다른 주소가 장애로 판정된다.
  - 행동: Alertmanager가 묶음을 다시 살핀다.
  - 결과: 판정 뒤 1분 안에 새 장애를 함께 보낸다.
- **S4. 처음 생긴 장애**
  - 상황: 진행 중인 장애가 없는 감시 항목에 장애가 생긴다.
  - 행동: Alertmanager가 새 묶음을 만든다.
  - 결과: 지금처럼 30초 기다렸다 보낸다.
- **S5. 확인 결과가 끊긴 장애**
  - 상황: 장애 중인 감시 항목이나 주소를 지우거나 끄거나, Blackbox Exporter가 멈춘다.
  - 행동: 마지막 확인 결과가 5분 지나 Prometheus가 복구로 판정한다.
  - 결과: 복구 webhook이 마지막 확인 결과 뒤 6분 15초 안에 온다(5분 창, 규칙 평가 15초, 묶음 전송 1분). 그 전까지 같은 장애를 1분마다 다시 보낸다.

### 예외 동작

- **S7. 관리자 서버의 응답이 늦음**
  - 상황: 관리자 서버가 webhook에 20초보다 늦게 응답한다(알림 전송을 마친 뒤 응답하는 등).
  - 행동: 장애가 이어진다.
  - 결과: Alertmanager가 다음 판단에서 경과 시간이 모자란다고 보고 한 번 건너뛰어, 이어지는 장애가 2분마다 간다. 복구와 새 장애는 영향을 받지 않는다. 통합 계약에 20초 안에 응답하라고 적어 막는다.
- **S6. 관리자 서버가 webhook에 실패를 돌려줌**
  - 상황: 관리자 서버가 멈췄거나 webhook에 `2xx`가 아닌 응답을 돌려준다.
  - 행동: Alertmanager가 묶음을 보낸다.
  - 결과: 지금처럼 Alertmanager가 다시 시도한다. 관리자 서버가 뜬 뒤 다음 전송에서 받는다.

### 보안

- 접근 권한: 바뀌지 않는다.
- 외부 입력: 바뀌지 않는다.
- 민감한 정보: 없음
- 남길 보안 이벤트: 없음

### 바뀌지 않는 것

- `group_by`(`alertname`, `check_id`)와 `group_wait`(30초), 복구 Alert를 보내는 것, webhook 주소와 token
- webhook 요청 형식, Alert label과 annotation
- Alert 규칙과 장애·복구 판정 시점
- 관리자 서버의 알림 다시 보내는 간격 범위(5~1440분)
- `.env`의 설정 값 목록

## 완료 기준

- [x] C1. `infrastructure/alertmanager/alertmanager.yml`의 `group_wait`가 `30s`, `group_interval`이 `1m`, `repeat_interval`이 `40s`이고, 주석이 새 값과 관계를 설명한다. 펼친 설정이 Alertmanager 설정 검사를 통과한다. (S1, S4) — 확인: `node scripts/build-monitoring-bundle.mjs --check`, diff 리뷰
- [x] C2. 개발 환경에서 webhook을 기록하는 가짜 앱을 붙이고 장애를 이어 두면 webhook이 1분 간격으로 오고 건너뛰지 않는다. 장애 중인 감시 항목에 두 번째 주소의 장애를 넣으면 1분 안에, 복구하면 1분 안에 webhook이 온다. 처음 장애는 30초 뒤에 온다. (S1~S4) — 확인: 개발 환경 수동 확인
- [x] C3. [INTEGRATION.md](../INTEGRATION.md)에 webhook은 20초 안에 응답한다는 조건이 있고(S7), [INTEGRATION.md](../INTEGRATION.md), [OPERATIONS.md](../OPERATIONS.md), [ARCHITECTURE.md](../ARCHITECTURE.md)의 전송 간격과 그에 따른 시간(이어지는 장애는 1분마다, 복구 webhook은 1분 안, 확인 결과가 끊긴 장애의 복구는 6분 15초 안, 같은 감시 항목의 새 장애는 1분 안, 재알림은 정한 간격보다 최대 1분 이름)이 새 값과 맞고, 알림 다시 보내는 간격 하한 5분이 `group_interval`보다 짧지 않다는 관계가 남아 있다. (목표, S5) — 확인: 문서 리뷰
- [x] C4. 새 ADR에 바꾼 값, 지킬 관계, 이 ADR이 대신하는 ADR 0001·0010의 문장이 있다. (목표) — 확인: 문서 리뷰
- [x] C5. `node scripts/build-monitoring-bundle.mjs --check`와 `node scripts/check-docs.mjs`가 통과한다. — 확인: 명령 실행

## 확인 필요

없음
