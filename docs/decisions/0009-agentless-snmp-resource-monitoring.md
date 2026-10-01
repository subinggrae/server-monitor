# 0009. 서버 Resource를 Agent 없이 SNMP로 수집한다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md 규칙 14), [snmp-resources.md](../architecture/snmp-resources.md), [0010](0010-snmp-credentials-via-exporter-auth-config.md), [0011](0011-storage-resource-identity.md)

## 배경과 문제

CPU, Memory, Storage를 수집해야 한다.

대상 서버는 고객사 서버나 다른 팀 서버일 수 있다. 그런 서버에 Agent를 설치하려면 승인 절차가 필요하고, 설치 자체가 도입 장벽이 된다.

## 검토한 선택지

- **대상 서버에 Node Exporter 같은 Agent를 설치한다**: 얻을 수 있는 metric이 많다 / 서버마다 설치와 승인이 필요하다. 정보량과 도입 비용을 맞바꾼다.
- **모니터링 서버 쪽에서 SNMP로 가져온다**: 대상 서버에는 snmpd 설정과 방화벽 허용만 필요하다 / 얻을 수 있는 정보가 Node Exporter보다 적다. `HOST-RESOURCES-MIB`가 없는 장비에서는 CPU나 Memory를 찾지 못한다. 정보량을 포기한다.

## 결정

모니터링 서버 쪽에서 SNMP로 가져온다.

가용성은 Blackbox Exporter, 서버 Resource는 SNMP Exporter로 모두 모니터링 서버 쪽에서 수집한다. 역할은 다음과 같이 나눈다.

- Resource Discovery는 Manager Backend가 장비에 직접 SNMP로 한다.
- 지속적인 metric 수집은 Prometheus와 `snmp_exporter`가 한다.

## 결과

- 좋아지는 점: 대상 서버에 아무것도 설치하지 않는다.
- 감수할 점: SNMP v2c만 지원한다. community가 필요하므로 Credential 관리가 따라온다.
- 향후 고려사항: SNMP v3는 Credential 모델이 달라 `server_snmp_configs`에 field를 더 넣어야 한다.
- 확신도: 높음
