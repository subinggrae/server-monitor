# 0008. 리소스 임계값은 앱이 Prometheus의 사용률을 읽어 판단한다

- 상태: 승인
- 날짜: 2026-10-19
- 관련: [요구사항 0009](../requirements/0009-resource-threshold-schema.md), [#23](https://github.com/subinggrae/server-monitor/issues/23), [ADR 0001](0001-alert-repeat-in-manager.md), [ADR 0005](0005-common-resource-recording-rules.md), [ADR 0007](0007-core-app-contract.md)

## 배경과 문제

사용자가 화면에서 서버 Resource마다 Warning·Critical 임계값과 지속 시간을 정하고, 사용률이 그 값을 넘으면 알림을 받아야 한다. 사용자 설정 때문에 `infrastructure/`의 Alert 규칙을 고치지 않는다는 경계가 있다([ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)). 그리고 [ADR 0007](0007-core-app-contract.md)의 통합 계약에서 코어는 앱의 metric을 수집하지 않는다. 판단과 발송은 코어를 붙일 다른 웹이 만든다.

## 검토한 선택지

- **임계값을 HTTP SD label로 넘김**: SD 경로를 그대로 쓴다 / PromQL은 label 값을 숫자로 비교하지 못하고, target label은 디스크마다 다른 값을 줄 수 없다. label을 더하면 서버의 time series가 끊긴다.
- **앱이 임계값을 metric으로 내보내고 범용 Alert 규칙이 비교함**: Prometheus의 판정과 Alertmanager webhook, 재발송 경로를 그대로 쓴다 / 코어에 앱 metric 수집 job을 더해야 해서 통합 계약이 바뀐다. 지속 시간은 규칙마다 고정이라 단계로만 고르고, 앱이 멈추면 임계값 series가 사라져 발생 중인 Alert가 복구로 바뀐다.
- **앱이 사용률을 읽어 직접 판단함**: 코어와 통합 계약의 코어 설정이 바뀌지 않고, 지속 시간을 분 단위로 자유롭게 정한다 / 대기, 발생, 복구 판정과 다시 띄운 뒤 이어 가기를 앱이 만들어야 한다. Check 알림(Alertmanager 경유)과 리소스 알림(앱 판단)의 흐름이 둘로 나뉜다.

## 결정

세 번째 선택지를 고른다(사람 확인: 2026-10-19). 앱은 서버의 수집 주기마다 `manager:resource_usage_percent{server_id, resource_type, resource_key}`를 Prometheus HTTP API로 읽어 Resource의 단계마다 임계값과 비교한다. 판단 상태(넘기 시작한 시각, 발생 여부, 마지막 발송 시각)는 앱의 DB에 두어 다시 띄워도 이어 간다. 판단 규칙과 참고용 DDL은 통합 계약([INTEGRATION.md](../INTEGRATION.md))에 둔다.

코어를 여러 앱이 같이 쓰려면 코어가 앱마다 다른 사용자 설정을 몰라야 한다. 그래서 사용자 설정에 따라 바뀌는 판단은 앱 쪽에 둔다. 재발송 판단을 Manager에 둔 [ADR 0001](0001-alert-repeat-in-manager.md)과 같은 방향이다.

## 결과

- 좋아지는 점: 코어가 바뀌지 않고, 임계값과 지속 시간을 바꾸면 다음 판단부터 반영된다. 지속 시간이 규칙의 단계에 묶이지 않는다.
- 감수할 점:
  - 판단 상태와 재시작 처리를 앱마다 만들어야 한다. 판단 규칙을 통합 계약에 자세히 적어 앱마다 다르게 만들지 않게 한다.
  - 앱이 멈춘 동안에는 리소스 알림이 판단되지 않는다. Check 알림은 Alertmanager가 앱이 다시 뜬 뒤 보내지만, 리소스 알림은 다시 뜬 뒤 첫 판단부터 이어진다.
  - 앱을 여러 인스턴스로 띄우면 같은 Resource를 두 번 판단할 수 있다.
- 다시 볼 신호: 리소스 알림을 판단하는 앱이 둘 이상 생길 때, 앱을 여러 인스턴스로 띄울 때, 앱이 멈춘 동안의 리소스 알림이 필요해질 때

## 확인 방법

- 리뷰에서 확인: `infrastructure/prometheus/rules/`에 리소스 임계값 Alert 규칙이 없고, 사용자 설정을 바꾸는 코드가 `infrastructure/`를 쓰지 않는지 본다.
- `ReferenceSchemaIntegrationTest`: 참고용 DDL이 판단 상태를 담고 두 DB에 적용되는지 본다.
