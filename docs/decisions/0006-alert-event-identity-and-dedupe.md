# 0006. 장애 한 번을 fingerprint + startsAt으로 식별하고 중복 전송은 짧은 창으로 막는다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md 규칙 4), [notifications.md의 중복 전송 방지](../architecture/notifications.md#중복-전송-방지), [0005](0005-alertmanager-single-webhook-routing.md)

## 배경과 문제

Alertmanager는 같은 Alert를 반복해서 보낸다. `repeat_interval`마다 한 번 보내고, Webhook 응답이 늦으면 곧바로 한 번 더 보낸다.

받을 때마다 새 행을 만들면 Alert 이력을 쓸 수 없고, 받을 때마다 보내면 같은 알림이 두 번 간다. 반대로 너무 강하게 막으면 장애가 이어지는 동안의 정상적인 반복 알림까지 사라진다.

## 검토한 선택지

- **`fingerprint`만으로 Alert를 식별한다**: 단순하다 / 같은 대상이 나중에 다시 장애가 나면 이전 기록을 덮는다. 이력 정확도를 잃는다.
- **`fingerprint + status`를 영구 unique로 두어 중복 전송을 막는다**: 같은 알림을 절대 두 번 보내지 않는다 / 장애가 이어지는 동안 오는 정상적인 반복 알림까지 막힌다. 중복 방지와 반복 알림을 맞바꾼다.
- **`fingerprint + startsAt`으로 식별하고, 짧은 시간 창으로 중복 전송을 막는다**: 장애 한 번이 한 행이다. 재전송만 막고 반복 알림은 통과한다 / 창 크기를 Alertmanager 설정과 맞춰야 한다. 두 설정 사이에 암묵적인 의존이 생긴다.

## 결정

`fingerprint + startsAt`으로 식별하고, 짧은 시간 창으로 중복 전송을 막는다.

- `fingerprint`는 label 조합이라 같은 대상이면 같다. `startsAt`은 장애가 해소된 뒤 다시 발생하면 새 값이 된다.
- 같은 alert_event, 같은 그룹, 같은 status로 2분 안에 성공한 전송이 있으면 보내지 않는다.
- 실패한 전송은 세지 않는다. 다시 보낼 수 있어야 한다.
- 보내지 않은 건도 `notification_deliveries`에 `SKIPPED`로 남긴다.

## 결과

- 좋아지는 점: 왜 알림이 안 왔는지를 전송 기록으로 확인할 수 있다.
- 감수할 점: 중복 창(2분)은 `alertmanager.yml`의 `group_interval`(5분)보다 작아야 한다. 한쪽만 바꾸면 반복 알림이 막히거나 중복이 생긴다.
- 확신도: 높음

## 확인 방법

원래 기록에 없다.
