# 알림

Prometheus Alert가 Alertmanager를 거쳐 Telegram 그룹에 닿는 경로, 저장하는 데이터, 중복 방지 규칙, Telegram 그룹 등록과 알림 경로 확인 절차를 설명한다. 전체 구조와 경계는 [ARCHITECTURE.md](../ARCHITECTURE.md)에 있다.

## 경로

Alertmanager 설정에 Telegram receiver를 그룹마다 만들면 사용자가 그룹을 추가할 때마다 `alertmanager.yml`을 고쳐야 한다. 사용자 설정 때문에 배포용 설정 파일을 고치지 않고 알림 대상은 Manager Database가 정한다는 경계와 충돌한다. 그래서 Alertmanager는 Manager webhook 하나로만 보내고, 누구에게 보낼지는 Manager가 정한다. ([ADR 0005](../decisions/0005-alertmanager-single-webhook-routing.md))

```text
Prometheus Alert Rule
  ↓ Alert 발생
Alertmanager                          receiver는 manager-webhook 하나뿐이다
  ↓ POST /api/integrations/alertmanager/webhook   (Bearer token 검증)
AlertEvent 저장                        fingerprint + startsAt 기준
  ↓ check_id로 Check 조회
Notification Binding 조회              Check에 연결된 Telegram 그룹
  ↓ severity와 sendResolved 확인
Telegram Destination
  ↓ Telegram Bot API
Telegram Group
```

- Alertmanager는 Telegram Bot Token을 받지 않는다. Telegram 전송은 Manager가 맡는다.
- 알림 연결은 Monitoring Check에만 붙는다. Server Resource와 Database Target은 그 기능을 만들 때 추가한다.

## 저장하는 데이터

| Table | 담는 것 |
|---|---|
| `telegram_destinations` | 알림을 받을 Telegram 그룹(chat id, topic) |
| `monitoring_check_notification_bindings` | Check와 그룹의 연결. severity와 복구 알림 설정 |
| `alert_events` | 받은 Alert. 장애 한 번이 한 행 |
| `notification_deliveries` | Telegram 전송 결과 |

Bot Token은 어느 table에도 저장하지 않는다. Docker Secret이나 환경 변수로만 받는다. Telegram 그룹의 chat id는 secret 파일에 두지 않고 화면에서 등록한다.

## 장애 한 번을 한 행으로 유지

Alertmanager는 같은 Alert를 반복해서 보낸다. `repeat_interval`(1시간)마다 한 번 보내고, webhook 응답이 늦으면 곧바로 한 번 더 보낸다. 받을 때마다 새 행을 만들면 이력을 쓸 수 없어 `fingerprint`와 `startsAt`을 함께 쓴다.

- `fingerprint`는 label 조합이라 같은 대상이면 계속 같다.
- `startsAt`은 장애가 한 번 해소된 뒤 다시 발생하면 새 값이 된다.

두 값을 합치면 장애 한 번이 한 행이 된다. ([ADR 0006](../decisions/0006-alert-event-identity-and-dedupe.md))

## 중복 전송 방지

`fingerprint + status`를 영구 unique로 두면 장애가 이어지는 동안 오는 정상적인 반복 알림까지 막힌다. 대신 짧은 시간 창을 쓴다.

```text
같은 alert_event + 같은 그룹 + 같은 status로
dedupe window(기본 2분) 안에 이미 성공한 전송이 있으면 보내지 않는다
```

- 창 크기(`NOTIFICATION_DEDUPE_WINDOW`)는 `alertmanager.yml`의 `group_interval`(5분)보다 작아야 한다. 그래야 Alertmanager의 정상적인 반복 알림을 막지 않는다. 한쪽만 바꾸면 반복 알림이 막히거나 중복이 생긴다.
- 실패한 전송은 세지 않는다. 다시 보낼 수 있어야 한다.
- 보내지 않은 건도 `notification_deliveries`에 `SKIPPED`로 남긴다. 왜 알림이 안 왔는지 확인할 수 있어야 한다.

## 실패와 예외 처리

- Telegram 전송이 실패해도 webhook 응답은 성공으로 돌려준다. 실패를 돌려주면 Alertmanager가 같은 payload를 계속 재시도한다.
- 전송 실패는 `notification_deliveries`에 `FAILED`로 기록한다. Manager가 스스로 다시 보내지 않고, 다음 재시도는 Alertmanager의 반복 알림이 가져온다. 그래서 실패하면 다음 반복 알림(1시간)까지 알림이 늦어질 수 있다. 알림 전송에 Message Queue를 쓰지 않는다. ([ADR 0007](../decisions/0007-no-message-queue-for-notifications.md))
- 연결이 없는 Check의 Alert는 어느 그룹에도 보내지 않는다. 기본 그룹을 몰래 정해 두면 운영자가 모르는 곳으로 장애 알림이 간다.
- 모르는 severity는 보내지 않고 기록만 한다. 임의로 critical로 바꾸지 않는다.
- 지워진 Check의 Alert가 뒤늦게 올 수 있다. 이때도 요청 전체를 실패시키지 않고 기록만 남긴 뒤 전송을 건너뛴다.
- Telegram 메시지는 parse mode 없이 plain text로 보낸다. 주소의 밑줄이나 괄호를 서식으로 읽어 메시지 전체가 거부되는 일을 막는다. ([ADR 0008](../decisions/0008-telegram-plain-text-messages.md))

