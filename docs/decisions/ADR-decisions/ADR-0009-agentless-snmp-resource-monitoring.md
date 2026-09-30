# ADR-0009: 서버 Resource를 Agent 없이 SNMP로 수집한다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

CPU, Memory, Storage를 수집해야 합니다.

대상 서버는 고객사 서버나 다른 팀 서버일 수 있습니다.
그런 서버에 Agent를 설치하려면 승인 절차가 필요하고, 설치 자체가 도입 장벽이 됩니다.

## Options Considered

### Option 1: 대상 서버에 Node Exporter 같은 Agent를 설치한다

- 장점: 얻을 수 있는 metric이 많습니다.
- 단점: 서버마다 설치와 승인이 필요합니다.
- 주요 trade-off: 정보량과 도입 비용을 맞바꿉니다.

### Option 2: 모니터링 서버 쪽에서 SNMP로 가져온다

- 장점: 대상 서버에는 snmpd 설정과 방화벽 허용만 필요합니다.
- 단점: 얻을 수 있는 정보가 Node Exporter보다 적습니다. `HOST-RESOURCES-MIB`가 없는 장비에서는 CPU나 Memory를 찾지 못합니다.
- 주요 trade-off: 정보량을 포기합니다.

## Decision

Option 2를 선택합니다.

가용성은 Blackbox Exporter, 서버 Resource는 SNMP Exporter로 모두 모니터링 서버 쪽에서 수집합니다.
역할은 다음과 같이 나눕니다.

- Resource Discovery는 Manager Backend가 장비에 직접 SNMP로 합니다.
- 지속적인 metric 수집은 Prometheus와 `snmp_exporter`가 합니다.

## Consequences

- 긍정적인 영향: 대상 서버에 아무것도 설치하지 않습니다.
- 부정적인 영향 / 감수하는 trade-off: SNMP v2c만 지원합니다. community가 필요하므로 Credential 관리가 따라옵니다.
- 향후 고려사항: SNMP v3는 Credential 모델이 달라 `server_snmp_configs`에 field를 더 넣어야 합니다.

## Confidence

High

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 14
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "Agent 없이 모니터링하기", "SNMP Resource 모니터링"
- 관련 ADR: [ADR-0010](ADR-0010-snmp-credentials-via-exporter-auth-config.md), [ADR-0011](ADR-0011-storage-resource-identity.md)
