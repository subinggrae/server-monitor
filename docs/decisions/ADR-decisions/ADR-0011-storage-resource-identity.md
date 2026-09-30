# ADR-0011: Storage Resource를 SNMP index가 아니라 hrStorageDescr로 식별한다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

운영자는 장비에서 찾은 Storage 중 모니터링할 것을 골라 저장합니다.
저장한 선택은 다시 검색하거나 장비가 재부팅된 뒤에도 같은 Storage를 가리켜야 합니다.

`hrStorageIndex`는 재부팅이나 SNMP Agent 설정 변경으로 바뀔 수 있습니다.

## Options Considered

### Option 1: `hrStorageIndex`로 식별한다

- 장점: SNMP table의 key를 그대로 씁니다.
- 단점: index가 바뀌면 다른 Storage를 가리키거나 선택이 사라집니다.
- 주요 trade-off: 단순함과 안정성을 맞바꿉니다.

### Option 2: 장비가 준 `hrStorageDescr`로 식별한다

- 장점: `/`, `/data` 같은 mount 이름은 재부팅해도 유지됩니다.
- 단점: 장비가 설명 문자열을 바꾸면 다른 Resource로 봅니다.
- 주요 trade-off: 설명 문자열이 안정적이라는 가정에 기댑니다.

## Decision

Option 2를 선택합니다. index는 참고 값으로만 저장합니다.

다시 검색해도 기존 선택은 그대로 둡니다.
장비에서 사라진 Resource는 지우지 않고 `UNAVAILABLE`로 보여 줍니다.

## Consequences

- 긍정적인 영향: 재부팅 후에도 선택한 Storage가 유지됩니다.
- 부정적인 영향 / 감수하는 trade-off: 설명이 바뀐 Storage는 새 Resource로 보이고, 이전 것은 `UNAVAILABLE`로 남습니다.
- 향후 고려사항: 없음

## Confidence

Medium

장비 종류별로 `hrStorageDescr`가 안정적인지 확인한 기록은 없습니다.

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 14
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "SNMP Resource 모니터링"
- 관련 ADR: [ADR-0009](ADR-0009-agentless-snmp-resource-monitoring.md)
