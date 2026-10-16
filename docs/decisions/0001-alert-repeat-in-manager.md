# 0001. Alert 재발송 판단은 Manager가 Check별 주기로 한다

- 상태: 승인
- 날짜: 2026-10-14
- 관련: [요구사항 0003](../requirements/0003-alert-repeat-interval.md), 지운 계획은 [PR #4](https://github.com/subinggrae/server-monitor/pull/4)에서 볼 수 있다

## 배경과 문제

장애가 이어지는 동안 Telegram 알림을 다시 보내는 주기를 Check마다 다르게 정하고, 화면에서 바꾸면 아무것도 다시 띄우지 않고 반영해야 했다. 그전에는 `alertmanager.yml`의 `repeat_interval: 1h` 하나가 모든 Check의 재발송 주기였다.

사용자가 화면에서 바꾼 설정 때문에 `infrastructure/`의 파일을 고치지 않는다는 경계가 있다([ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)). Alertmanager에는 Prometheus의 HTTP SD처럼 설정을 밖에서 받아 오는 경로가 없다.

## 검토한 선택지

- **Check마다 route를 만들어 `alertmanager.yml`을 다시 쓰고 reload**: Alertmanager가 주기를 직접 지킨다 / 경계를 어기고, 설정 파일이 Check 수만큼 커진다.
- **Manager가 스케줄러로 `alert_events`의 FIRING 행을 다시 보냄**: Alertmanager 설정을 바꾸지 않는다 / Alert가 아직 이어지는지는 Alertmanager의 webhook으로만 알 수 있어, Alertmanager가 멈추면 끝난 장애를 계속 알릴 수 있다.
- **Alertmanager는 짧은 간격으로 계속 보내고, Manager가 webhook마다 Check별 주기로 판단**: 경계를 지키고 다시 띄울 것이 없다 / webhook과 DB 쓰기가 늘고, 실제 재발송 시각이 webhook 간격만큼 어긋난다.

## 결정

세 번째 선택지를 고른다. `alertmanager.yml`의 `repeat_interval`을 사용자 설정과 관계없이 `group_interval`(5분)보다 짧은 4분으로 고정해, Alertmanager가 이어지는 Alert를 5분마다 webhook으로 보내게 한다. 두 값을 같게 두면 다음 시점에 경과 시간이 조금 모자라 한 번씩 건너뛰므로 10분마다 보낸다. 재발송 주기(5~1440분, 기본 60분)는 Check마다 `check_notification_settings`에 둔다. Manager는 FIRING webhook을 받을 때마다 그 Alert와 그룹의 마지막 성공 전송 뒤 `재발송 주기 - 1분`이 지났는지 보고 보낸다. 1분은 전송 시각이 Telegram 호출 뒤에 기록돼 생기는 지연 때문에 한 webhook 간격을 건너뛰지 않게 하는 허용 오차다. 복구 알림은 재발송 주기를 보지 않는다.

## 결과

- 좋아지는 점: 사용자가 화면에서 바꾼 주기가 다음 webhook부터 반영된다. Alertmanager 설정은 배포할 때만 바뀐다.
- 감수할 점:
  - 이어지는 Alert마다 5분에 한 번 webhook과 DB 쓰기가 생긴다.
  - 실제 재발송은 주기보다 최대 5분 늦거나 1분 이르다.
  - 재발송 주기의 하한은 `group_interval`보다 짧을 수 없다.
  - 배포 순서가 중요하다. Manager를 먼저 배포하고 Alertmanager 설정을 나중에 바꾼다. 되돌릴 때는 Alertmanager 설정을 먼저 되돌린다. 이전 Manager는 짧은 중복 방지만 하므로, 순서가 바뀌면 5분마다 알림이 간다. `alertmanager.yml`은 파일을 그대로 mount하므로, 파일이 바뀌어도 Alertmanager에 reload(`POST /-/reload`)나 재시작을 해야 반영된다. 그래서 코드를 받는 시점이 아니라 reload하는 시점이 순서를 정한다.
  - 마지막 성공 전송 뒤 주기가 지난 상태에서 Telegram 전송이 계속 실패하면 webhook마다(5분) 다시 시도하고 FAILED를 기록한다.
- 다시 볼 신호: 5분보다 짧은 재발송 주기가 필요해지거나, Check 수가 늘어 webhook 처리가 느려질 때

## 확인 방법

- `AlertNotificationDispatcherTest`, `RepeatNotificationPolicyTest`
- 리뷰에서 확인: `alertmanager.yml`의 `repeat_interval`이 `group_interval`보다 짧고, `group_interval`이 재발송 주기 하한(요청 DTO의 `@DecimalMin`)보다 길지 않은지
