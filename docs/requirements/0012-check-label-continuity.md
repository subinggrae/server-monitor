# 0012. 감시 항목 이름·환경·확인 방식을 바꿔도 이어지는 장애

- 상태: 승인
- 작성일: 2026-10-29
- 관련: [#22](https://github.com/subinggrae/server-monitor/issues/22), [INTEGRATION.md](../INTEGRATION.md), [ADMIN-SERVER.md](../ADMIN-SERVER.md), [ADR 0001](../decisions/0001-alert-repeat-in-manager.md), [ADR 0007](../decisions/0007-core-app-contract.md), [ADR 0010](../decisions/0010-alert-labels-by-check-id.md), [PR #43](https://github.com/subinggrae/server-monitor/pull/43)(이 요구사항의 이전 구현, 확인 기록 포함)

## 배경

감시 항목(Check)의 이름이나 환경을 바꾸면 그 감시 항목의 Blackbox time series와 장애(Alert)가 끊긴다. Manager가 Blackbox HTTP SD 응답의 label에 사용자가 화면에서 고칠 수 있는 `check_name`, `environment`를 넣고, Prometheus는 label 집합이 다르면 다른 series로, Alertmanager는 다른 Alert(fingerprint)로 보기 때문이다. `BlackboxServiceDiscoveryService`의 주석은 label을 "이름이 바뀌어도 흔들리지 않도록 ID 중심"으로 넣는다고 하지만 실제와 다르다.

2026-10-29에 개발 환경에서 확인했다. 저장소의 코어와 앱 이미지를 그대로 빌드해 따로 띄우고, 닫힌 포트(`manager-db:1`)를 확인하는 TCP 감시 항목을 수집 주기 10초, 재발송 주기 5분으로 만들었다. 장애가 발생한 뒤 대상 id(`check_target_id`)는 그대로 두고 이름과 환경을 함께 바꿨다(06:22:56 UTC). 대상은 처음부터 끝까지 계속 실패했다. 관찰한 결과는 다음과 같다.

| 구성 요소 | 바꾸기 전 | 바꾼 뒤 |
|---|---|---|
| Prometheus `probe_success` series | 06:23:17에 마지막 값, 이어서 stale | 06:23:17부터 새 label 집합으로 새 series |
| Prometheus `ALERTS` | firing이 06:23:27에 끝남(`endsAt` 06:23:31) | 06:23:17 pending, 06:24:17 firing(`for: 1m`을 처음부터 다시 셈) |
| Alertmanager | fingerprint `7d8243b372ec5d81`이 resolved로 끝남 | fingerprint `392bca281c52f479`의 새 Alert, 새 묶음(`environment`가 `group_by`에 있음) |
| Manager 장애 기록(`alert_events`) | 1번 기록이 06:27:45에 받은 webhook으로 RESOLVED | 06:25:01에 받은 webhook으로 2번 기록이 새로 FIRING |
| Manager 알림 기록(`notification_deliveries`) | 06:27:45에 RESOLVED 알림 시도 | 06:25:01에 FIRING 알림 시도(새 장애라 재발송 주기를 보지 않음) |

전송이 성공하는 환경이라면 받는 그룹은 대상이 한 번도 복구되지 않았는데 새 장애 알림과 복구 알림을 받는다. 복구 알림의 제목은 Manager Database의 새 이름이고 환경은 Alert label의 옛 환경이라, 두 값이 섞인 메시지가 간다(`AlertEventService`, `AlertMessageFormatter`를 읽어 확인). 이름만 바꿔도 series와 fingerprint가 새로 생겨 끊긴다.

2026-11-09에 [PR #43](https://github.com/subinggrae/server-monitor/pull/43)에서 Alert 규칙과 `group_by`에서 이름과 환경만 빼는 방법으로 구현해 확인했다. 그 과정에서 다음을 알았다.

- 값을 바꾸면 옛 series는 바로 stale로 끝나고, 새 series는 새 target의 첫 수집 때 시작한다. 그 사이에 규칙 평가가 있으면 장애가 한 번 사라지고 실패 1분을 처음부터 다시 센다. 그래서 지금 값이 없는 대상은 최근 5분 안의 마지막 값(`last_over_time`)으로 판단해 이 틈을 메운다(사람 확인: 2026-11-09).
- 최근 5분의 마지막 값만 보면, 장애가 없을 때 값을 바꾼 직후 실패해도 옛 series의 마지막 성공 값이 5분 동안 남아 장애를 최대 5분 늦게 잡는다. 그래서 지금 값도 함께 본다(사람 확인: 2026-11-12).

2026-11-11에 방향을 다시 정했다. Alert를 감시 항목과 주소의 id(`check_id`, `check_target_id`)로만 묶는다(사람 확인: 2026-11-11, [#22](https://github.com/subinggrae/server-monitor/issues/22)). 그러면 확인 방식(`check_type`, `module`)과 주소(`instance`)를 바꿀 때도 장애가 이어지고, 관리자 서버는 이름·환경·주소를 모두 자기 DB에서 읽는다. PR #43은 이 요구사항으로 대신하고 닫는다(사람 확인: 2026-11-12).

SD label은 그대로 둔다. 사용자의 Grafana가 이 Prometheus의 기록을 읽고, 기록에 이름과 환경이 있어야 Grafana 그래프에서 감시 항목을 이름으로 보고 환경으로 거를 수 있다(사람 확인: 2026-10-29). 앱 자체는 Prometheus에서 지금 값만 번호로 읽으므로 이 선택의 영향을 받지 않는다.

## 목표

- 대상이 계속 실패하는 동안 사용자가 감시 항목의 이름, 환경, 확인 방식이나 주소를 바꿔도 장애는 하나로 이어진다. 복구 알림과 새 장애 알림이 가지 않고, 장애 기록도 하나로 남는다.
- 관리자 서버를 만드는 개발자가 알림의 감시 항목 이름, 환경, 주소를 Alert label이 아니라 알림을 보내는 시점의 자기 DB 값으로 채워야 한다는 것을 [관리자 서버 만들기](../ADMIN-SERVER.md)와 [INTEGRATION.md](../INTEGRATION.md)에서 알 수 있다.
- [INTEGRATION.md](../INTEGRATION.md)의 label 설명이 실제 동작과 맞는다.

## 하지 않는 것

- 이미 쌓인 time series를 새 label 집합으로 옮기는 것. SD label은 바꾸지 않으므로 이름·환경·확인 방식을 바꿀 때 원래 `probe_success`, `up` series가 새로 시작하는 것은 지금과 같다.
- 실패가 1분 이어지면 장애라는 기준(`for: 1m`)을 바꾸는 것. 장애 판단은 코어가 정한다([ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)). 다만 값을 바꿀 때의 수집 틈을 메우려고, 지금 값이 없는 대상을 판단하는 방법은 바꾼다(S9, S10).
- OS exporter SD의 `server_name`, `environment`를 바꾸는 것. OS exporter에는 Alert 규칙이 없고, 앱이 읽는 recording rule은 id만 쓰며, Grafana에서 서버를 이름으로 보려면 이 label이 필요하다(사람 확인: 2026-10-29).
- 장애 중인 감시 항목이나 주소를 지우거나 끌 때 관리자 서버가 장애 기록을 직접 닫는 것. 직접 닫아도 그 뒤 5분 동안 같은 장애의 firing webhook이 다시 와서 기록을 다시 열기 때문이다. 기록은 최대 5분 뒤 오는 복구 webhook으로 닫는다(사람 확인: 2026-11-12).
- 이 저장소의 Manager(`backend/`, `frontend/`)를 고치는 것. 이 저장소는 코어만 남기고, 관리자 서버의 API와 웹은 다른 웹에서 만든다(사람 확인: 2026-11-09, 2026-11-12). 이 저장소의 Manager는 Alert에 환경 label이 없으면 감시 항목의 DB 값을 쓰므로, 고치지 않아도 알림에 지금 환경이 보인다(`AlertEventService`를 읽어 확인). 이 변경 뒤에는 장애 목록 화면과 최근 장애 표(`RecentAlertTable`)의 내용 칸에 이름과 주소가 없는 장애 문장(annotation `summary`)이 보인다. 그래서 다음은 하지 않는다.
  - 알림의 환경을 Alert label을 보지 않고 DB 값으로만 채우게 `AlertEventService`를 고치는 것
  - `BlackboxServiceDiscoveryService`의 label 주석을 실제 동작에 맞추는 것
  - S6~S8. 장애 목록 화면의 내용 칸에 감시 항목 이름을 DB의 지금 값으로 먼저 보이는 것
  - C7. 지운 감시 항목의 복구 Alert가 기록만 남는지 새 `AlertEventService` 단위 테스트로 확인하는 것
  - C8. `GET /api/alerts`의 항목마다 지금 감시 항목 이름(`checkName`)을 주는 것
  - C9, C10. C8의 이름을 장애 목록 화면에 보이는 컴포넌트 테스트와 기준 폭의 화면 검사

## 사용자 경험

1. 사용자가 관리자 서버에서 장애가 이어지는 감시 항목의 이름, 환경, 확인 방식이나 주소를 고쳐 저장한다.
2. 받는 그룹에는 복구 알림이나 새 장애 알림이 가지 않는다.
3. 재발송 주기가 지나 다시 가는 장애 알림에는 새 이름, 새 환경, 새 주소가 보인다. 관리자 서버가 이 값들을 자기 DB에서 채우기 때문이다.
4. 관리자 서버의 장애 기록에는 그 장애가 처음 시작한 시각의 장애 하나로 남는다.

## 동작

### 정상 동작

- **S1. 장애 중에 이름 바꾸기**
  - 상황: 감시 항목의 대상이 계속 실패해 장애가 이어지고 있다.
  - 행동: 사용자가 감시 항목의 이름을 바꿔 저장한다.
  - 결과: 장애가 복구로 끝나지 않고 같은 장애로 이어진다. 복구 알림과 새 장애 알림이 가지 않는다. 다음 재발송 알림부터 새 이름이 보인다.
- **S2. 장애 중에 환경 바꾸기**
  - 상황: S1과 같다.
  - 행동: 사용자가 감시 항목의 환경을 바꿔 저장한다.
  - 결과: S1과 같고, 다음 재발송 알림과 복구 알림부터 새 환경이 보인다.
- **S12. 장애 중에 확인 방식 바꾸기**
  - 상황: S1과 같다.
  - 행동: 사용자가 감시 항목의 확인 방식을 바꿔 저장한다.
  - 결과: 새 방식으로도 실패하면 S1과 같다. 새 방식으로 성공하면 S11대로 바로 복구된다.
- **S13. 장애 중에 주소 바꾸기**
  - 상황: S1과 같다.
  - 행동: 사용자가 실패하는 주소를 고쳐 저장한다. 주소의 id는 그대로다.
  - 결과: 새 주소도 실패하면 같은 장애로 이어지고, 다음 재발송 알림부터 새 주소가 보인다. 새 주소가 성공하면 S11대로 바로 복구된다.
- **S3. 장애가 없을 때 값 바꾸기**
  - 상황: 대상이 모두 성공하고 있다.
  - 행동: 사용자가 이름, 환경, 확인 방식이나 주소를 바꾼다.
  - 결과: 장애와 알림이 생기지 않는다. 바꾼 직후에 대상이 실패해도 지금처럼 실패가 1분 이어지면 장애가 된다. 옛 기록의 마지막 성공 값 때문에 늦어지지 않는다.
- **S4. 변경을 배포할 때의 영향**
  - 상황: 이 변경 전의 규칙으로 이어지는 장애가 있는 코어를 이 변경으로 갱신한다.
  - 행동: 이 변경을 배포한다.
  - 결과: Alert의 label 집합이 한 번 바뀌므로 이어지던 장애가 새 장애로 다시 생긴다. 코어를 갱신하면 Alertmanager가 다시 만들어져 옛 장애를 잊으므로, 관리자 서버에는 옛 장애의 복구가 오지 않고 옛 장애 기록은 장애 중으로 남는다(2026-11-09 개발 환경에서 확인). 아직 운영에 배포한 코어가 없어 이 영향은 생기지 않으므로, 운영 가이드에 안내를 넣지 않는다(사람 확인: 2026-11-09, 2026-11-12).
- **S9. 값을 바꾸는 순간의 수집 틈**
  - 상황: 대상이 계속 실패해 장애가 이어지고 있다. 수집 주기는 10~240초 가운데 하나다.
  - 행동: 사용자가 이름, 환경이나 확인 방식을 바꿔, 옛 기록이 끝나고 새 기록이 아직 처음 수집되지 않은 동안 규칙을 평가한다.
  - 결과: 그동안은 최근 5분 안의 마지막 값(실패)으로 판단해 장애가 이어진다. 새 기록이 수집되면 그 값으로 판단한다. 다만 옛 기록의 마지막 수집부터 새 기록의 첫 수집까지가 5분을 넘으면 끊긴다. 수집 주기가 150초 이하면 끊기지 않고, 그보다 길면 끊길 수 있다(240초에서 약 28%로 추정). 이 위험은 받아들인다(사람 확인: 2026-11-09).
- **S11. 장애 중에 대상이 살아남**
  - 상황: 대상이 실패해 장애가 이어지고 있다.
  - 행동: 대상이 다시 성공한다.
  - 결과: 지금처럼 성공한 확인 결과를 받은 다음 규칙 평가에서 장애가 바로 복구로 끝난다. 5분을 기다리지 않는다. 값을 바꾼 직후에 살아나도 같다.

### 예외 동작

- **S5. 지운 감시 항목의 장애**
  - 상황: 장애가 이어지는 감시 항목을 지운다.
  - 행동: Alertmanager가 그 감시 항목의 복구 Alert를 보낸다.
  - 결과: 지금처럼 기록만 남기고 알리지 않는다. 복구 Alert는 S10대로 최대 5분 뒤에 온다.
- **S10. 장애 중인 감시 항목이나 주소를 지우거나 끔**
  - 상황: 대상이 실패해 장애가 이어지고 있다.
  - 행동: 사용자가 감시 항목이나 그 주소를 지우거나 끈다. Blackbox Exporter가 멈춰 확인 결과를 받지 못할 때도 같다.
  - 결과: 장애가 바로 끝나지 않고, 마지막 실패 값이 5분 안에 있는 동안 이어진 뒤 복구로 끝난다. 지금은 바로 복구로 끝난다. 그 사이에 재발송 알림이 한 번 더 갈 수 있다. 관리자 서버는 장애 기록을 직접 닫지 않고 복구 webhook으로 닫는다. 이 지연은 받아들인다(사람 확인: 2026-11-09, 2026-11-11). 끈 감시 항목을 5분 안에 다시 켜고 대상이 여전히 실패하면 같은 장애로 이어진다.

### 보안

- 접근 권한: 바뀌지 않는다. Alertmanager webhook은 Bearer token으로 확인한다.
- 외부 입력: 바뀌지 않는다. Alertmanager webhook 요청의 label 값이 줄어들 뿐이다.
- 민감한 정보: 없음
- 남길 보안 이벤트: 없음

### 바뀌지 않는 것

- SD entry의 label 전부(`check_id`, `check_target_id`, `check_type`, `module`, `environment`, `check_name`, `__scrape_interval__`)와 대상 주소
- Alert 규칙의 `for: 1m`, 규칙 이름(`MonitoringCheckDown`, `MonitoringCheckProbeFailed`), `severity` label, 지금 값이 있는 대상을 그 값이 0일 때 실패로 보는 것
- Alertmanager의 `group_wait`, `group_interval`, `repeat_interval`, 복구 Alert 전송
- 장애를 `fingerprint`와 `startsAt`으로 구분하는 방식과 재발송 판단([ADR 0001](../decisions/0001-alert-repeat-in-manager.md))
- 한 감시 항목의 여러 주소 Alert가 한 webhook 요청으로 묶여 오는 것
- 이 저장소의 Manager 코드(`backend/`, `frontend/`)

## 완료 기준

- [x] C1. 두 Alert 규칙에서 장애가 이어지는 대상의 이름, 환경, 확인 방식, 주소를 바꿔도 Alert의 label이 그대로이고 Alert가 끊기지 않는다. (S1, S2, S12, S13) — 확인: `infrastructure/prometheus/tests/`의 규칙 테스트
- [x] C2. 개발 환경에서 장애가 이어지는 감시 항목의 이름·환경과 확인 방식을 바꿔도 Alertmanager의 그 Alert fingerprint와 `startsAt`이 그대로이고, webhook을 받는 곳에 복구 webhook이 오지 않는다. 수집 주기 30초 이상을 포함한다. (S1, S2, S12, S9) — 확인: 개발 환경 수동 확인. 이 저장소의 Manager를 쓰지 않고, 빌드한 코어 이미지에 감시 대상 목록을 주고 webhook을 기록하는 가짜 앱을 붙인다(사람 확인: 2026-11-12, 코어만 남김)
- [x] C4. Alert 규칙의 label이 `alertname`, `severity`, `check_id`, `check_target_id`뿐이고, annotation에 이름·환경·주소·확인 방식이 없으며, `core-dev.bat check`(이미지 빌드, 설정 검사, 규칙 테스트)가 통과한다. (S1, S2, S12, S13) — 확인: 규칙 테스트, `core-dev.bat check`
- [x] C5. `alertmanager.yml`의 `group_by`가 `alertname`, `check_id`뿐이고, 한 감시 항목의 여러 주소 Alert가 여전히 한 요청으로 묶인다. — 확인: `amtool check-config`(`core-dev.bat check`에 포함)와 C2의 수동 확인
- [ ] C6. [INTEGRATION.md](../INTEGRATION.md)의 SD label 표와 Alert label 표, webhook 예시, [ADMIN-SERVER.md](../ADMIN-SERVER.md)의 예시가 실제 label과 맞는다. — 확인: 리뷰
- [ ] C11. [관리자 서버 만들기](../ADMIN-SERVER.md)와 [INTEGRATION.md](../INTEGRATION.md)에 관리자 서버가 알림의 감시 항목 이름, 환경, 주소를 Alert label이 아니라 `check_id`, `check_target_id`로 찾은 자기 DB 값으로 채워야 한다고 적혀 있다. (S1, S2, S13) — 확인: 리뷰
- [x] C12. 두 Alert 규칙에서 옛 기록의 stale 표시와 새 기록의 첫 수집 사이에 평가가 있어도 장애가 이어지고 시작 시각이 그대로다. 지우거나 끈 대상의 장애는 5분 동안 이어진 뒤 끝나고, 살아난 대상의 장애는 다음 평가에서 끝난다. 장애가 없을 때 값을 바꾼 직후 실패하면 1분 뒤 장애가 된다. (S3, S9, S10, S11) — 확인: 규칙 테스트. 바꾸기 전 규칙, 지금 값만 보는 식, `last_over_time`만 보는 식에서 실패하는지도 확인한다
- [ ] C13. [INTEGRATION.md](../INTEGRATION.md)와 [운영 가이드](../OPERATIONS.md)에 지우거나 끈 감시 항목·주소의 장애가 최대 5분 뒤 복구로 끝나고 그 사이 재발송 알림이 한 번 더 갈 수 있다고 적혀 있다. (S10) — 확인: 리뷰

## 확인 필요

없음
