# 0003. Check마다 다른 알림 재발송 주기 구현 계획

- 요구사항: [0003. Check마다 다른 알림 재발송 주기](0003-alert-repeat-interval.md)
- 상태: 검토 중

## 접근 방법

재발송 판단을 Alertmanager에서 Manager API로 옮긴다.

- Alertmanager의 `repeat_interval`을 `1h`에서 `5m`으로 줄인다. 배포할 때 한 번만 바꾸고, 사용자 설정과 관계없이 고정한다. 그러면 Alert가 이어지는 동안 Alertmanager가 5분마다 webhook을 보낸다. 이 값은 재발송 주기의 하한(5분)과 같고 `group_interval: 5m`보다 짧지 않다.
- 재발송 주기는 새 테이블 `check_notification_settings`에 Check마다 한 행으로 둔다. 행이 없으면 60분으로 본다. 그래서 기존 Check와 새 Check에 행을 미리 만들지 않아도 요구사항 S6을 만족한다.
- `AlertNotificationDispatcher`가 FIRING 알림을 보낼 때, 그 Alert와 그룹의 마지막 SENT 전송 시각을 읽는다.
  - 기록이 없으면 보낸다. 첫 알림이거나 앞선 전송이 모두 실패했다.
  - 마지막 SENT 뒤 `재발송 주기 - 허용 오차` 이상 지났으면 보낸다.
  - 지나지 않았으면 보내지 않고 전송 기록도 남기지 않는다.
- RESOLVED 알림은 지금처럼 2분 중복 방지(`dedupe-window`)만 거친다.
- 판단은 순수 함수(`RepeatNotificationPolicy`)로 떼어 현재 시각을 인자로 받는다. Dispatcher는 주입한 `Clock`으로 현재 시각을 얻는다. 지금 코드에는 `Clock` bean이 없어 `config`에 `Clock.systemUTC()` bean을 더한다.
- Check 엔티티는 알림을 모르게 둔다(`CheckNotificationBinding`의 클래스 주석). 그래서 `monitoring_checks`에 열을 더하지 않고 `notification.routing` 패키지에 새 엔티티를 둔다.

허용 오차가 필요한 이유는 다음과 같다. Alertmanager는 정확히 5분 간격으로 webhook을 보내지만, `attempted_at`은 Telegram 호출이 끝난 뒤 기록된다. 그래서 60분 주기라도 60분 뒤 webhook이 오면 경과 시간이 60분보다 조금 짧아 한 번 건너뛰고, 실제 재발송은 늘 65분 뒤가 된다. 허용 오차 1분을 두면 60분 뒤 webhook에서 보낸다. 1분은 webhook 간격(5분)보다 충분히 짧아 한 간격 일찍 보내는 일이 없다. 이 허용 오차는 요구사항 C2의 경계값 문장을 바꾸므로 사람에게 확인받는다.

검토했지만 고르지 않은 방법은 다음과 같다.

- 사용자가 주기를 바꿀 때마다 `alertmanager.yml`을 다시 쓰고 reload: 지켜야 할 경계를 어긴다. Check마다 route가 필요해 설정 파일이 대상 수만큼 커진다.
- Manager API가 스케줄러로 `alert_events`의 FIRING 행을 다시 보냄: Alert가 아직 이어지는지는 Alertmanager의 webhook으로만 안다. Alertmanager가 멈췄을 때 끝난 장애를 계속 알릴 수 있다. 요구사항의 하지 않는 것이다.
- `monitoring_checks`에 열을 더함: 테이블은 하나 줄지만 Check 등록·수정 API와 엔티티가 알림 설정을 알게 된다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/alertmanager/alertmanager.yml` | `repeat_interval: 1h` → `5m`, 이유 주석 |
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
- 허용 오차 때문에 주기보다 최대 1분 일찍 보냄: 요구사항에 그대로 쓴다(사람 확인 필요).
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
| C4 | `AlertNotificationDispatcherTest`: 첫 FIRING과 RESOLVED는 주기와 관계없이 보냄 | `./gradlew test` |
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

- [ ] `parseIntegerInRange` 리팩터링(`refactor` 커밋)
- [ ] Backend 완료 기준 테스트 작성(C1~C5, C7)과 실패 확인
- [ ] V8 migration(두 vendor), 엔티티, Repository, 알림 설정 API
- [ ] `RepeatNotificationPolicy`, `Clock` bean, Dispatcher 판단
- [ ] Frontend 컴포넌트 테스트 작성(C8)과 실패 확인, 화면 구현
- [ ] `alertmanager.yml`, `dedupe-window` 주석, ARCHITECTURE.md, ADR
- [ ] 수동 확인(C6, C9)과 전체 검증
- [ ] 새 세션이나 하위 에이전트의 리뷰
- [ ] 요구사항 상태를 구현됨으로 바꾸고 이 계획을 지움(병합 전 마지막 커밋)

## 진행 중 발견

- Alertmanager webhook으로 받는 `AlertNotification`에는 첫 수신인지 반복 수신인지 구분하는 값이 없다. `AlertEventService.record`는 반복 수신에도 같은 알림을 돌려준다. 그래서 첫 알림 여부를 전송 기록(마지막 SENT가 있는가)으로 판단한다.

## 남길 결정

- Alert가 이어지는 동안의 재발송 판단은 Manager API가 Check별 재발송 주기로 한다. Alertmanager의 `repeat_interval`은 판단 간격(5분)으로 고정한다. 재발송 주기의 하한은 이 값보다 짧을 수 없다.
