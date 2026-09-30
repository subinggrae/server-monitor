# 알림 경로 확인

## Runbook 정보

| 항목 | 내용 |
|---|---|
| Runbook ID | `RUN-005` |
| 목적 / 기대 결과 | 실제 장애를 만들지 않고 Webhook 인증, Alert 저장, Telegram 전송이 동작하는 것을 확인한 상태 |
| 사용 도구 | curl |
| 필요한 권한 | 배포 서버 shell 접근, `infrastructure/secrets/alertmanager_webhook_token` 읽기 권한 |
| 담당자 | 미정 |
| 마지막 수정일 | 2026-09-28 |
| 에스컬레이션 | 미정 |

> **실패하는 Check로 알림 경로를 확인하지 마세요.**
> 1분 넘게 실패하면 실제 Alert가 발생하고, 그 Check에 연결된 운영 그룹으로 진짜 장애 알림이 갑니다.

## 절차

1. token 없이 호출하면 401인지 확인합니다.

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' \
     -X POST http://localhost:8080/api/integrations/alertmanager/webhook \
     -H 'Content-Type: application/json' -d '{"alerts":[]}'
   ```

2. 알림 그룹을 연결하지 않은 Check의 id로 Alert 하나를 직접 넣습니다.

   ```bash
   TOKEN=$(cat infrastructure/secrets/alertmanager_webhook_token)

   curl -s -X POST http://localhost:8080/api/integrations/alertmanager/webhook \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"version":"4","groupKey":"test","status":"firing","alerts":[
           {"status":"firing",
            "labels":{"alertname":"MonitoringCheckDown","check_id":"1","severity":"critical",
                      "check_name":"확인용","environment":"Development",
                      "instance":"http://10.0.0.99:8080/health"},
            "annotations":{"summary":"확인용 Alert입니다"},
            "startsAt":"2026-09-23T00:00:00Z","endsAt":"0001-01-01T00:00:00Z",
            "fingerprint":"manual-check-1"}]}'
   ```

3. Manager가 Alert를 저장했는지 확인합니다.

   ```bash
   curl -s http://localhost:8080/api/alerts
   ```

4. Telegram 전송은 테스트 메시지로 확인합니다. `1`은 Telegram 그룹 id입니다.

   ```bash
   curl -s -X POST http://localhost:8080/api/telegram/destinations/1/test
   ```

확인용 Alert는 Alert 이력에 남습니다. 화면이나 API로 지우는 기능은 없습니다.

## 오류 처리

- 올바른 token으로도 401 → `alertmanager_webhook_token` 파일이 manager-api와 alertmanager에 같은 값으로 들어갔는지 확인합니다.
- Alert가 저장되지 않음 → `check_id`가 실제 Check id인지 확인합니다. 없는 Check의 Alert는 기록만 하고 전송을 건너뜁니다.
- 테스트 메시지 실패 → [RUN-003](telegram-group-registration.md)의 오류 처리를 따릅니다.
- 해결할 수 없는 경우 → 미정
