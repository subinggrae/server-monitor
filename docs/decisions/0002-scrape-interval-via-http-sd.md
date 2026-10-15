# 0002. 대상별 수집 주기는 HTTP SD의 `__scrape_interval__`로 전한다

- 상태: 승인
- 날짜: 2026-10-14
- 관련: [요구사항 0001](../requirements/0001-per-target-scrape-interval.md), 지운 계획은 [PR #2](https://github.com/subinggrae/server-monitor/pull/2)에서 볼 수 있다

## 배경과 문제

Check와 SNMP 서버마다 수집 주기를 다르게 정하고, 화면에서 바꾸면 Prometheus를 다시 띄우지 않고 반영해야 했다. 그전에는 `prometheus.yml`의 전역 `scrape_interval` 15초 하나를 모든 대상이 함께 썼다.

사용자가 화면에서 바꾼 설정 때문에 `infrastructure/`의 파일을 고치지 않는다는 경계가 있다([ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)). 또 SD label을 바꾸면 쌓아 온 time series가 끊긴다.

## 검토한 선택지

- **주기마다 job을 나눠 `prometheus.yml`에 적음**: Prometheus 기본 기능만 쓴다 / 허용 값마다 job이 생기고, 사용자 설정 때문에 `infrastructure/`를 고치고 reload해야 해서 경계를 어긴다.
- **SD endpoint를 주기마다 나눔**(`/sd/blackbox?interval=30s`): 결국 주기마다 job이 필요해 위와 같다.
- **일반 SD label을 더하고 relabel로 `__scrape_interval__`을 만듦**: 예약 label을 바로 주는 것보다 이점이 없고, 그 label이 time series에 남거나 따로 지워야 한다.
- **SD entry에 예약 label `__scrape_interval__`을 바로 넣음**: 재시작 없이 SD 갱신 주기(Check 15초, SNMP 30초) 안에 반영되고, `__`로 시작하는 label은 relabel 뒤 지워져 time series 이름표가 바뀌지 않는다 / Prometheus의 예약 label 동작에 기댄다.

## 결정

네 번째 선택지를 고른다. 수집 주기(10~240초 정수, 기본 15초)는 `monitoring_checks`와 `server_snmp_configs`의 `scrape_interval_seconds`에 두고, HTTP SD entry마다 `"__scrape_interval__": "{초}s"`로 넘긴다. `__scrape_timeout__`은 넣지 않는다. 주기 하한(10초)이 전역 `scrape_timeout`(10초)과 같아 "timeout은 주기보다 길 수 없다"는 조건을 항상 만족하기 때문이다. 상한 240초는 Prometheus 조회의 기본 lookback(5분)보다 짧게 두어 값이 비는 구간을 막는다.

## 결과

- 좋아지는 점: 화면에서 바꾼 주기가 재시작이나 reload 없이 반영되고, time series의 label 집합은 그대로다.
- 감수할 점:
  - Alert 규칙의 `for: 1m`은 주기와 관계없이 고정이라, 주기가 길수록 Alert가 늦게 온다(최대 수집 주기 + 1분).
  - `prometheus.yml`의 `scrape_timeout`을 주기 하한보다 길게 바꾸면 그보다 짧은 주기의 대상을 수집하지 않는다.
  - 주기를 바꾸는 순간 Prometheus가 대상을 새로 만들면서 짧은 공백이 생기는지는 확인하지 않았다. 공백이 생기면 pending 중인 Alert의 `for` 판단이 처음부터 다시 시작될 수 있다.
- 다시 볼 신호: 10초보다 짧거나 240초보다 긴 주기가 필요해질 때, Prometheus가 예약 label 동작을 바꿀 때

## 확인 방법

- `BlackboxServiceDiscoveryServiceTest`, `SnmpServiceDiscoveryServiceTest`, `MonitoringCheckRequestTest`, `ServerSnmpRequestTest`
- 리뷰에서 확인: `prometheus.yml`의 `scrape_timeout`이 수집 주기 하한(요청 DTO의 `@DecimalMin`)보다 길지 않은지
