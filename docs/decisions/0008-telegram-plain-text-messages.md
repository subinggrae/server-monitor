# 0008. Telegram 메시지를 plain text로 보낸다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [notifications.md의 실패와 예외 처리](../architecture/notifications.md#실패와-예외-처리), [0005](0005-alertmanager-single-webhook-routing.md)

## 배경과 문제

알림 메시지에는 URL과 IP가 들어간다.

Telegram의 Markdown이나 HTML parse mode를 쓰면 주소에 들어 있는 밑줄이나 괄호를 서식으로 읽는다. 서식이 맞지 않으면 Telegram이 메시지 전체를 거부한다.

## 검토한 선택지

- **Markdown·HTML로 보내고 문자마다 escape한다**: 굵은 글씨 같은 서식을 쓸 수 있다 / escape 규칙을 하나라도 놓치면 장애 알림이 통째로 거부된다. 가독성과 전송 신뢰성을 맞바꾼다.
- **plain text로 보낸다**: 대상 주소의 내용과 관계없이 메시지가 거부되지 않는다 / 서식을 쓸 수 없다. 가독성을 조금 포기한다.

## 결정

plain text로 보낸다. 장애 알림은 도착하는 것이 서식보다 중요하다.

## 결과

- 좋아지는 점: 주소 문자열 때문에 알림이 거부되지 않는다.
- 감수할 점: 메시지에 강조 표시가 없다.
- 확신도: 높음

## 확인 방법

원래 기록에 없다.
