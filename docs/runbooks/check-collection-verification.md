# Check 수집 반영 확인

## Runbook 정보

| 항목 | 내용 |
|---|---|
| Runbook ID | `RUN-004` |
| 목적 / 기대 결과 | 등록한 Check가 Prometheus에 발견되고 probe 결과가 화면 상태에 반영된 것을 확인한 상태 |
| 사용 도구 | curl, Docker |
| 필요한 권한 | 배포 서버 shell 접근 (포트는 `127.0.0.1`에만 열립니다) |
| 담당자 | 미정 |
| 마지막 수정일 | 2026-09-28 |
| 에스컬레이션 | 미정 |

## 절차

1. Service Discovery 응답에 Check Target이 있는지 확인합니다.

   ```bash
   curl -s http://localhost:8080/api/prometheus/sd/blackbox
   ```

2. 30초 정도 기다린 뒤 Prometheus가 Target을 발견했는지 확인합니다.
   SD 조회 주기(15초)와 scrape 주기(15초)가 지나야 합니다.

   ```bash
   curl -s 'http://localhost:9090/api/v1/targets?state=active'
   ```

3. probe 결과를 확인합니다.

   ```bash
   curl -s --get http://localhost:9090/api/v1/query \
     --data-urlencode 'query=probe_success{job="manager-blackbox"}'
   ```

4. Backend가 계산한 Check 상태를 확인합니다. `1`은 Check id입니다.

   ```bash
   curl -s http://localhost:8080/api/checks/1/status
   ```

## 오류 처리

- SD 응답에 Target이 없음 → Check와 Target이 모두 켜져 있는지 확인합니다. 꺼 둔 것은 응답에서 빠집니다.
- Prometheus가 Target을 발견하지 못함 → Prometheus container에서 `manager-api:8080`에 접근되는지 확인합니다.
- `probe_success`가 0 → Blackbox module만 따로 호출해 대상 문제인지 확인합니다.

  ```bash
  docker exec prometheus wget -qO- \
    'http://blackbox-exporter:9115/probe?module=icmp&target=manager-api' | grep probe_success
  ```

- 상태가 계속 "알 수 없음" → Prometheus가 떠 있는지 확인합니다. Prometheus에 연결하지 못하면 설정은 보이고 상태만 `UNKNOWN`이 됩니다.
- 해결할 수 없는 경우 → 미정
