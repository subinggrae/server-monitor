# 0003. Check마다 다른 알림 재발송 주기 구현 계획

- 요구사항: [0003. Check마다 다른 알림 재발송 주기](0003-alert-repeat-interval.md)
- 상태: 승인

## 접근 방법

재발송 판단을 Alertmanager에서 Manager API로 옮긴다.

- Alertmanager의 `repeat_interval`을 `1h`에서 `group_interval`(5m)보다 짧은 `4m`으로 줄인다(진행 중 발견에서 `5m`을 고쳤다). 배포할 때 한 번만 바꾸고, 사용자 설정과 관계없이 고정한다. 그러면 Alert가 이어지는 동안 Alertmanager가 `group_interval`마다, 곧 5분마다 webhook을 보낸다. 재발송 주기의 하한(5분)은 이 간격과 같다.
- 재발송 주기는 새 테이블 `check_notification_settings`에 Check마다 한 행으로 둔다. 행이 없으면 60분으로 본다. 그래서 기존 Check와 새 Check에 행을 미리 만들지 않아도 요구사항 S6을 만족한다.
- `AlertNotificationDispatcher`가 FIRING 알림을 보낼 때, 그 Alert와 그룹의 마지막 SENT 전송 시각을 읽는다.
  - 기록이 없으면 보낸다. 첫 알림이거나 앞선 전송이 모두 실패했다.
  - 마지막 SENT 뒤 `재발송 주기 - 허용 오차` 이상 지났으면 보낸다.
  - 지나지 않았으면 보내지 않고 전송 기록도 남기지 않는다.
- RESOLVED 알림은 지금처럼 2분 중복 방지(`dedupe-window`)만 거친다.
- 판단은 순수 함수(`RepeatNotificationPolicy`)로 떼어 현재 시각을 인자로 받는다. Dispatcher는 주입한 `Clock`으로 현재 시각을 얻는다. 지금 코드에는 `Clock` bean이 없어 `config`에 `Clock.systemUTC()` bean을 더한다.
- Check 엔티티는 알림을 모르게 둔다(`CheckNotificationBinding`의 클래스 주석). 그래서 `monitoring_checks`에 열을 더하지 않고 `notification.routing` 패키지에 새 엔티티를 둔다.

허용 오차가 필요한 이유는 다음과 같다. Alertmanager는 정확히 5분 간격으로 webhook을 보내지만, `attempted_at`은 Telegram 호출이 끝난 뒤 기록된다. 그래서 60분 주기라도 60분 뒤 webhook이 오면 경과 시간이 60분보다 조금 짧아 한 번 건너뛰고, 실제 재발송은 늘 65분 뒤가 된다. 허용 오차 1분을 두면 60분 뒤 webhook에서 보낸다. 1분은 webhook 간격(5분)보다 충분히 짧아 한 간격 일찍 보내는 일이 없다. 사람이 2026-10-14에 허용 오차를 두기로 정했고 요구사항 S2, S3, C2에 반영했다.

검토했지만 고르지 않은 방법은 다음과 같다.

