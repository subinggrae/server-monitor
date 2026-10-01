# 0001. Prometheus Target을 HTTP Service Discovery로 관리한다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md 규칙 1, 2), [checks.md의 Prometheus SD 응답](../architecture/checks.md#prometheus-sd-응답), [0002](0002-sd-entry-per-check-target.md)

## 배경과 문제

운영자는 웹 화면에서 모니터링 대상을 등록하고 지운다. Prometheus는 그 대상을 알아야 수집할 수 있다.

`prometheus.yml`은 배포용 설정이다. 사용자 설정 때문에 이 파일을 고치면 안 된다. Target을 바꿀 때마다 Prometheus를 다시 시작하는 것도 운영에서 받아들이기 어렵다.

## 검토한 선택지

- **Manager가 `prometheus.yml` 또는 `file_sd_configs` 파일을 고친다**: Prometheus 기본 기능만 쓴다 / 사용자 설정이 배포용 파일에 섞이고, 파일과 Manager Database 내용이 어긋날 수 있다. 구현이 단순한 대신 설정의 기준이 두 곳으로 나뉜다.
- **Manager가 HTTP Service Discovery API를 제공한다**: Target 목록의 기준이 Manager Database 하나다. Prometheus를 다시 시작하지 않는다 / Prometheus가 Manager API에 의존한다. 반영까지 SD 조회 주기만큼 늦어진다.

## 결정

HTTP Service Discovery API를 제공한다.

Manager가 `GET /api/prometheus/sd/<exporter>` 형태로 Target 목록을 제공하고, Prometheus는 `http_sd_configs`로 이 API를 주기적으로 조회한다. `file_sd_configs`와 `targets/*.yml`은 쓰지 않는다.

## 결과

- 좋아지는 점: Target을 추가하거나 지워도 설정 파일 수정과 Prometheus 재시작이 필요 없다.
- 감수할 점: 변경은 SD 조회 주기(blackbox 15초, snmp 30초)와 scrape 주기가 지나야 반영된다. SD API는 조회마다 Manager Database를 읽으므로 응답 시간을 관리해야 한다.
- 향후 고려사항: Database Target도 같은 방식(`/api/prometheus/sd/mariadb`)으로 제공한다.
- 확신도: 높음
