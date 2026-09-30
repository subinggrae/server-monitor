# ADR-0005: Alertmanager는 Manager Webhook 하나로 보내고 알림 대상은 Manager가 정한다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

운영자는 화면에서 Telegram 그룹을 추가하고 Check에 연결합니다.

Alertmanager가 Telegram으로 직접 보내면 그룹마다 receiver가 필요합니다.
그룹을 추가할 때마다 `alertmanager.yml`을 고치고 Alertmanager를 다시 띄워야 합니다.
일반 운영자가 할 수 있는 일이 아닙니다.

## Options Considered

### Option 1: Alertmanager가 Telegram receiver를 그룹마다 가진다

- 장점: Alertmanager 기본 기능만 씁니다.
- 단점: 그룹 추가가 설정 파일 수정입니다. 라우팅 정보(`service`, `notification_group`)를 Prometheus label에 넣어야 합니다.
- 주요 trade-off: 구현이 단순한 대신 사용자 설정이 배포용 파일에 섞입니다.

### Option 2: Alertmanager는 Manager Webhook 하나만 알고, Manager가 Database에서 대상을 찾는다

- 장점: 그룹 추가와 연결이 Manager Database 변경만으로 끝납니다. Label이 라우팅 정보를 갖지 않습니다.
- 단점: Manager가 Alert 저장, 대상 조회, Telegram 전송을 모두 맡습니다.
- 주요 trade-off: Manager의 책임이 늘어납니다.

## Decision

Option 2를 선택합니다.

```text
Prometheus → Alertmanager → Manager Webhook
→ Notification Binding 조회 → Telegram Bot API
```

- Webhook은 Bearer token(`alertmanager_webhook_token`)으로 검증합니다.
- Manager는 `check_id` label로 Check를 찾고, Check에 연결된 그룹에만 보냅니다.
- 연결이 없으면 보내지 않습니다. 기본 그룹을 두지 않습니다.
- Telegram 전송이 실패해도 Webhook은 성공을 돌려줍니다. 실패를 돌려주면 Alertmanager가 같은 payload를 계속 재시도합니다.
- `service`, `notification_group` label은 제거했습니다.

## Consequences

- 긍정적인 영향: `alertmanager.yml`에 Telegram 설정이 없습니다. Bot Token도 Alertmanager에 주지 않습니다.
- 부정적인 영향 / 감수하는 trade-off: label 구성이 바뀌어 `probe_success` time series가 한 번 끊겼습니다.
  이전 데이터는 Prometheus retention(15일) 동안 남습니다.
- 향후 고려사항: 알림 연결은 지금 Monitoring Check에만 붙습니다. Server Resource와 Database Target은 그 기능을 만들 때 추가합니다.

## Confidence

High

실제 Telegram 그룹으로의 전송과 `/register`는 운영 환경에서 확인하지 못했습니다. Mock Telegram API로 경로를 확인했습니다.

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 3, 4
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "Alertmanager에서 Telegram까지"
- 관련 ADR: [ADR-0006](ADR-0006-alert-event-identity-and-dedupe.md), [ADR-0007](ADR-0007-no-message-queue-for-notifications.md), [ADR-0008](ADR-0008-telegram-plain-text-messages.md)
