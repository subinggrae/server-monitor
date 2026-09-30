# Grafana

상세 시계열 조회와 장기간 분석을 담당합니다.

Monitoring Manager는 설정, 현재 상태, 알림, 간단한 요약까지만 보여 줍니다.
깊이 있는 metric 분석은 Grafana에서 합니다.

## 현재 상태

아직 설정하지 않았습니다. 이 디렉터리는 다음 단계를 위해 미리 만들어 둔 것입니다.

## 다음 단계에서 할 일

1. `provisioning/datasources/prometheus.yml` 작성
   - Prometheus를 기본 datasource로 등록합니다.
2. `provisioning/dashboards/` 아래에 기본 Dashboard 추가
   - 서버 가용성, 리소스, DB Dashboard를 준비합니다.
3. `compose.yml`에 `grafana` service 추가

## 주의할 점

Monitoring Manager는 Grafana Dashboard를 자동으로 만들지 않습니다.
Manager 화면에서는 해당 대상의 Dashboard로 이동하는 링크만 제공합니다.
