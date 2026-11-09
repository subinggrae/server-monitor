# 0012. 감시 항목 이름·환경을 바꿔도 이어지는 장애

- 상태: 승인
- 작성일: 2026-10-29
- 관련: [#22](https://github.com/subinggrae/server-monitor/issues/22), [INTEGRATION.md](../INTEGRATION.md), [ADMIN-SERVER.md](../ADMIN-SERVER.md), [ADR 0001](../decisions/0001-alert-repeat-in-manager.md), [ADR 0007](../decisions/0007-core-app-contract.md)

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

시험 환경은 Telegram 전송을 일부러 실패시켰으므로 알림 기록은 모두 FAILED다. 전송이 성공하는 환경이라면 받는 그룹은 대상이 한 번도 복구되지 않았는데 바꾼 지 약 2분 뒤에 새 장애 알림을, 약 5분 뒤에 복구 알림을 받는다. 복구 알림의 제목은 Manager Database의 새 이름이고 환경은 Alert label의 옛 환경이라, 두 값이 섞인 메시지가 간다(`AlertEventService`, `AlertMessageFormatter`를 읽어 확인). 장애 기록에서도 한 번의 장애가 시작 시각이 다른 두 장애로 남는다. 바꾼 뒤 약 50초 동안은 firing인 Alert가 하나도 없다.

이름만 바꿔도 끊긴다(06:31:09 UTC에 바꿈). series와 fingerprint가 새로 생기는 것은 같다. 다만 `check_name`은 `group_by`에 없어서 두 Alert가 같은 묶음에 남고, 다음 `group_interval` 때(06:34:59) 한 webhook 요청에 옛 Alert의 복구와 새 Alert의 장애가 함께 왔다. Manager는 옛 장애 기록을 RESOLVED로 닫고 새 장애 기록을 만들어, 복구 알림과 새 장애 알림을 함께 시도했다.

이름과 환경을 뺀 label로 장애를 만들면 이어진다. 시험 컨테이너에만 `max without (check_name, environment) (probe_success{job="manager-blackbox"}) == 0` 규칙을 더하고 이름과 환경을 다시 함께 바꿨다(06:38:19). 기존 규칙의 Alert는 위와 같이 새 fingerprint로 다시 생겼지만, 시험 규칙의 Alert는 fingerprint `5b8ae392b5070f5d`와 `startsAt`(06:36:32)이 그대로였고 `ALERTS` series에도 빈 구간이 없었다. Manager에도 장애 기록 하나로 남았다. 환경 label이 없으면 Manager는 알림의 환경을 Manager Database 값으로 채운다(`AlertEventService`를 읽어 확인). 원래 `probe_success` series는 이때도 끊겼다.

`environment`는 OS exporter SD(`server_name`, `environment`)에도 있다. 다만 `manager-os` job에는 Alert 규칙이 없고, 앱이 읽는 공통 recording rule의 label은 `server_id`, `resource_type`, `resource_key`뿐이라 리소스 값과 임계값 판단은 이어진다. 끊기는 것은 `up{job="manager-os"}`의 원래 series뿐이다. 이 내용은 코드와 설정을 읽어 판단했고 실행해 확인하지 않았다.

그래서 이 문제를 고친다. SD label은 그대로 두고, 장애를 만드는 Alert 규칙과 Alertmanager `group_by`에서만 이름과 환경을 뺀다(사람 확인: 2026-10-29). SD label에서 빼지 않는 이유는 사용자의 Grafana가 이 Prometheus의 기록을 읽기 때문이다. 앱은 추세를 Grafana 링크로 넘기고([DESIGN.md](../design/DESIGN.md), [ARCHITECTURE.md](../ARCHITECTURE.md)), 기록에 이름과 환경이 있어야 Grafana 그래프에서 감시 항목을 이름으로 보고 환경으로 거를 수 있다. 앱 자체는 Prometheus에서 지금 값만 번호로 읽으므로 이 선택의 영향을 받지 않는다.

## 목표

- 대상이 계속 실패하는 동안 사용자가 감시 항목의 이름이나 환경을 바꿔도 장애는 하나로 이어진다. 복구 알림과 새 장애 알림이 가지 않고, 장애 기록도 하나로 남는다.
- 관리자 서버를 만드는 개발자가 알림의 감시 항목 이름과 환경을 Alert label이 아니라 알림을 보내는 시점의 자기 DB 값으로 채워야 한다는 것을 [관리자 서버 만들기](../ADMIN-SERVER.md)와 [INTEGRATION.md](../INTEGRATION.md)에서 알 수 있다.
- [INTEGRATION.md](../INTEGRATION.md)의 label 설명이 실제 동작과 맞는다.

## 하지 않는 것

- 확인 주소(대상의 주소와 포트)를 바꿨을 때 장애를 잇는 것. 주소는 `instance` label이고, 주소가 바뀌면 다른 대상을 확인한다.
- 감시 항목 종류(`check_type`, `module`)를 바꿨을 때 장애를 잇는 것. 종류가 바뀌면 확인 방법이 달라진다.
- 이미 쌓인 time series를 새 label 집합으로 옮기는 것
- 장애 판단 규칙(`for: 1m` 등)을 바꾸는 것. 장애 판단은 코어가 정한다([ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)).
- OS exporter SD의 `server_name`, `environment`를 바꾸는 것. OS exporter에는 Alert 규칙이 없고, 앱이 읽는 recording rule은 id만 쓰며, Grafana에서 서버를 이름으로 보려면 이 label이 필요하다(사람 확인: 2026-10-29).
- 대시보드와 감시 항목 상세가 함께 쓰는 최근 장애 표(`RecentAlertTable`)에 이름을 보이는 것. 이 변경 뒤에는 이 표의 장애 내용에서 이름이 빠진다. 화면 목록을 다시 정할 예정이라 이번에는 고치지 않는다(사람 확인: 2026-10-29).
- 이 저장소의 Manager(`backend/`, `frontend/`)를 고치는 것. 관리자 서버의 API와 웹은 다른 웹에서 만든다(사람 확인: 2026-11-09). 그래서 다음은 이번에 하지 않는다. 이 저장소의 Manager는 참고용 앱으로 남는다.
  - S6~S8. 장애 목록 화면의 내용 칸에 감시 항목 이름을 Manager Database의 지금 값으로 먼저 보이고(상세 화면 링크), 그 아래에 장애 내용을 보이는 것. 이름을 찾을 수 없으면 `찾을 수 없는 감시 항목`을 링크 없이 보이는 것. 이 변경 뒤에는 Manager의 장애 목록 내용 칸에서도 이름이 빠진다.
  - C3. Telegram 메시지의 이름과 환경이 알림을 보낼 때의 Manager Database 값인지 `AlertEventService`, `AlertMessageFormatter` 단위 테스트로 확인하는 것. 관리자 서버가 지켜야 할 동작은 C11의 문서로 알린다.
  - C7. 지운 감시 항목의 복구 Alert가 기록만 남는지 새 `AlertEventService` 단위 테스트로 확인하는 것
  - C8. `GET /api/alerts`의 항목마다 Manager Database의 지금 감시 항목 이름(`checkName`)을 주는 것
  - C9, C10. C8의 이름을 장애 목록 화면에 보이는 컴포넌트 테스트와 기준 폭의 화면 검사
  - `BlackboxServiceDiscoveryService`의 label 주석을 실제 동작에 맞추는 것

## 사용자 경험

1. 사용자가 관리자 서버에서 장애가 이어지는 감시 항목의 이름이나 환경을 고쳐 저장한다.
2. 받는 그룹에는 복구 알림이나 새 장애 알림이 가지 않는다.
3. 재발송 주기가 지나 다시 가는 장애 알림에는 새 이름과 새 환경이 보인다. 관리자 서버가 이름과 환경을 자기 DB에서 채우기 때문이다.
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
  - 결과: S1과 같고, 다음 재발송 알림부터 새 환경이 보인다.
- **S3. 장애가 없을 때 이름·환경 바꾸기**
  - 상황: 대상이 모두 성공하고 있다.
  - 행동: 사용자가 이름이나 환경을 바꾼다.
  - 결과: 장애와 알림이 생기지 않는다. 이후 장애가 나면 알림에 새 이름과 새 환경이 보인다.
- **S4. 변경을 배포할 때의 1회성 영향**
  - 상황: 이 변경 전부터 이어지는 장애와 쌓인 time series가 있다.
  - 행동: 이 변경을 배포한다.
  - 결과: Alert의 label 집합이 한 번 바뀌므로 이어지던 장애가 한 번 복구됐다가 새 장애로 다시 생긴다. SD label은 바뀌지 않으므로 쌓인 time series는 이어진다. 이 영향은 피할 수 없어 받아들이고, 운영 가이드에 이 영향과 장애가 적은 시간에 배포하라는 안내를 적는다(사람 확인: 2026-10-29).

### 예외 동작

- **S5. 지운 감시 항목의 장애**
  - 상황: 장애가 이어지는 감시 항목을 지운다.
  - 행동: Alertmanager가 그 감시 항목의 복구 Alert를 보낸다.
  - 결과: 지금처럼 기록만 남기고 알리지 않는다. 이 동작은 [INTEGRATION.md](../INTEGRATION.md)의 처리 순서에 이미 있고, 이 변경은 Alert의 `check_id` label을 바꾸지 않는다.

### 보안

- 접근 권한: 바뀌지 않는다. Alertmanager webhook은 Bearer token으로 확인한다.
- 외부 입력: 바뀌지 않는다. Alertmanager webhook 요청의 label 값이 줄어들 뿐이다.
- 민감한 정보: 없음
- 남길 보안 이벤트: 없음

### 바뀌지 않는 것

- SD entry의 label 전부(`check_id`, `check_target_id`, `check_type`, `module`, `environment`, `check_name`, `__scrape_interval__`)와 대상 주소. 이름을 바꿀 때 원래 `probe_success` series가 새로 시작하는 것도 지금과 같다.
- Alert 규칙의 장애 판단 식과 `for: 1m`, 규칙 이름(`MonitoringCheckDown`, `MonitoringCheckProbeFailed`)
- Alertmanager의 `group_wait`, `group_interval`, `repeat_interval`, 복구 Alert 전송
- 장애를 `fingerprint`와 `startsAt`으로 구분하는 방식과 재발송 판단([ADR 0001](../decisions/0001-alert-repeat-in-manager.md))
- 한 감시 항목의 여러 대상 Alert가 한 webhook 요청으로 묶여 오는 것
- 이 저장소의 Manager 코드(`backend/`, `frontend/`)

## 완료 기준

- [ ] C1. 장애가 이어지는 감시 항목의 이름을 바꿔도 Alertmanager의 그 Alert fingerprint와 `startsAt`이 그대로이고, Manager에 복구 webhook이 오지 않는다. (S1) — 확인: 개발 환경에서 이번 배경과 같은 절차로 수동 확인
- [ ] C2. 장애가 이어지는 감시 항목의 환경을 바꿔도 C1과 같다. (S2) — 확인: C1과 같은 수동 확인
- [ ] C4. Alert 규칙 파일의 label과 annotation이 바뀐 계약대로이고, `promtool test rules`가 통과한다. (S1, S2) — 확인: `infrastructure/prometheus/tests/`의 규칙 테스트
- [ ] C5. `alertmanager.yml`의 `group_by`가 이름과 환경을 쓰지 않고, 한 감시 항목의 여러 대상 Alert가 여전히 한 요청으로 묶인다. (S1, S2) — 확인: `amtool check-config`와 수동 확인
- [ ] C6. [INTEGRATION.md](../INTEGRATION.md)의 SD label 표와 Alert label 표, webhook 예시, [ADMIN-SERVER.md](../ADMIN-SERVER.md)의 예시가 실제 label과 맞고, 배포 때의 1회성 영향이 [운영 가이드](../OPERATIONS.md)에 있다. (S4) — 확인: 리뷰
- [ ] C11. [관리자 서버 만들기](../ADMIN-SERVER.md)와 [INTEGRATION.md](../INTEGRATION.md)에 관리자 서버가 알림의 감시 항목 이름과 환경을 Alert label이 아니라 `check_id`로 찾은 자기 DB 값으로 채워야 한다고 적혀 있다. (S1, S2, S3) — 확인: 리뷰

## 확인 필요

없음
