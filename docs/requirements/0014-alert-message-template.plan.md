# 0014. 관리자 서버에서 정하는 알림 메시지 틀 구현 계획

- 요구사항: [0014. 관리자 서버에서 정하는 알림 메시지 틀](0014-alert-message-template.md)
- 상태: 검토 중

## 접근 방법

[요구사항 0009](0009-resource-threshold-schema.md)와 같은 방식으로 만든다. 다른 웹의 개발자가 읽을 설명은 [관리자 서버 만들기](../ADMIN-SERVER.md)에 새 절로 쓰고, 메시지 틀 테이블은 `docs/reference-schema/`에 MariaDB용과 PostgreSQL용 참고용 DDL로 둔다. 이 저장소의 앱은 이 테이블을 쓰지 않으므로 Manager Database migration, Entity, `backend/src/main/`은 바꾸지 않는다.

설명을 관리자 서버 만들기에 두는 이유는 메시지 틀이 코어와 주고받는 약속이 아니라 관리자 서버 안의 기능이기 때문이다. [통합 계약](../INTEGRATION.md)은 경로, 형식, label, series의 기준이라 같은 내용을 두 곳에 쓰면 어긋난다([WRITING.md의 출처는 한 곳](../WRITING.md#출처는-한-곳)). 그래서 통합 계약에는 링크만 더한다.

참고용 DDL이 두 DB에 적용되는지는 기존 `ReferenceSchemaIntegrationTest`를 넓혀 확인한다. 이 테스트 코드는 `backend/src/test/`에 들어간다. 참고용 DDL을 실행해 볼 수 있는 곳이 Testcontainers로 MariaDB와 PostgreSQL을 띄우는 백엔드의 통합 테스트뿐이고, 기준 migration(`V1__baseline.sql`)을 Flyway로 먼저 적용하는 준비도 이 테스트에 이미 있기 때문이다. `integrationTest` 작업은 이미 `../docs/reference-schema` 디렉터리 전체를 입력으로 등록하고 있어서 `build.gradle`은 고치지 않는다.

검토했지만 고르지 않은 방법은 다음과 같다.

- **메시지 틀 전용 테스트 클래스를 새로 만든다**: 컨테이너 두 개를 한 번 더 띄워 통합 테스트가 느려진다. 또 기존 테스트의 "두 DB의 논리 schema가 같다" 검사를 따로 다시 써야 한다.
- **기존 `resource-thresholds.sql`에 테이블을 더한다**: 메시지 틀은 리소스 임계값을 쓰지 않는 관리자 서버도 쓴다. 한 파일에 두면 감시 항목 알림만 만드는 쪽도 임계값 테이블까지 만들게 된다.
- **기본 틀 네 행을 DDL의 `INSERT`로 넣는다**: 요구사항에서 기본 틀은 관리자 서버가 갖고 행이 없으면 기본 틀을 쓰기로 했다(S12).
- **설명을 통합 계약에 둔다**: 같은 내용을 두 문서에 쓰게 되어 출처는 한 곳 원칙과 맞지 않는다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `docs/reference-schema/mariadb/alert-message-templates.sql` | 메시지 틀 테이블의 MariaDB용 참고용 DDL(새 파일) |
| `docs/reference-schema/postgresql/alert-message-templates.sql` | 같은 논리 schema의 PostgreSQL용 참고용 DDL(새 파일) |
| `backend/src/test/java/kr/co/unidocs/monitoring/ReferenceSchemaIntegrationTest.java` | 새 DDL도 적용하고, 테이블 목록에 메시지 틀 테이블을 더하며, 메시지 틀 테스트를 더함 |
| `docs/ADMIN-SERVER.md` | 알림 메시지 틀 절을 새로 쓰고, 만들 것·저장할 데이터·화면과 설정 기능 표에 행이나 링크를 더함 |
| `docs/INTEGRATION.md` | 리소스 임계값의 알림 발송 행과 참고용 DDL 절에서 관리자 서버 만들기로 링크 |
| `docs/ARCHITECTURE.md` | 코드 지도의 `docs/reference-schema/` 설명에 메시지 틀 DDL을 더함 |

`docs/ADMIN-SERVER.md`에는 다음을 쓴다.

- **만들 것 표**: 알림 발송과 재알림 행의 형식의 기준 칸에 새 절 링크를 더한다. 지금 값은 `관리자 서버가 정함`이다.
- **저장할 데이터 표**: 알림 메시지 틀 행을 더한다. 꼭 담을 값은 틀 종류와 틀 문구, 쓰는 곳은 알림 발송이다. 이 저장소의 테이블 칸은 `없음(참고용 DDL)`으로 쓴다. 표 아래 임계값 테이블 안내 문단에 메시지 틀 DDL의 위치를 한 문장으로 더한다.
- **화면과 설정 기능 표**: `알림 메시지 틀 보기·고치기` 행을 더한다. 필요한 때는 알림을 보낼 때, 이 저장소의 API는 `없음(이 저장소의 Manager에 없음)`이다.
- **새 절 `알림 메시지 틀`**: [관리자 서버 만들기의 화면과 설정 기능](../ADMIN-SERVER.md#화면과-설정-기능) 뒤에 둔다. 하위 제목은 다섯 개다.
  - 틀 종류와 재료: 네 종류, 종류마다 하나인 틀, [재료 목록](0014-alert-message-template.md#재료-목록) 표와 값의 출처
  - 저장할 때의 검사: 목록에 없는 재료, 다른 종류의 재료, 짝이 맞지 않는 중괄호, 1000자 초과를 거부하고 기존 틀을 둔다. 비우고 저장하면 행을 지워 기본 틀로 돌아간다(S9~S13).
  - 메시지 만들기: 재료 값은 다시 해석하지 않는다. 메시지는 서식 없는 글로 보낸다. 이름과 환경은 보낼 때 DB에서 읽는다. 시각 형식과 시간대, 재알림의 틀, 한도를 넘는 메시지의 `…` 처리도 쓴다(S1~S7, S15).
  - 기본 틀: 네 틀과 만든 메시지 예, 재료 뒤에 조사를 붙이지 않는 이유
  - 장애 기록 문구: 감시 항목 기록의 고정 문구와, 리소스 발생 기록에 문구를 두지 않는 이유(S8)

요구사항은 무엇을 만족해야 하는지의 기준이고 끝나면 바뀌지 않는다. 관리자 서버 만들기는 지금 상태를 설명하는 기준 문서라서, 다른 웹의 개발자가 요구사항을 찾아 읽지 않아도 되게 새 절의 내용을 그 문서에 쓴다. 새 절에서 요구사항은 결정의 근거로만 링크한다.

`docs/INTEGRATION.md`에는 다음 두 곳만 고친다.

- [리소스 임계값의 앱이 만들 것](../INTEGRATION.md#앱이-만들-것) 표의 알림 발송 행 쓰는 것 칸에 관리자 서버 만들기의 알림 메시지 틀 링크를 더한다.
- [참고용 DDL](../INTEGRATION.md#참고용-ddl) 절에 메시지 틀 DDL의 두 파일과 설명 위치를 한 문장으로 더한다.

### 데이터 모델 변경

이 저장소의 Manager Database는 바뀌지 않는다. 참고용 DDL에 다음 테이블을 더한다. 이름과 열은 0009의 참고용 DDL처럼 도입처가 바꿔 쓰는 예다.

```text
alert_message_templates   종류마다 0~1행, 행이 없으면 관리자 서버의 기본 틀
  template_type  VARCHAR(30)    NOT NULL  PK  CHECK_FIRING, CHECK_RESOLVED, RESOURCE_FIRING, RESOURCE_RESOLVED
  body           VARCHAR(1000)  NOT NULL      틀 문구(빈 문자열은 저장하지 않음, 비우면 행을 지움)
  created_at     DATETIME(6) / TIMESTAMP(6)  NOT NULL  UTC
  updated_at     DATETIME(6) / TIMESTAMP(6)  NOT NULL  UTC
```

- 종류를 기본 키로 두어 종류마다 행이 하나를 넘지 않게 한다. 0009의 `server_resource_alert_settings`가 `server_id`를 기본 키로 둔 것과 같은 방식이다.
- 다른 테이블을 외래 키로 가리키지 않는다. 모든 감시 항목과 서버가 함께 쓰기 때문이다. 그래서 기준 migration의 이름을 바꿔도 이 DDL은 고치지 않는다.
- 종류 값과 1000자 한도는 0009와 같이 앱의 요청 검증에서 확인하고 DB `CHECK` 제약을 두지 않는다. `VARCHAR(1000)`은 두 DB 모두 byte가 아니라 글자 수(code point)로 센다. 그래서 관리자 서버 만들기에는 1000자를 code point로 센다고 쓴다. Java `String.length()`는 이모지 한 글자를 2로 세기 때문이다.
- MariaDB는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4`, PostgreSQL은 `TIMESTAMP(6)`처럼 기존 `resource-thresholds.sql`의 작성 규칙과 머리 주석 형식을 따른다.

### 계약 변경

해당 없음. 코어와 관리자 서버 사이의 API, label, series는 바뀌지 않는다. 메시지 틀 저장·검사 API의 경로와 형식은 다른 웹이 정한다.

## 위험과 되돌리기

- **두 DB의 DDL이 어긋남**: 한쪽만 고치면 다른 웹이 고른 DB에서 다르게 동작한다. 기존 `vendorsShareLogicalSchema` 테스트가 메시지 틀 테이블의 열과 NULL 허용까지 비교해 알아챈다.
- **기존 테스트의 테이블 목록**: `createsTables`는 참고용 DDL이 만든 테이블이 정확히 목록과 같은지 본다. 목록에 새 테이블을 더하지 않으면 새 테이블이 검사에서 빠진다. 그래서 테스트를 먼저 고쳐 실패를 본 뒤 DDL을 쓴다.
- **문서가 요구사항과 어긋남**: 재료 목록과 기본 틀을 관리자 서버 만들기에 옮겨 쓰면서 글자가 달라질 수 있다. 리뷰에서 요구사항의 재료 목록과 기본 틀과 한 줄씩 비교한다.
- **Docker가 없는 환경**: 통합 테스트는 Docker가 있어야 돈다. 실행하지 못했으면 보고의 확인하지 못한 것에 쓴다.
- **되돌리기**: 문서, 참고용 DDL 두 파일, 테스트 변경만 있으므로 커밋을 되돌리면 된다. 이 저장소에는 데이터 migration이 없다.

### 보안 위협

이 저장소에는 틀을 받는 코드가 없다. 하지만 다른 웹이 관리자 서버 만들기를 보고 구현하므로, 다음 약점을 막는 규칙을 그 문서에 쓴다.

- 바뀌는 신뢰 경계와 데이터 흐름: 이 저장소의 신뢰 경계는 바뀌지 않는다. 다른 웹에서는 관리자가 넣은 틀 문구가 설정 API로 들어와 DB에 저장되고, 알림을 보낼 때 사용자 입력(감시 항목·서버·리소스 이름, 환경)과 webhook으로 들어온 값(`{대상}`)이 섞여 알림 수단으로 나간다.
- 잘못될 수 있는 것과 막는 방법
  - 권한 없는 사람의 틀 변경(CWE-862) → 다른 설정 API와 같이 로그인과 권한 확인을 거친 사람만 바꾸게 한다고 쓴다.
  - 검증하지 않은 틀(CWE-20) → 재료 목록, 종류별 재료, 중괄호 짝, 1000자 한도를 서버에서 검사하고 거부하면 기존 틀을 둔다고 쓴다.
  - 틀을 범용 템플릿 엔진으로 해석함(CWE-1336) → 정해 둔 재료 이름만 문자열로 바꾸고, 식 평가나 조건문을 지원하는 템플릿 엔진을 쓰지 않으며, 넣은 값은 다시 해석하지 않는다고 쓴다(S7).
  - 재료 값이 알림 수단의 서식으로 읽힘(CWE-116) → 서식 없는 글로 보낸다고 쓴다. 이 저장소 Manager가 Telegram에 `parse_mode` 없이 보내는 이유와 같다.
  - 틀 고치기 화면에서 틀이나 오류 문구를 HTML로 넣음(CWE-79) → 화면 프레임워크의 기본 escape로 보인다고 쓴다.
- 받아들이는 위험: 없음

## 테스트 계획

요구사항의 완료 기준마다 다음으로 확인한다.

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | `ReferenceSchemaIntegrationTest`의 `createsTables`, `vendorsShareLogicalSchema`, 새 테스트 `같은 종류의 틀을 두 번 넣지 못한다`, `한글 1000자 틀을 저장한다` | `cd backend && ./gradlew integrationTest --tests '*ReferenceSchemaIntegrationTest'` 통과(DDL을 쓰기 전에는 실패) |
| C2 | 문서 리뷰(새 절의 S1~S15 규칙, 재료 목록, 검사, 기본 틀, 통합 계약의 두 링크) | 리뷰어가 요구사항과 한 줄씩 비교 |
| C3 | 문서 리뷰와 검색(영어·기술 표기, 이모지, 재료 바로 뒤의 조사) | 새 절의 기본 틀 코드 블록에 `RESOLVED`, `CRITICAL`, `firing`, `resolved`, `Alert`가 없음 |
| C4 | diff 확인 | `git diff main -- infrastructure/ compose.yml backend/src/main/` 결과가 없음 |
| C5 | 문서 검사와 전체 검증 | `node scripts/check-docs.mjs` 오류 0, 경고 0. `cd frontend && npm run check`, `cd backend && ./gradlew build`, `cd backend && ./gradlew integrationTest` 통과 |

보안 위협의 막는 방법은 이 저장소에 코드가 없어서 자동 테스트로 확인할 수 없다. 그래서 C2의 문서 리뷰에서 위 다섯 규칙이 새 절에 있는지 확인한다.

## 작업 순서

- [ ] `ReferenceSchemaIntegrationTest`의 테이블 목록과 적용할 파일에 메시지 틀을 더하고 새 테스트 두 개를 쓴다. 실행해 실패를 확인한다.
- [ ] MariaDB와 PostgreSQL의 `alert-message-templates.sql`을 쓰고 통합 테스트가 통과하는지 확인한다.
- [ ] `docs/ADMIN-SERVER.md`에 알림 메시지 틀 절을 쓰고 세 표를 고친다.
- [ ] `docs/INTEGRATION.md`의 두 곳과 `docs/ARCHITECTURE.md`의 코드 지도를 고친다.
- [ ] 문서 검사, C3의 검색, C4의 diff 확인, 전체 검증을 실행한다.
- [ ] 새 세션이나 하위 에이전트에 리뷰를 맡기고 반영한다.
- [ ] 요구사항을 구현됨으로 바꾸고 이 계획을 지운다.

## 진행 중 발견

없음

## 남길 결정

없음. [AGENTS.md의 단계별 규칙](../../AGENTS.md#단계별-규칙)에서 ADR은 여러 모듈이나 외부 클라이언트가 기대는 계약, 구조, 의존성, 품질 기준을 정하고 되돌리려면 클라이언트를 고치거나 데이터를 옮겨야 하는 결정이다. 이번 변경은 코어와 관리자 서버 사이의 계약과 이 저장소의 구조를 바꾸지 않는다. 틀 종류, 재료, 검사 규칙은 관리자 서버 하나 안의 기능 규칙이라 엔드포인트 하나의 오류 코드처럼 요구사항에 남기면 된다. 이미 요구사항 0014에 이유와 함께 있다. 참고용 DDL도 다른 웹이 이름을 바꿔 쓰는 예라서 이 저장소에서 되돌릴 데이터가 없다.
