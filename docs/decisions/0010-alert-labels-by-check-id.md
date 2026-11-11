# 0010. 감시 항목 Alert는 감시 항목과 주소의 id로만 묶는다

- 상태: 제안
- 날짜: 2026-11-12
- 관련: [요구사항 0012](../requirements/0012-check-label-continuity.md), [#22](https://github.com/subinggrae/server-monitor/issues/22), [ADR 0001](0001-alert-repeat-in-manager.md), [ADR 0007](0007-core-app-contract.md), [PR #43](https://github.com/subinggrae/server-monitor/pull/43)(이전 구현과 확인 기록)

## 배경과 문제

관리자 서버는 HTTP SD label에 감시 항목 이름(`check_name`), 환경(`environment`), 확인 방식(`check_type`, `module`)을 넣고, Prometheus는 주소를 `instance` label로 붙인다. 코어의 Alert 규칙은 series label을 그대로 Alert label로 넘기고, Alertmanager는 `environment`로 Alert를 묶는다. Prometheus와 Alertmanager는 label 집합으로 Alert를 구분하므로, 대상이 계속 실패하는 동안 사용자가 이 값들을 바꾸면 장애가 복구로 끝나고 새 장애로 다시 생긴다. 받는 그룹은 복구되지 않은 대상의 복구 알림과 새 장애 알림을 받는다(요구사항 0012의 배경, 개발 환경에서 확인).

이름과 환경은 사용자의 Grafana가 이 Prometheus 기록을 이름으로 보고 환경으로 거르는 데 쓴다. 그래서 SD label에서는 뺄 수 없다.

## 검토한 선택지

- **SD label에서 사용자가 바꾸는 값을 뺌**: series와 장애가 모두 이어진다 / 쌓인 time series가 한 번 끊기고, Grafana에서 감시 항목을 이름과 환경으로 볼 수 없다.
- **Alert 규칙과 `group_by`에서 이름과 환경만 뺌**(PR #43): Alert에 주소와 확인 방식이 남는다 / 확인 방식과 주소를 바꿀 때 장애가 여전히 끊긴다.
- **Alert 규칙을 `check_id`, `check_target_id`로만 묶음**: 이름·환경·확인 방식·주소를 바꿔도 장애가 이어지고 Grafana 기록은 그대로다 / webhook label이 줄어 관리자 서버가 이름·환경·주소를 DB에서 채워야 한다.
- **그대로 두고 관리자 서버가 복구와 새 장애를 하나로 합침**: 코어를 바꾸지 않는다 / 관리자 서버마다 같은 처리를 만들어야 하고 예외가 많다. Alertmanager 쪽 장애는 여전히 끊긴다.

## 결정

감시 항목의 두 Alert 규칙을 `check_id`, `check_target_id`로만 묶는다(사람 확인: 2026-11-11). 지금 값으로 판단하는 식과 최근 5분의 마지막 값(`last_over_time(...[5m])`)으로 판단하는 식을 `or`로 합친다.

- 지금 값의 식: 장애가 없을 때 값을 바꾼 직후의 실패를 늦지 않게 잡는다(사람 확인: 2026-11-12).
- 최근 5분의 식: 값을 바꾸면 옛 series는 바로 stale로 끝나고 새 series는 첫 수집 때 시작하므로, 그 틈을 메운다. 5분은 가장 긴 수집 주기(240초)보다 길게 잡은 값이다.

Alertmanager `group_by`는 `alertname`, `check_id`다. SD label은 그대로 둔다. Alert label에는 두 id와 규칙이 정하는 `alertname`, `severity`만 남고, annotation에는 이름·환경·주소·확인 방식을 넣지 않는다. 관리자 서버는 알림의 감시 항목 이름, 환경, 주소를 `check_id`, `check_target_id`로 찾은 자기 DB 값으로 채운다.

앞으로 SD label에 값을 더해도 Alert 규칙과 `group_by`에는 넣지 않는다.

## 결과

- 좋아지는 점: 이름, 환경, 확인 방식, 주소를 바꿔도 장애가 하나로 이어지고, 알림에는 보낼 때의 DB 값이 보인다. Grafana가 읽는 기록에는 이름과 환경이 그대로 있다.
- 감수할 점:
  - webhook과 `ALERTS` series에서 id 밖의 label이 빠져 그 label을 읽던 관리자 서버와 대시보드를 고쳐야 한다. 이 결정 전에 운영에 배포한 코어가 없어 배포 때 끊기는 장애는 없다(사람 확인: 2026-11-12).
  - 장애 중인 감시 항목이나 주소를 지우거나 끄면, Blackbox Exporter가 멈추면 장애가 최대 5분 뒤 복구로 끝나고, 그 사이 재발송 알림이 한 번 더 갈 수 있다(사람 확인: 2026-11-09, 2026-11-11). 대상이 다시 정상으로 동작하면 다음 규칙 평가에서 바로 복구된다.
  - 수집 주기가 150초를 넘는 감시 항목은 값을 바꿀 때 끊길 수 있다(240초에서 약 28%로 추정, 사람 확인: 2026-11-09).
- 다시 볼 신호: 관리자 서버가 DB 없이 webhook만으로 알림을 만들어야 할 때, 수집 주기 상한을 240초보다 늘릴 때

## 확인 방법

- `infrastructure/prometheus/tests/monitoring-checks.test.yml`(`promtool test rules`): Alert label이 두 id와 `alertname`, `severity`뿐이고, 값을 바꿔도 수집 틈을 지나 Alert가 이어진다. 지운 대상은 5분 뒤, 살아난 대상은 바로 복구된다.
- `amtool check-config`와 리뷰: `group_by`가 `alertname`, `check_id`뿐이다.
- 리뷰: [관리자 서버 만들기](../ADMIN-SERVER.md)와 [INTEGRATION.md](../INTEGRATION.md)에 알림의 이름, 환경, 주소를 webhook label이 아니라 DB 값으로 채우라고 적혀 있다.
