# ADR-0010: SNMP Credential을 Manager가 만든 snmp_exporter auth config로 전달한다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

`snmp_exporter`는 scrape 요청에서 `target`, `module`, `auth`만 받습니다.
community는 받지 않고, 자기 config의 `auths`에 미리 있는 이름만 고를 수 있습니다.

community는 운영자가 화면에서 입력하고 Manager Database가 기준입니다.
community가 Prometheus label이나 SD 응답에 남으면 안 됩니다.

## Options Considered

### Option 1: 사용자 설정에 맞춰 `snmp.yml` 전체를 만든다

- 장점: exporter 설정이 한 파일에 있습니다.
- 단점: image에 들어 있는 module 정의까지 관리하게 됩니다. 사용자 설정이 배포용 설정에 섞입니다.
- 주요 trade-off: 설정 파일 하나와 관리 범위를 맞바꿉니다.

### Option 2: auth만 담긴 파일을 Manager가 만들고 exporter가 기본 `snmp.yml`과 합쳐 읽게 한다

- 장점: module 정의는 image 기본값 그대로입니다. Prometheus에는 auth 이름만 넘깁니다.
- 단점: 파일 쓰기와 exporter reload라는 두 번째 반영 단계가 생깁니다.
- 주요 trade-off: DB 저장과 exporter 반영이 따로 실패할 수 있습니다.

## Decision

Option 2를 선택합니다.

```text
Manager Database (community 암호문)
→ 복호화 → runtime auth config (manager_server_<id>)
→ POST /-/reload → snmp_exporter
Prometheus → HTTP SD (auth 이름만)
```

- community는 `APP_SECRET_KEY`로 암호화해 저장합니다.
- auth config는 `snmp-runtime` volume에만 둡니다. Git에 없고 host에 bind하지 않습니다.
- 파일은 임시 이름으로 쓴 뒤 옮깁니다. exporter가 쓰다 만 파일을 읽으면 안 됩니다.
- `snmp_auth`, `snmp_modules`는 relabel 단계에서 지워 metric label로 남기지 않습니다.

## Consequences

- 긍정적인 영향: community가 Prometheus, SD 응답, API 응답에 나타나지 않습니다.
- 부정적인 영향 / 감수하는 trade-off: 저장은 됐는데 반영이 실패할 수 있습니다.
  그래서 `exporterSynced`를 응답에 넣고 화면에서 알려 줍니다.
- 향후 고려사항: `APP_SECRET_KEY`를 바꾸면 저장된 community를 읽을 수 없습니다.

## Confidence

High

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 11, 14
- 관련 설계 문서: [architecture.md](../../architecture/architecture.md) "SNMP Resource 모니터링", [infrastructure/snmp/README.md](../../../infrastructure/snmp/README.md)
- 관련 ADR: [ADR-0009](ADR-0009-agentless-snmp-resource-monitoring.md), [ADR-0012](ADR-0012-secrets-via-docker-secret-config-tree.md)
