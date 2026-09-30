# ADR-0003: Monitoring Check를 Server에서 분리한다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

같은 애플리케이션이 서버 여러 대에서 동작하는 경우가 많습니다.
운영자는 이것을 "전자결재 Health" 같은 Check 하나로 관리하려 합니다.

Check를 특정 서버의 하위 항목으로 두면, 다른 서버에 있는 주소의 소속이 맞지 않게 됩니다.

## Options Considered

### Option 1: Check를 Server에 종속시킨다 (`monitoring_checks.server_id`)

- 장점: 서버별로 Check를 모아 보기 쉽습니다.
- 단점: 여러 서버에 걸친 Check를 표현할 수 없습니다. 서버를 지우면 Check도 영향을 받습니다.
- 주요 trade-off: 서버 중심 탐색과 실제 서비스 구조를 맞바꿉니다.

### Option 2: Check와 Server를 서로 다른 도메인으로 둔다

- 장점: Server 없이 Check를 만들 수 있고, Server를 지워도 모니터링이 멈추지 않습니다.
- 단점: 어떤 서버에 어떤 Check가 있는지 자동으로 알 수 없습니다.
- 주요 trade-off: 연결 정보가 필요해지면 나중에 추가해야 합니다.

## Decision

Option 2를 선택합니다.

`monitoring_checks`에서 `server_id`를 제거했고, Check 쪽 table과 `server_targets` 사이에 FK를 두지 않습니다.
Server 상태도 Blackbox Check 결과로 계산하지 않습니다. SNMP 수집 결과로만 판단합니다.

## Consequences

- 긍정적인 영향: 서버 정리 때문에 모니터링이 조용히 멈추는 사고가 없습니다.
- 부정적인 영향 / 감수하는 trade-off: Check Target과 등록된 서버를 연결하는 기능이 없습니다.
- 향후 고려사항: 필요성이 확인되면 `MonitoringCheckTarget → optional ServerTarget` 관계를 검토합니다.

## Confidence

High

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 2-1
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "두 개의 독립된 도메인"
- 관련 ADR: [ADR-0002](ADR-0002-sd-entry-per-check-target.md)
