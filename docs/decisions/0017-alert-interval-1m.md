# 0017. Alertmanager는 묶음을 1분마다 다시 살핀다

- 상태: 승인
- 날짜: 2026-11-25
- 관련: [요구사항 0026](../requirements/0026-alert-interval-1m.md), [ADR 0001](0001-alert-repeat-in-manager.md)(이 ADR이 그 결정의 5분·4분 값과 그에 따른 감수할 점만 대신함), [ADR 0010](0010-alert-labels-by-check-id.md)(이 ADR이 감수할 점의 복구 webhook 시간만 대신함)

## 배경과 문제

Alertmanager는 감시 항목(`alertname`, `check_id`)마다 묶은 Alert를 묶음을 다시 살피는 간격(`group_interval`)마다 관리자 서버로 보낸다. 이 값이 5분이라, 확인이 성공해 복구로 판정해도 관리자 화면에 장애가 최대 5분 남았다. 장애가 이미 있는 감시 항목의 새 장애도 최대 5분 늦게 갔다.

ADR 0001은 Alertmanager가 이어지는 Alert를 짧은 간격으로 계속 보내고, 관리자 서버가 webhook마다 감시 항목별 재알림 간격(5~1440분)으로 판단하게 했다. 그러려면 `repeat_interval`이 `group_interval`보다 짧아야 하고, 재알림 간격의 하한(5분)이 `group_interval`보다 짧으면 안 된다. 이 값들은 `.env`로 열지 않는다(요구사항 0013).

## 검토한 선택지

- **5분 그대로**: webhook 수가 적다 / 복구와 같은 감시 항목의 새 장애가 최대 5분 늦는다.
- **`group_interval` 1분, `repeat_interval` 40초**: 복구와 새 장애가 1분 안에 간다. ADR 0001의 관계를 지킨다 / 이어지는 장애마다 1분에 한 번 webhook이 간다.
- **`group_interval`을 1분보다 짧게**: 더 빠르다 / webhook이 더 늘고, 장애 판정이 15초마다라서 얻는 것이 적다.

## 결정

두 번째 선택지를 고른다(사람 확인: 2026-11-25, 관리자 서버 Testigo_Mng). `group_interval`을 1분, `repeat_interval`을 40초로 고정하고 `group_wait`(30초)는 그대로 둔다. Alertmanager는 묶음 타이머가 울린 시각과 지난 전송이 끝난 시각(webhook 응답과 재시도 뒤)의 차이를 `repeat_interval`과 비교한다. 그래서 관리자 서버의 응답 시간이 `group_interval - repeat_interval`을 넘으면 한 번씩 건너뛴다. 요청서는 50초였지만(사람 확인: 2026-11-25, 관리자 서버 Testigo_Mng), 견디는 응답 시간이 10초뿐이라 40초로 줄이고 통합 계약에 "webhook은 20초 안에 응답"을 적는다(사람 확인: 2026-11-25, 이 저장소). 30초 이하는 전송 기록의 만료(`repeat_interval`의 2배)가 `group_interval` 이하가 되어 복구 webhook을 잃을 수 있어 쓰지 않는다(Alertmanager v0.34.1 `notify`·`nflog` 소스로 확인).

ADR 0001의 결정에서 "`group_interval`(5분)보다 짧은 4분으로 고정해, Alertmanager가 이어지는 Alert를 5분마다 webhook으로 보내게 한다"를 대신한다. 관리자 서버가 webhook마다 감시 항목별 간격으로 판단하는 것, 재알림 하한 5분과 1분 허용 오차, `repeat_interval` < `group_interval` ≤ 재알림 하한이라는 관계는 그대로다. ADR 템플릿에는 일부만 대체하는 것을 표시하는 상태 값이 없어서 ADR 0001과 0010은 고치지 않고, 이 ADR에서 가리킨다.

## 결과

- 좋아지는 점: 복구로 판정한 뒤 1분 안에, 같은 감시 항목의 새 장애도 판정 뒤 1분 안에 관리자 서버에 간다. 재알림이 정한 간격보다 늦게 가는 폭이 최대 4분에서 거의 없어진다.
- 감수할 점(ADR 0001과 0010의 같은 항목을 대신함):
  - 이어지는 장애마다 1분에 한 번 webhook이 간다. 관리자 서버 쪽이 감당한다고 확인했다(2026-11-25).
  - 관리자 서버는 webhook에 20초 안에 응답해야 한다. 늦으면 이어지는 장애가 한 번씩 건너뛰어 2분마다 간다. 알림 전송은 응답한 뒤에 한다.
  - 재알림은 정한 간격보다 최대 1분 이르게 갈 수 있다. 관리자 서버의 1분 허용 오차 때문이며 관리자 서버 요구사항에서 받아들였다.
  - 재알림 전송이 계속 실패하면 관리자 서버가 webhook마다(1분) 다시 시도한다.
  - 장애 중인 감시 항목이나 주소를 지우거나 끄거나 Blackbox Exporter가 멈추면, 복구 webhook이 마지막 확인 결과 5분~6분 15초 뒤에 오고 그 사이 같은 장애가 1분마다 다시 간다. 정상으로 돌아온 대상의 복구 webhook은 1분 안에 온다.
- 다시 볼 신호: 감시 항목 수가 늘어 관리자 서버의 webhook 처리가 느려질 때, 재알림 하한을 1분보다 짧게 바꿀 때

## 확인 방법

- 리뷰에서 확인: `alertmanager.yml`의 `repeat_interval`이 `group_interval`보다 짧고 `group_interval`의 절반보다 길며, `group_interval`이 관리자 서버의 재알림 하한(5분)보다 길지 않은지
- `node scripts/build-monitoring-bundle.mjs --check`의 Alertmanager 설정 검사
