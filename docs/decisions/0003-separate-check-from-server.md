# 0003. Monitoring Check를 Server에서 분리한다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md 규칙 2-1), [checks.md의 도메인 구조](../architecture/checks.md#도메인-구조), [0002](0002-sd-entry-per-check-target.md)

## 배경과 문제

같은 애플리케이션이 서버 여러 대에서 동작하는 경우가 많다. 운영자는 이것을 "전자결재 Health" 같은 Check 하나로 관리하려 한다.

Check를 특정 서버의 하위 항목으로 두면, 다른 서버에 있는 주소의 소속이 맞지 않게 된다.

## 검토한 선택지

- **Check를 Server에 종속시킨다(`monitoring_checks.server_id`)**: 서버별로 Check를 모아 보기 쉽다 / 여러 서버에 걸친 Check를 표현할 수 없다. 서버를 지우면 Check도 영향을 받는다. 서버 중심 탐색과 실제 서비스 구조를 맞바꾼다.
- **Check와 Server를 서로 다른 도메인으로 둔다**: Server 없이 Check를 만들 수 있고, Server를 지워도 모니터링이 멈추지 않는다 / 어떤 서버에 어떤 Check가 있는지 자동으로 알 수 없다. 연결 정보가 필요해지면 나중에 추가해야 한다.

## 결정

Check와 Server를 서로 다른 도메인으로 둔다.

`monitoring_checks`에서 `server_id`를 제거했고, Check 쪽 table과 `server_targets` 사이에 FK를 두지 않는다. Server 상태도 Blackbox Check 결과로 계산하지 않는다. SNMP 수집 결과로만 판단한다.

## 결과

- 좋아지는 점: 서버 정리 때문에 모니터링이 조용히 멈추는 사고가 없다.
- 감수할 점: Check Target과 등록된 서버를 연결하는 기능이 없다.
- 향후 고려사항: 필요성이 확인되면 `MonitoringCheckTarget → optional ServerTarget` 관계를 검토한다.
- 확신도: 높음
