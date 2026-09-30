# Telegram 알림 그룹 등록

## Runbook 정보

| 항목 | 내용 |
|---|---|
| Runbook ID | `RUN-003` |
| 목적 / 기대 결과 | Telegram 그룹이 등록되고 Check에 연결되어, 장애 시 해당 그룹으로 알림이 가는 상태 |
| 사용 도구 | Monitoring Manager 화면, Telegram |
| 필요한 권한 | Telegram 그룹에 Bot을 초대할 권한 |
| 담당자 | 미정 |
| 마지막 수정일 | 2026-09-28 |
| 에스컬레이션 | 미정 |

## 절차

1. Bot을 알림 받을 Telegram 그룹에 초대합니다.
2. 그룹을 등록합니다. 두 방법 중 하나를 씁니다.
   - `/register`: 그룹에서 `/register`를 보냅니다.
     `.env`의 `TELEGRAM_WEBHOOK_PUBLIC_URL`에 Telegram 서버가 접근할 수 있는 외부 HTTPS 주소가 있어야 합니다.
   - 직접 입력: **알림 > Telegram 그룹**에서 Chat ID를 입력합니다. 외부 주소가 없을 때 씁니다.
3. 등록한 그룹에서 **테스트 메시지**를 보내 도착하는지 확인합니다.
4. **모니터링 > Check** 상세 화면의 **알림** section에서 그룹을 연결합니다.
   그룹마다 Warning / Critical 수신과 복구 알림 여부를 정합니다.
5. **알림 > Telegram 그룹** 화면 아래쪽 목록에서 알림 대상이 없는 Check가 남아 있는지 확인합니다.

## 오류 처리

- `/register`를 보내도 그룹이 나타나지 않음 → `TELEGRAM_WEBHOOK_PUBLIC_URL`이 비어 있지 않은지, 외부에서 HTTPS로 접근되는지 확인합니다.
  외부 주소가 없으면 Chat ID를 직접 입력합니다.
- 테스트 메시지가 오지 않음 → Bot이 그룹에 있는지, `infrastructure/secrets/telegram_bot_token` 값이 맞는지 확인합니다.
- 장애가 났는데 알림이 오지 않음 → Check에 그룹이 연결되어 있는지 확인합니다. 연결이 없으면 보내지 않습니다.
  **모니터링 > Alert**에 Alert가 기록됐는지도 확인합니다.
- 해결할 수 없는 경우 → 미정
