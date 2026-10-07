# 0002. Blackbox SD entry를 Check Target 단위로 만든다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md 규칙 2-1), [checks.md의 Prometheus SD 응답](../architecture/checks.md#prometheus-sd-응답), [checks.md의 Target과 Check 상태](../architecture/checks.md#target과-check-상태), [0001](0001-prometheus-http-service-discovery.md), [0003](0003-separate-check-from-server.md)

## 배경과 문제

하나의 Monitoring Check는 확인 주소(Target)를 여러 개 가진다. 화면에서는 어느 주소가 문제인지 따로 보여 줘야 한다.

Prometheus SD 응답의 entry 하나는 `targets` 배열에 주소를 여러 개 담을 수 있지만, 같은 entry의 주소는 label을 모두 공유한다.

## 검토한 선택지

- **Check 하나를 entry 하나로 만들고 주소를 `targets` 배열에 넣는다**: 응답이 짧다 / 주소마다 다른 식별자 label을 붙일 수 없어 주소 단위 상태를 구분할 수 없다. 응답 크기와 상태 구분 능력을 맞바꾼다.
- **Target 하나를 entry 하나로 만든다**: 주소마다 `check_target_id`를 붙일 수 있다 / 같은 Check의 label 값이 entry마다 반복된다. 응답이 길어지는 것을 감수한다.

## 결정

Target 하나를 entry 하나로 만든다.

주소 단위 상태를 보여 주려면 안정적인 식별자가 필요하다. 같은 Check에 속한다는 것은 `check_id` label로 안다.

## 결과

- 좋아지는 점: Backend는 `probe_success`를 한 번 조회하고 `check_target_id`로 묶어 모든 Target 상태를 계산한다.
- 감수할 점: Target id가 바뀌면 time series가 끊긴다. 그래서 Check 수정 API는 기존 Target의 `id`를 유지해야 한다.
- 확신도: 높음

## 확인 방법

원래 기록에 없다.