- 사용자가 주기를 바꿀 때마다 `alertmanager.yml`을 다시 쓰고 reload: 지켜야 할 경계를 어긴다. Check마다 route가 필요해 설정 파일이 대상 수만큼 커진다.
- Manager API가 스케줄러로 `alert_events`의 FIRING 행을 다시 보냄: Alert가 아직 이어지는지는 Alertmanager의 webhook으로만 안다. Alertmanager가 멈췄을 때 끝난 장애를 계속 알릴 수 있다. 요구사항도 이 방법을 하지 않는 것에 두었다.
- `monitoring_checks`에 열을 더함: 테이블은 하나 줄지만 Check 등록·수정 API와 엔티티가 알림 설정을 알게 된다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/alertmanager/alertmanager.yml` | `repeat_interval: 1h` → `4m`(진행 중 발견), 이유 주석 |
| `backend/src/main/resources/db/migration/{mariadb,postgresql}/V8__check_notification_settings.sql` | 새 테이블 |
| `backend/.../notification/routing/CheckNotificationSetting.java`, `CheckNotificationSettingRepository.java` | 새 엔티티와 Repository |
| `backend/.../notification/routing/CheckNotificationRequest.java`, `CheckNotificationResponse.java` | `repeatIntervalMinutes` 필드 |
| `backend/.../notification/routing/CheckNotificationService.java` | 주기 저장과 조회 |
| `backend/.../notification/RepeatNotificationPolicy.java` | 새 판단 함수 |
| `backend/.../notification/AlertNotificationDispatcher.java` | FIRING 재발송 판단, `Clock` 주입 |
| `backend/.../notification/NotificationDeliveryRepository.java` | 마지막 SENT 전송 조회 메서드 |
| `backend/.../config/` | `Clock` bean |
| `backend/src/main/resources/application.yml`, `MonitoringManagerProperties.java` | `dedupe-window` 주석을 새 역할에 맞게 고침 |
| `backend/src/test/java/...` | 새 단위 테스트 |
| `frontend/src/lib/api.ts` | `CheckNotifications`, `CheckNotificationsInput`에 `repeatIntervalMinutes` |
| `frontend/src/lib/repeatInterval.ts` | 재발송 주기 안내, 오류 문구, 입력 검증 |
| `frontend/src/components/checks/CheckNotificationSection.tsx` | 재발송 주기 입력 칸, 칸별 오류, 지연 안내 |
| `frontend/src/components/checks/CheckNotificationSection.test.tsx` | 새 컴포넌트 테스트 |
| `docs/ARCHITECTURE.md` | 지켜야 할 경계에 재발송 주기 판단 방식과 `repeat_interval` 제약 |
| `docs/decisions/0001-alert-repeat-in-manager.md` | 새 ADR |

`scrapeInterval.ts`의 정수 범위 검사와 같은 일을 하는 코드가 필요하다. 두 곳이 같은 함수를 쓰도록 먼저 리팩터링 커밋으로 `parseIntegerInRange`를 꺼낸다.

### 데이터 모델 변경

- 새 테이블 `check_notification_settings`
  - `check_id BIGINT NOT NULL` 기본 키, `monitoring_checks(id)` 외래 키, `ON DELETE CASCADE`
  - `repeat_interval_minutes INT NOT NULL`
  - `created_at`, `updated_at`
- 기존 테이블은 바뀌지 않는다. 행이 없는 Check의 주기는 60분이다.
- 허용 범위 5~1440의 정수는 요청 DTO에서 `BigDecimal`과 `@DecimalMin`, `@DecimalMax`, `@Digits(fraction = 0)`로 검증한다. 수집 주기와 같은 방식이다. DB 제약은 두지 않는다.

### 계약 변경

- 화면용 API(이 저장소의 화면만 호출)
  - `PUT /api/checks/{checkId}/notifications` 요청에 선택 필드 `repeatIntervalMinutes`(정수, 5~1440)를 더한다. 범위 밖이면 400과 `fieldErrors.repeatIntervalMinutes`를 돌려준다. 값이 없으면 기존 주기를 유지한다.
  - `GET`과 `PUT`의 응답에 `repeatIntervalMinutes`를 더한다. 저장한 적 없으면 60이다.
- Alertmanager webhook(공개 API): 요청 형식, 인증, 응답은 바뀌지 않는다. Alertmanager가 보내는 횟수만 늘어난다(Alert가 이어지는 동안 1시간 1회 → 5분 1회).

## 위험과 되돌리기

- webhook과 DB 쓰기가 12배로 늘어남: 이어지는 Alert마다 5분에 한 번 `alert_events`의 `last_received_at`을 고치고 마지막 SENT를 조회한다. 조회는 기존 인덱스 `ix_notification_deliveries_lookup`을 쓴다. 지금 규모(Check 수십 개)에서는 문제가 없다고 보고, 수동 확인(C9)에서 응답 시간을 본다.
- 배포 순서가 어긋남
  - Alertmanager만 먼저 5분으로 바뀌면, 이전 Manager API는 2분 중복 방지만 하므로 5분마다 Telegram 알림을 보낸다.
  - 그래서 Manager API를 먼저 배포하고 Alertmanager 설정을 나중에 바꾼다. 되돌릴 때는 Alertmanager 설정을 먼저 1시간으로 되돌린다.
  - 이 순서는 PR 설명과 커밋 본문에 쓴다.
- 허용 오차 때문에 주기보다 최대 1분 일찍 보냄: 요구사항과 화면 안내에 쓴다.
- Telegram 전송이 계속 실패함(봇이 그룹에서 빠짐 등): 실패는 보낸 것으로 세지 않으므로(요구사항 S9) webhook마다 5분 간격으로 다시 시도하고 FAILED와 WARN 로그를 남긴다. 이전에는 Alertmanager 주기(1시간)마다였다. 전송 기록 화면에서 실패가 반복되는 것으로 알아챈다.
- 같은 Alert의 webhook이 겹쳐 오면 두 요청이 모두 기록 없음을 보고 두 번 보낼 수 있다. 이 변경 전부터 있던 경쟁이고, Manager가 늘 바로 200으로 답하므로 드물다.
- migration 되돌리기: 새 테이블만 더하므로 기존 데이터는 바뀌지 않는다. 이전 버전 애플리케이션은 새 테이블을 쓰지 않는다. Flyway 이력을 되돌려야 하면 사람이 확인한 뒤 테이블과 `flyway_schema_history`의 V8 행을 지운다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 없음. 화면용 API에 정수 필드 하나가 들어온다. webhook 인증과 처리 경로는 같다.
- 잘못될 수 있는 것과 막는 방법
  - 범위를 벗어난 주기(CWE-20): 0이나 음수면 webhook마다 Telegram으로 보내 그룹을 도배하고 Bot API 제한에 걸린다. 서버에서 범위 밖 값과 소수를 거부한다. 화면 검증은 보조다.
  - 없는 Check에 설정 저장(CWE-20): 기존처럼 Check가 없으면 404를 돌려주고, 외래 키가 한 번 더 막는다.
- 받아들이는 위험: 없음

## 테스트 계획

Backend는 `cd backend && ./gradlew test`, Frontend는 `cd frontend && npm test`로 실행한다. 기대 결과는 모두 통과다.

| 완료 기준 | 테스트 | 확인 |
|---|---|---|
| C1 | `CheckNotificationServiceTest`: 저장한 주기가 응답에 들어 있고, 저장한 적 없으면 60이다 | `./gradlew test` |
| C2 | `RepeatNotificationPolicyTest`: 기록 없음은 보냄, 주기에서 허용 오차를 뺀 시각 경계에서 보냄, 그 직전은 보내지 않음 | `./gradlew test` |
| C2 | `AlertNotificationDispatcherTest`: 주기 전이면 Telegram을 부르지 않고 전송 기록도 남기지 않음 | `./gradlew test` |
| C3 | `AlertNotificationDispatcherTest`: 같은 마지막 전송 시각에서 주기를 바꾸면 결과가 바뀜 | `./gradlew test` |
| C4 | `AlertNotificationDispatcherTest`: 첫 FIRING과 RESOLVED는 주기와 관계없이 보냄, RESOLVED를 2분 안에 다시 받으면 SKIPPED | `./gradlew test` |
| C5 | `AlertNotificationDispatcherTest`: FAILED 기록만 있으면 보냄 | `./gradlew test` |
| C6 | 수동: MariaDB와 임시 PostgreSQL에 기존 Check를 둔 채 V8을 적용하고, 알림 설정 조회 API가 60을 돌려주는지 확인 | 결과를 보고에 적는다 |
| C7 | `CheckNotificationRequestTest`: 4, 1441, 15.5, JSON의 15.5는 위반, 5와 1440은 통과 | `./gradlew test` |
| C7 | `CheckNotificationServiceTest`: 값이 없으면 기존 주기 유지 | `./gradlew test` |
| C8 | `CheckNotificationSection.test.tsx`: 요청에 주기를 담음, 범위 밖 오류 표시, 저장한 주기와 지연 안내 표시 | `npm test` |
| C9 | 수동: 아래 절차 | 결과를 보고에 적는다 |

C9는 전체 실행 환경에서 다음 순서로 확인한다.

1. 닿지 않는 대상의 Check를 만들고 테스트용 Telegram 그룹을 연결한 뒤 주기를 5분으로 둔다.
2. `notification_deliveries`의 SENT 간격이 5분인지 본다.
3. 아무것도 다시 띄우지 않고 주기를 10분으로 바꾼 뒤, SENT 간격이 10분으로 바뀌는지 본다.

## 작업 순서

- [x] `parseIntegerInRange` 리팩터링(`refactor` 커밋)
- [x] Backend 완료 기준 테스트 작성(C1~C5, C7)과 실패 확인
- [x] V8 migration(두 vendor), 엔티티, Repository, 알림 설정 API
- [x] `RepeatNotificationPolicy`, `Clock` bean, Dispatcher 판단
- [x] Frontend 컴포넌트 테스트 작성(C8)과 실패 확인, 화면 구현
- [x] `alertmanager.yml`, `dedupe-window` 주석, ARCHITECTURE.md, ADR
- [x] 수동 확인(C6, C9)과 전체 검증
- [x] 새 세션이나 하위 에이전트의 리뷰
- [x] 토픽 브랜치를 푸시하고 PR을 만듦(#4). 사람이 2026-10-14에 구현을 마치면 묻지 않고 PR을 만들라고 지시했다. 푸시 직전에 전체 검증을 다시 실행하고, PR 설명에 배포 순서(Manager API 먼저, Alertmanager 설정 나중)를 쓴다
- [ ] 요구사항 상태를 구현됨으로 바꾸고 이 계획을 지움(병합 전 마지막 커밋)

## 진행 중 발견

- Alertmanager webhook으로 받는 `AlertNotification`에는 첫 수신인지 반복 수신인지 구분하는 값이 없다. `AlertEventService.record`는 반복 수신에도 같은 알림을 돌려준다. 그래서 첫 알림 여부를 전송 기록(마지막 SENT가 있는가)으로 판단한다.
- 계획과 달라진 것: `repeat_interval`을 `5m`이 아니라 `4m`으로 둔다. C9에서 `repeat_interval`과 `group_interval`을 모두 5분으로 두자, 첫 알림 뒤 다음 webhook이 5분이 아니라 10분 뒤에 왔다(23:25:31 → 23:35:30). Alertmanager는 `group_interval` 시점마다 마지막 전송 뒤 `repeat_interval`이 지났는지 보는데, 그 시점에는 경과 시간이 조금 모자라 한 번씩 건너뛴다. 4분으로 바꾸고 reload하자 5분마다 왔다. Alertmanager는 "repeat_interval이 group_interval보다 짧아 다음 group_interval까지 반복하지 않는다"는 경고를 남기는데, 이것이 의도한 동작이다. 계획의 접근 방법과 바뀌는 부분 표의 `5m`은 이 발견으로 `4m`이 됐다.
- C6: 임시 MariaDB 11.4와 PostgreSQL 17에서 V7 애플리케이션으로 Check를 만든 뒤 V8 애플리케이션을 띄웠다. 두 DB 모두 Flyway가 V8까지 성공했다. 기존 Check의 재발송 주기는 60, 15로 저장하면 응답과 재조회가 15, 4분은 400, 주기 없이 저장하면 15가 그대로였다. Check를 지우면 설정 행도 지워졌다.
- C9: 실제 Telegram 대신 `{"ok":true}`를 돌려주는 임시 서버를 쓰고, 비밀값은 모두 임시 값으로 바꾼 별도 compose project(`rp0003`)에서 확인했다. 닿지 않는 TCP 대상의 Check에 그룹을 연결하고 주기를 5분으로 두자 SENT가 23:40:58, 23:45:57에 났다. 다시 띄우지 않고 10분으로 바꾸자 다음 SENT는 23:55:57이었다. Alertmanager의 webhook 전송 수는 6이었다. 23:50 webhook은 왔지만 보내지 않았고 전송 기록도 남지 않았다. 위험 항목에 적은 webhook 응답 시간은 재지 않았다.
- 하위 에이전트 리뷰(2026-10-15): P1·P2는 없었다. 반영한 것은 복구 알림의 2분 중복 방지 테스트, 요구사항의 바뀌지 않는 것 문구(중복 방지는 복구 알림에만 남음), ADR의 Alertmanager reload 시점과 전송 실패 반복, 이 계획의 위험 항목, 주석 둘이다. 바꾸지 않은 것은 판단과 기록의 시각 출처가 다른 것(판단은 주입한 `Clock`, 기록은 `@PrePersist`의 `Instant.now()`)과, 설정 행이 없는 Check에 저장 요청이 동시에 오면 기본 키 충돌로 500이 날 수 있는 점이다. 둘 다 운영 영향이 작다.

## 남길 결정

- Alert가 이어지는 동안의 재발송 판단은 Manager API가 Check별 재발송 주기로 한다. Alertmanager는 `group_interval`(5분)마다 webhook을 보내도록 `repeat_interval`을 그보다 짧은 4분으로 고정한다. 재발송 주기의 하한은 `group_interval`보다 짧을 수 없다.
