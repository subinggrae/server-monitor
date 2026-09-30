# ADR-0006: 장애 한 번을 fingerprint + startsAt으로 식별하고 중복 전송은 짧은 창으로 막는다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

Alertmanager는 같은 Alert를 반복해서 보냅니다.
`repeat_interval`마다 한 번 보내고, Webhook 응답이 늦으면 곧바로 한 번 더 보냅니다.

받을 때마다 새 행을 만들면 Alert 이력을 쓸 수 없고, 받을 때마다 보내면 같은 알림이 두 번 갑니다.
반대로 너무 강하게 막으면 장애가 이어지는 동안의 정상적인 반복 알림까지 사라집니다.

## Options Considered

### Option 1: `fingerprint`만으로 Alert를 식별한다

- 장점: 단순합니다.
- 단점: 같은 대상이 나중에 다시 장애가 나면 이전 기록을 덮습니다.
- 주요 trade-off: 이력 정확도를 잃습니다.

### Option 2: `fingerprint + status`를 영구 unique로 두어 중복 전송을 막는다

- 장점: 같은 알림을 절대 두 번 보내지 않습니다.
- 단점: 장애가 이어지는 동안 오는 정상적인 반복 알림까지 막힙니다.
- 주요 trade-off: 중복 방지와 반복 알림을 맞바꿉니다.

### Option 3: `fingerprint + startsAt`으로 식별하고, 짧은 시간 창으로 중복 전송을 막는다

- 장점: 장애 한 번이 한 행입니다. 재전송만 막고 반복 알림은 통과합니다.
- 단점: 창 크기를 Alertmanager 설정과 맞춰야 합니다.
- 주요 trade-off: 두 설정 사이에 암묵적인 의존이 생깁니다.

## Decision

Option 3을 선택합니다.

- `fingerprint`는 label 조합이라 같은 대상이면 같습니다. `startsAt`은 장애가 해소된 뒤 다시 발생하면 새 값이 됩니다.
- 같은 alert_event, 같은 그룹, 같은 status로 2분 안에 성공한 전송이 있으면 보내지 않습니다.
- 실패한 전송은 세지 않습니다. 다시 보낼 수 있어야 합니다.
- 보내지 않은 건도 `notification_deliveries`에 `SKIPPED`로 남깁니다.

## Consequences

- 긍정적인 영향: "왜 알림이 안 왔는지"를 전송 기록으로 확인할 수 있습니다.
- 부정적인 영향 / 감수하는 trade-off: 중복 창(2분)은 `alertmanager.yml`의 `group_interval`(5분)보다 작아야 합니다.
  한쪽만 바꾸면 반복 알림이 막히거나 중복이 생깁니다.
- 향후 고려사항: 없음

## Confidence

High

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 4
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "Alertmanager에서 Telegram까지"
- 관련 ADR: [ADR-0005](ADR-0005-alertmanager-single-webhook-routing.md)
