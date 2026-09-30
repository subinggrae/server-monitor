# ADR-0008: Telegram 메시지를 plain text로 보낸다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

알림 메시지에는 URL과 IP가 들어갑니다.

Telegram의 Markdown이나 HTML parse mode를 쓰면 주소에 들어 있는 밑줄이나 괄호를 서식으로 읽습니다.
서식이 맞지 않으면 Telegram이 메시지 전체를 거부합니다.

## Options Considered

### Option 1: Markdown / HTML로 보내고 문자마다 escape한다

- 장점: 굵은 글씨 같은 서식을 쓸 수 있습니다.
- 단점: escape 규칙을 하나라도 놓치면 장애 알림이 통째로 거부됩니다.
- 주요 trade-off: 가독성과 전송 신뢰성을 맞바꿉니다.

### Option 2: plain text로 보낸다

- 장점: 대상 주소의 내용과 관계없이 메시지가 거부되지 않습니다.
- 단점: 서식을 쓸 수 없습니다.
- 주요 trade-off: 가독성을 조금 포기합니다.

## Decision

Option 2를 선택합니다.

장애 알림은 도착하는 것이 서식보다 중요합니다.

## Consequences

- 긍정적인 영향: 주소 문자열 때문에 알림이 거부되지 않습니다.
- 부정적인 영향 / 감수하는 trade-off: 메시지에 강조 표시가 없습니다.
- 향후 고려사항: 없음

## Confidence

High

## References

- 관련 요구사항: 없음
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "Alertmanager에서 Telegram까지"
- 관련 ADR: [ADR-0005](ADR-0005-alertmanager-single-webhook-routing.md)
