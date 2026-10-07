# 0005. Alertmanager는 Manager Webhook 하나로 보내고 알림 대상은 Manager가 정한다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md 규칙 3, 4), [notifications.md의 경로](../architecture/notifications.md#경로), [0006](0006-alert-event-identity-and-dedupe.md), [0007](0007-no-message-queue-for-notifications.md), [0008](0008-telegram-plain-text-messages.md)

## 배경과 문제

운영자는 화면에서 Telegram 그룹을 추가하고 Check에 연결한다.

Alertmanager가 Telegram으로 직접 보내면 그룹마다 receiver가 필요하다. 그룹을 추가할 때마다 `alertmanager.yml`을 고치고 Alertmanager를 다시 띄워야 한다. 일반 운영자가 할 수 있는 일이 아니다.

## 검토한 선택지

- **Alertmanager가 Telegram receiver를 그룹마다 가진다**: Alertmanager 기본 기능만 쓴다 / 그룹 추가가 설정 파일 수정이다. 라우팅 정보(`service`, `notification_group`)를 Prometheus label에 넣어야 한다. 구현이 단순한 대신 사용자 설정이 배포용 파일에 섞인다.
- **Alertmanager는 Manager Webhook 하나만 알고, Manager가 Database에서 대상을 찾는다**: 그룹 추가와 연결이 Manager Database 변경만으로 끝난다. label이 라우팅 정보를 갖지 않는다 / Manager가 Alert 저장, 대상 조회, Telegram 전송을 모두 맡아 책임이 늘어난다.

## 결정

Alertmanager는 Manager Webhook 하나만 알고, Manager가 Database에서 대상을 찾는다.

```text
Prometheus → Alertmanager → Manager Webhook
→ Notification Binding 조회 → Telegram Bot API
```

- Webhook은 Bearer token(`alertmanager_webhook_token`)으로 검증한다.
- Manager는 `check_id` label로 Check를 찾고, Check에 연결된 그룹에만 보낸다.
- 연결이 없으면 보내지 않는다. 기본 그룹을 두지 않는다.
- Telegram 전송이 실패해도 Webhook은 성공을 돌려준다. 실패를 돌려주면 Alertmanager가 같은 payload를 계속 재시도한다.
- `service`, `notification_group` label은 제거했다.

## 결과

- 좋아지는 점: `alertmanager.yml`에 Telegram 설정이 없다. Bot Token도 Alertmanager에 주지 않는다.
- 감수할 점: label 구성이 바뀌어 `probe_success` time series가 한 번 끊겼다. 이전 데이터는 Prometheus 보존 기간(15일) 동안 남는다.
- 향후 고려사항: 알림 연결은 지금 Monitoring Check에만 붙는다. Server Resource와 Database Target은 그 기능을 만들 때 추가한다.
- 확신도: 높음. 실제 Telegram 그룹으로의 전송과 `/register`는 운영 환경에서 확인하지 못했다. Mock Telegram API로 경로를 확인했다.

## 확인 방법

원래 기록에 없다.
