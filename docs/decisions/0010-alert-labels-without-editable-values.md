# 0010. Alert label에는 사용자가 바꾸는 이름과 환경을 넣지 않는다

- 상태: 승인
- 날짜: 2026-10-29
- 관련: [요구사항 0012](../requirements/0012-check-label-continuity.md), [#22](https://github.com/subinggrae/server-monitor/issues/22), [ADR 0001](0001-alert-repeat-in-manager.md), [ADR 0007](0007-core-app-contract.md)

## 배경과 문제

앱은 HTTP SD label에 감시 항목 이름(`check_name`)과 환경(`environment`)을 넣는다. 코어의 Alert 규칙은 series label을 그대로 Alert label로 넘기고, Alertmanager는 `environment`로 Alert를 묶는다. Prometheus와 Alertmanager는 label 집합으로 Alert를 구분하므로, 대상이 계속 실패하는 동안 사용자가 이름이나 환경을 바꾸면 장애가 복구로 끝나고 새 장애로 다시 생긴다. 받는 그룹은 복구되지 않은 대상의 복구 알림과 새 장애 알림을 받는다(요구사항 0012의 배경, 개발 환경에서 확인).

이름과 환경은 사용자의 Grafana가 이 Prometheus 기록을 이름으로 보고 환경으로 거르는 데 쓴다. 그래서 SD label에서는 뺄 수 없다.

## 검토한 선택지

- **SD label에서 이름과 환경을 뺌**: series와 장애가 모두 이어진다 / Grafana에서 감시 항목을 이름과 환경으로 볼 수 없다.
- **Alert 규칙과 `group_by`에서만 이름과 환경을 빼고, 앱이 DB 값으로 채움**: 장애가 이어지고 Grafana 기록은 그대로다 / webhook label이 줄어 그 label을 읽던 앱이 깨진다. 이름·환경을 바꾸면 원래 series가 새로 시작하는 것은 남는다.
- **그대로 두고 앱이 복구와 새 장애를 하나로 합침**: 코어를 바꾸지 않는다 / 앱마다 같은 추측 로직이 필요하고, Alertmanager 쪽 장애는 여전히 끊긴다.

## 결정

Alert 규칙과 Alertmanager `group_by`에서만 이름과 환경을 뺀다. 장애를 만드는 규칙은 `max without (check_name, environment) (...)`로 두 label을 빼고 판단한다. 값을 바꾸면 옛 series는 바로 끝나고 새 series는 첫 수집 때 시작하므로, 지금 값이 없는 대상은 최근 5분 안의 마지막 값(`last_over_time(...[5m])`)으로 판단해 그 틈을 메운다(사람 확인: 2026-11-09). SD label은 그대로 둔다. Alert label에는 값이 바뀌지 않는 id(`check_id`, `check_target_id`), 확인 방식(`check_type`, `module`), 주소(`instance`), 수집 방식과 규칙이 정하는 값(`job`, `alertname`, `severity`)만 남는다. 앱은 알림의 감시 항목 이름과 환경을 `check_id`로 찾은 자기 DB 값으로 채운다.

앞으로 SD label에 사용자가 바꿀 수 있는 값을 더하면, 같은 방식으로 Alert 규칙과 `group_by`에서 뺀다.

## 결과

- 좋아지는 점: 이름과 환경을 바꿔도 장애가 하나로 이어지고, 알림에는 보낼 때의 DB 값이 보인다. Grafana가 읽는 기록에는 이름과 환경이 그대로 있다.
- 감수할 점: webhook과 `ALERTS` series에서 두 label이 빠져 그 label을 읽던 앱과 대시보드를 고쳐야 한다. 이 결정을 배포할 때 이어지던 장애가 한 번 끊긴다. 다만 이 결정 전에 운영에 배포한 코어가 없어 이 영향은 생기지 않는다(사람 확인: 2026-11-09). annotation에도 이름을 넣을 수 없다. 장애 중인 감시 항목이나 주소를 지우거나 끄면, Blackbox Exporter가 멈추면 장애가 최대 5분 뒤 복구로 끝난다(사람 확인: 2026-11-09). 대상이 다시 정상으로 동작하면 지금처럼 다음 규칙 평가에서 바로 복구된다. 수집 주기가 150초를 넘는 감시 항목은 값을 바꿀 때 끊길 수 있다(240초에서 약 28%로 추정, 사람 확인: 2026-11-09).
- 다시 볼 신호: 앱이 DB 없이 webhook만으로 알림을 만들어야 할 때, Grafana가 이 Prometheus 기록을 더는 읽지 않을 때

## 확인 방법

- `infrastructure/prometheus/tests/monitoring-checks.test.yml`(`promtool test rules`): 이름과 환경이 바뀌어도 수집 틈을 지나 Alert가 이어지고, Alert label에 두 label이 없다. 지운 대상은 5분 뒤, 살아난 대상은 바로 복구된다.
- `amtool check-config`와 리뷰에서 확인: `group_by`에 사용자가 바꾸는 label이 없다.
- 리뷰에서 확인: [관리자 서버 만들기](../ADMIN-SERVER.md)와 [INTEGRATION.md](../INTEGRATION.md)에 알림의 이름과 환경을 webhook label이 아니라 DB 값으로 채우라고 적혀 있다. 이 저장소의 Manager를 고치지 않기로 해 `AlertEventServiceTest`는 쓰지 않는다(사람 확인: 2026-11-09).
