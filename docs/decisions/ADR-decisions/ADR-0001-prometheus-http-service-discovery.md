# ADR-0001: Prometheus Target을 HTTP Service Discovery로 관리한다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

운영자는 웹 화면에서 모니터링 대상을 등록하고 지웁니다.
Prometheus는 그 대상을 알아야 수집할 수 있습니다.

`prometheus.yml`은 배포용 설정입니다. 사용자 설정 때문에 이 파일을 고치면 안 됩니다.
Target을 바꿀 때마다 Prometheus를 다시 시작하는 것도 운영에서 받아들이기 어렵습니다.

## Options Considered

### Option 1: `prometheus.yml` 또는 `file_sd_configs` 파일을 Manager가 고친다

- 장점: Prometheus 기본 기능만 씁니다.
- 단점: 사용자 설정이 배포용 파일에 섞입니다. 파일과 Manager Database 내용이 어긋날 수 있습니다.
- 주요 trade-off: 구현이 단순한 대신 설정의 기준이 두 곳으로 나뉩니다.

### Option 2: Manager가 HTTP Service Discovery API를 제공한다

- 장점: Target 목록의 기준이 Manager Database 하나입니다. Prometheus를 다시 시작하지 않습니다.
- 단점: Prometheus가 Manager API에 의존합니다.
- 주요 trade-off: 반영까지 SD 조회 주기만큼 지연됩니다.

## Decision

Option 2를 선택합니다.

Manager가 `GET /api/prometheus/sd/<exporter>` 형태로 Target 목록을 제공하고,
Prometheus는 `http_sd_configs`로 이 API를 주기적으로 조회합니다.
`file_sd_configs`와 `targets/*.yml`은 쓰지 않습니다.

## Consequences

- 긍정적인 영향: Target을 추가하거나 지워도 설정 파일과 Prometheus 재시작이 필요 없습니다.
- 부정적인 영향 / 감수하는 trade-off: 변경은 SD 조회 주기(blackbox 15초, snmp 30초)와 scrape 주기가 지나야 반영됩니다.
  SD API는 조회마다 Manager Database를 읽으므로 응답 시간을 관리해야 합니다.
- 향후 고려사항: Database Target도 같은 방식(`/api/prometheus/sd/mariadb`)으로 제공합니다.

## Confidence

High

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 1, 2
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "Prometheus HTTP Service Discovery"
- 관련 ADR: [ADR-0002](ADR-0002-sd-entry-per-check-target.md)
