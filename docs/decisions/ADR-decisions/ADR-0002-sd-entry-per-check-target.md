# ADR-0002: Blackbox SD entry를 Check Target 단위로 만든다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

하나의 Monitoring Check는 확인 주소(Target)를 여러 개 가집니다.
화면에서는 어느 주소가 문제인지 따로 보여 줘야 합니다.

Prometheus SD 응답의 entry 하나는 `targets` 배열에 주소를 여러 개 담을 수 있지만,
같은 entry의 주소는 label을 모두 공유합니다.

## Options Considered

### Option 1: Check 하나를 entry 하나로 만들고 주소를 `targets` 배열에 넣는다

- 장점: 응답이 짧습니다.
- 단점: 주소마다 다른 식별자 label을 붙일 수 없어 주소 단위 상태를 구분할 수 없습니다.
- 주요 trade-off: 응답 크기와 상태 구분 능력을 맞바꿉니다.

### Option 2: Target 하나를 entry 하나로 만든다

- 장점: 주소마다 `check_target_id`를 붙일 수 있습니다.
- 단점: 같은 Check의 label 값이 entry마다 반복됩니다.
- 주요 trade-off: 응답이 길어지는 것을 감수합니다.

## Decision

Option 2를 선택합니다.

주소 단위 상태를 보여 주려면 안정적인 식별자가 필요하기 때문입니다.
같은 Check에 속한다는 것은 `check_id` label로 알 수 있습니다.

## Consequences

- 긍정적인 영향: Backend는 `probe_success`를 한 번 조회하고 `check_target_id`로 묶어 모든 Target 상태를 계산합니다.
- 부정적인 영향 / 감수하는 trade-off: Target id가 바뀌면 time series가 끊깁니다.
  그래서 Check 수정 API는 기존 Target의 `id`를 유지해야 합니다.
- 향후 고려사항: 없음

## Confidence

High

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 2-1
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "Prometheus HTTP Service Discovery", "Target과 Check 상태"
- 관련 ADR: [ADR-0001](ADR-0001-prometheus-http-service-discovery.md), [ADR-0003](ADR-0003-separate-check-from-server.md)
