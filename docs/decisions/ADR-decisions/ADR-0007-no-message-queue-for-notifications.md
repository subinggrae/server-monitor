# ADR-0007: 알림 전송에 Message Queue를 쓰지 않는다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

Manager는 Alertmanager Webhook을 받아 저장하고 Telegram으로 보냅니다.
Telegram 전송이 실패할 수 있으므로 재시도 방법이 필요합니다.

프로젝트는 Modular Monolith이고 추가 미들웨어를 미리 만들지 않는 것이 원칙입니다.

## Options Considered

### Option 1: Kafka나 RabbitMQ로 수신과 전송을 나눈다

- 장점: 전송 재시도와 부하 분산을 Queue가 맡습니다.
- 단점: 운영할 구성요소가 늘어납니다.
- 주요 trade-off: 현재 규모에 비해 인프라 비용이 큽니다.

### Option 2: 한 애플리케이션 안에서 처리하고 재시도는 Alertmanager에 맡긴다

- 장점: 추가 인프라가 없습니다.
- 단점: 실패한 전송을 Manager가 스스로 다시 보내지 않습니다.
- 주요 trade-off: 재시도 시점이 Alertmanager의 반복 주기에 묶입니다.

## Decision

Option 2를 선택합니다.

전송 실패는 `notification_deliveries`에 `FAILED`로 기록합니다.
다음 재시도는 Alertmanager의 반복 알림(`repeat_interval`)이 가져옵니다.

## Consequences

- 긍정적인 영향: 구성요소가 늘지 않습니다.
- 부정적인 영향 / 감수하는 trade-off: 전송이 실패하면 다음 반복 알림(`repeat_interval` 1시간)까지 알림이 늦어질 수 있습니다.
- 향후 고려사항: 전송 실패가 운영에서 문제가 되면 이 결정을 다시 검토합니다.

## Confidence

Medium

복구(resolved) 알림 전송이 실패했을 때 다시 전송되는지는 확인하지 않았습니다.

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 9, 13
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "Alertmanager에서 Telegram까지"
- 관련 ADR: [ADR-0005](ADR-0005-alertmanager-single-webhook-routing.md), [ADR-0006](ADR-0006-alert-event-identity-and-dedupe.md)
