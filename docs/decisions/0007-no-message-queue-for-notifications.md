# 0007. 알림 전송에 Message Queue를 쓰지 않는다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: 옛 CLAUDE.md 규칙 9, 13(원문은 저장소에 없다), [notifications.md의 실패와 예외 처리](../architecture/notifications.md#실패와-예외-처리), [0005](0005-alertmanager-single-webhook-routing.md), [0006](0006-alert-event-identity-and-dedupe.md)

## 배경과 문제

Manager는 Alertmanager Webhook을 받아 저장하고 Telegram으로 보낸다. Telegram 전송이 실패할 수 있으므로 재시도 방법이 필요하다.

프로젝트는 Modular Monolith이고 추가 미들웨어를 미리 만들지 않는 것이 원칙이다.

## 검토한 선택지

- **Kafka나 RabbitMQ로 수신과 전송을 나눈다**: 전송 재시도와 부하 분산을 Queue가 맡는다 / 운영할 구성요소가 늘어난다. 현재 규모에 비해 인프라 비용이 크다.
- **한 애플리케이션 안에서 처리하고 재시도는 Alertmanager에 맡긴다**: 추가 인프라가 없다 / 실패한 전송을 Manager가 스스로 다시 보내지 않는다. 재시도 시점이 Alertmanager의 반복 주기에 묶인다.

## 결정

한 애플리케이션 안에서 처리하고 재시도는 Alertmanager에 맡긴다.

전송 실패는 `notification_deliveries`에 `FAILED`로 기록한다. 다음 재시도는 Alertmanager의 반복 알림(`repeat_interval`)이 가져온다.

## 결과

- 좋아지는 점: 구성요소가 늘지 않는다.
- 감수할 점: 전송이 실패하면 다음 반복 알림(`repeat_interval` 1시간)까지 알림이 늦어질 수 있다.
- 향후 고려사항: 전송 실패가 운영에서 문제가 되면 이 결정을 다시 검토한다.
- 확신도: 중간. 복구(resolved) 알림 전송이 실패했을 때 다시 전송되는지는 확인하지 않았다.

## 확인 방법

원래 기록에 없다.