## Telegram 그룹 등록 방식

| 방법 | 필요한 것 |
|---|---|
| 그룹에서 `/register` 전송 | Telegram이 접근할 수 있는 외부 HTTPS 주소(`TELEGRAM_WEBHOOK_PUBLIC_URL`) |
| 화면에서 Chat ID 직접 입력 | 없음 |

`/register`는 Telegram 서버가 Manager를 직접 부르는 방식이라 `http://manager-api:8080` 같은 내부 주소로는 받을 수 없다. 외부 주소가 없는 환경에서는 화면에서 Chat ID를 직접 등록한다. Bot Token은 어느 방법에서도 사용자가 입력하지 않는다.

## Telegram 그룹 등록

Telegram 그룹을 등록하고 Check에 연결해 장애 시 그 그룹으로 알림이 가게 하는 운영 절차다.

- 도구: Monitoring Manager 화면, Telegram
- 권한: Telegram 그룹에 Bot을 초대할 권한
- 담당자와 에스컬레이션 경로: 아직 정하지 않았다

1. Bot을 알림 받을 Telegram 그룹에 초대한다.
2. 그룹을 등록한다. 두 방법 가운데 하나를 쓴다.
   - `/register`: 그룹에서 `/register`를 보낸다. `.env`의 `TELEGRAM_WEBHOOK_PUBLIC_URL`에 Telegram 서버가 접근할 수 있는 외부 HTTPS 주소가 있어야 한다.
   - 직접 입력: **알림 > Telegram 그룹**에서 Chat ID를 입력한다. 외부 주소가 없을 때 쓴다.
3. 등록한 그룹에서 **테스트 메시지**를 보내 도착하는지 확인한다.
4. **모니터링 > Check** 상세 화면의 **알림** section에서 그룹을 연결한다. 그룹마다 Warning·Critical 수신과 복구 알림 여부를 정한다.
5. **알림 > Telegram 그룹** 화면 아래쪽 목록에서 알림 대상이 없는 Check가 남아 있는지 확인한다.

| 증상 | 확인할 것 |
|---|---|
| `/register`를 보내도 그룹이 나타나지 않음 | `TELEGRAM_WEBHOOK_PUBLIC_URL`이 비어 있지 않은지, 외부에서 HTTPS로 접근되는지 확인한다. 외부 주소가 없으면 Chat ID를 직접 입력한다 |
| 테스트 메시지가 오지 않음 | Bot이 그룹에 있는지, `infrastructure/secrets/telegram_bot_token` 값이 맞는지 확인한다 |
| 장애가 났는데 알림이 오지 않음 | Check에 그룹이 연결되어 있는지 확인한다. 연결이 없으면 보내지 않는다. **모니터링 > Alert**에 Alert가 기록됐는지도 확인한다 |

해결할 수 없을 때 연락할 곳은 아직 정하지 않았다. 담당 팀을 정해야 한다.

## 알림 경로 확인

실제 장애를 만들지 않고 webhook 인증, Alert 저장, Telegram 전송이 동작하는지 확인하는 운영 절차다.

- 도구: curl
- 권한: 배포 서버 shell 접근, `infrastructure/secrets/alertmanager_webhook_token` 읽기 권한
- 담당자와 에스컬레이션 경로: 아직 정하지 않았다

> **실패하는 Check로 알림 경로를 확인하지 않는다.** 1분 넘게 실패하면 실제 Alert가 발생하고, 그 Check에 연결된 운영 그룹으로 진짜 장애 알림이 간다.

1. token 없이 호출하면 401인지 확인한다.

   ```sh
   curl -s -o /dev/null -w '%{http_code}\n' \
     -X POST http://localhost:8080/api/integrations/alertmanager/webhook \
     -H 'Content-Type: application/json' -d '{"alerts":[]}'
   ```

2. 알림 그룹을 연결하지 않은 Check의 id로 Alert 하나를 직접 넣는다.

   ```sh
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

3. Manager가 Alert를 저장했는지 확인한다.

   ```sh
   curl -s http://localhost:8080/api/alerts
   ```

4. Telegram 전송은 테스트 메시지로 확인한다. `1`은 Telegram 그룹 id다.

   ```sh
   curl -s -X POST http://localhost:8080/api/telegram/destinations/1/test
   ```

확인용 Alert는 Alert 이력에 남는다. 화면이나 API로 지우는 기능은 없다.

| 증상 | 확인할 것 |
|---|---|
| 올바른 token으로도 401 | `alertmanager_webhook_token` 파일이 `manager-api`와 `alertmanager`에 같은 값으로 들어갔는지 확인한다 |
| Alert가 저장되지 않음 | `check_id`가 실제 Check id인지 확인한다. 없는 Check의 Alert는 기록만 하고 전송을 건너뛴다 |
| 테스트 메시지 실패 | [Telegram 그룹 등록](#telegram-그룹-등록)의 확인 표를 따른다 |

해결할 수 없을 때 연락할 곳은 아직 정하지 않았다. 담당 팀을 정해야 한다.

실제 Telegram 그룹으로의 전송과 `/register`는 운영 환경에서 확인한 기록이 없다. Mock Telegram API(`TELEGRAM_API_BASE_URL`)로 경로를 확인했다.
