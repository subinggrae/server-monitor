# 0018. 코어만 남기고 관리자 서버 앱을 저장소에서 걷어냄

- 상태: 구현됨
- 작성일: 2026-11-16
- 관련: [PR #57](https://github.com/subinggrae/server-monitor/pull/57)(구현 계획과 확인 기록 포함), [0008](0008-embeddable-core.md), [0012](0012-check-label-continuity.md), [0014](0014-alert-message-template.md), [ADR 0006](../decisions/0006-baseline-migration.md), [ADR 0007](../decisions/0007-core-app-contract.md), [INTEGRATION.md](../INTEGRATION.md), [ADMIN-SERVER.md](../ADMIN-SERVER.md)

## 배경

이 저장소에는 모니터링 코어(`infrastructure/`, `core-dev.bat`, `scripts/build-core-bundle.mjs`)와 함께 관리자 서버 앱(Manager)이 들어 있다. Manager는 `backend/`(Spring Boot API), `frontend/`(React 화면), 루트 `compose.yml`(앱, 개발용 DB, OS exporter 시험 장비)로 이루어진다.

관리자 서버의 API와 웹은 다른 웹에서 만들기로 했다(사람 확인: 2026-11-09). 그 뒤 이 저장소의 Manager는 참고용으로만 남았고, 코어 계약이 바뀌어도 따라가지 않는다. 예를 들어 [요구사항 0012](0012-check-label-continuity.md) 뒤로 Manager의 장애 목록에는 이름과 주소가 없는 문장만 보인다. 그래도 개발 가이드, 테스트 기준, AI 작업 지침, 검증 명령은 여전히 Manager를 기준으로 한다. 그래서 코어만 바꾸는 작업에도 Manager의 빌드와 테스트를 돌리고, 문서를 읽는 사람이 어느 쪽이 실제 제품인지 헷갈린다.

이 저장소는 코어만 남긴다(사람 확인: 2026-11-12, 2026-11-16).

## 목표

- 저장소에 다음만 남는다.
  - 코어: `infrastructure/`, `core-dev.bat`, `scripts/build-core-bundle.mjs`
  - 코어와 관리자 서버 사이의 계약과 안내: 통합 계약, 관리자 서버 만들기, 운영 가이드, 참고용 DDL(`docs/reference-schema/`)
  - 작업 지침과 문서 체계
- 개발 가이드, 테스트 기준, 아키텍처, README, AI 작업 지침과 검증 명령이 코어만 기준으로 한다.
- 코어의 빌드, 검사, 묶음, 개발용 실행(`core-dev.bat check`, `build`, `start`, `stop`)은 지금처럼 동작한다.
- 남는 설정, 비밀값, 주석, 참고용 DDL이 특정 알림 수단(Telegram)을 전제로 하지 않는다. 이 코어로 만드는 관리자 서버가 Telegram을 꼭 쓰는 것은 아니기 때문이다(사람 확인: 2026-11-16). 남는 비밀값은 코어가 쓰는 webhook token 하나다.

## 하지 않는 것

- 코어의 동작과 코어·관리자 서버 사이의 계약을 바꾸는 것. 코어가 쓰는 네트워크는 요구사항 0019에서, 제품 이름(모니터링 코어)은 [#56](https://github.com/subinggrae/server-monitor/issues/56)에서 바꾼다.
- 지난 요구사항과 ADR의 내용을 고치는 것. 기록이기 때문이다. 다만 지운 파일을 가리키는 링크는 문서 검사가 통과하도록 링크를 풀어 글로 남긴다. 더는 지킬 대상이 없는 ADR은 상태만 바꾼다(S4).
- 참고용 DDL을 자동으로 확인하는 테스트를 새로 만드는 것. 지금은 `backend/`의 `ReferenceSchemaIntegrationTest`가 `resource-thresholds.sql`을 확인하는데, 이 테스트도 함께 없어진다. 참고용 DDL은 문서로만 둔다([요구사항 0014](0014-alert-message-template.md)와 같음, 사람 확인: 2026-11-12).
- 개인 PC에만 있는 파일(루트 `.env`, `infrastructure/secrets/`의 실제 값 파일, 빌드 결과)을 저장소 변경으로 다루는 것. Git에 올라가지 않는 파일이라, 작업한 PC에서만 사람 확인을 받고 지웠다(2026-11-16).
- 닫힌 PR의 원격 브랜치를 정리하는 것
- 걷어낸 앱에만 해당하는 이슈(#49, #39, #37, #29, #21)를 이 저장소에서 처리하는 것. 이 변경이 병합될 때 이유를 적어 닫는다. #35는 코어의 exporter 묶음이라 남긴다(사람 확인: 2026-11-16).

## 사용자 경험

이 변경의 사용자는 이 저장소에서 코어를 고치는 개발자와 AI다.

1. 저장소를 받으면 코어, 코어 문서, 작업 지침만 보인다.
2. README와 개발 가이드의 시작 방법은 코어를 빌드하고 띄우는 방법이다.
3. 작업을 끝내기 전에 실행하는 전체 검증은 코어 검사와 문서 검사다.

## 동작

### 정상 동작

- **S1. 코어 검사와 묶음**
  - 상황: 앱을 걷어낸 저장소다.
  - 행동: 개발자가 `core-dev.bat check`와 `core-dev.bat build`를 실행한다.
  - 결과: 지금처럼 이미지 검사, 설정 검사, 규칙 테스트, 운영 명령 시험이 통과하고 묶음이 만들어진다.
- **S2. 개발용 코어 실행**
  - 상황: S1과 같다.
  - 행동: 개발자가 `core-dev.bat start`, `stop`을 실행한다.
  - 결과: 지금처럼 코어가 뜨고 멈춘다.
- **S3. 문서 검사**
  - 상황: S1과 같다.
  - 행동: `node scripts/check-docs.mjs`를 실행한다.
  - 결과: 오류 없이 통과한다. 화면 문서(`docs/design/`)가 없어도 검사가 그 문서를 찾지 않는다.
- **S4. 지킬 대상이 없어진 ADR**
  - 상황: [ADR 0006](../decisions/0006-baseline-migration.md)은 이 저장소 Manager의 Flyway migration에 관한 결정이다.
  - 행동: Manager를 걷어낸다.
  - 결과: ADR 0006의 상태를 폐기로 바꾸고 이유를 한 줄 적는다. 코어와 관리자 서버 사이의 결정(ADR 0001, 0002, 0007, 0008 등)은 그대로 둔다.

- **S6. 알림 수단과 무관한 참고용 DDL**
  - 상황: 관리자 서버 개발자가 참고용 기본 테이블 DDL(`base-tables.sql`)을 읽는다.
  - 행동: 알림 받을 곳과 알림 연결, 알림을 보낸 기록 테이블을 본다.
  - 결과: 테이블과 열이 특정 알림 수단의 이름(`telegram_*`, `chat_id` 등)을 쓰지 않는다. 알림 받을 곳은 이름, 알림 수단 종류, 보낼 곳, 켜짐을 담고, 다른 테이블은 `destination_id`로 가리킨다(사람 확인: 2026-11-16).

### 예외 동작

- **S5. 지운 경로를 가리키는 글**
  - 상황: 남는 문서나 설정이 `backend/`, `frontend/`, 루트 `compose.yml`, `docs/design/`을 가리킨다.
  - 행동: 문서 검사와 리뷰로 찾는다.
  - 결과: 지금 기준 문서(지난 요구사항과 ADR이 아닌 문서)에는 지운 경로를 가리키는 링크와 설명이 남지 않는다.

### 보안

- 접근 권한: 없음. 코어의 접근 방식은 바뀌지 않는다.
- 외부 입력: 없음. 걷어낸 앱의 입력(화면용 API, Telegram webhook)이 저장소에서 사라진다.
- 민감한 정보: 앱의 비밀값 예시 파일(`manager_db_password.example` 등)을 지운다. 실제 값 파일은 Git에 없으므로 다루지 않는다.
- 남길 보안 이벤트: 없음

### 바뀌지 않는 것

- 코어 이미지, 설정, Alert 규칙, 운영 명령과 그 시험
- 통합 계약(INTEGRATION.md)과 관리자 서버 만들기(ADMIN-SERVER.md)의 계약 내용. 이 저장소 Manager를 예로 든 설명만 고친다.
- 참고용 DDL의 테이블 구성. 알림 테이블의 이름과 열만 S6대로 알림 수단과 무관하게 바꾼다.
- 지난 요구사항과 ADR의 내용

## 완료 기준

- [x] C1. `backend/`, `frontend/`, 루트 `compose.yml`, `.env.example`, `docs/design/`, 앱의 비밀값 예시 파일이 저장소에 없다. `.nvmrc`는 코어 빌드 스크립트와 문서 검사의 Node.js 버전 기준이라 남긴다. (목표) — 확인: `git ls-files`
- [x] C2. `core-dev.bat check`가 통과한다. (S1) — 확인: 실행
- [x] C3. `core-dev.bat start`로 코어가 뜨고 `stop`으로 멈춘다. (S2) — 확인: 실행
- [x] C4. `node scripts/check-docs.mjs`가 오류 0, 경고 0이다. (S3, S5) — 확인: 실행
- [x] C5. AGENTS.md, README.md, DEVELOPMENT.md, TESTING.md, ARCHITECTURE.md, OPERATIONS.md, INTEGRATION.md, ADMIN-SERVER.md, WRITING.md, DIAGRAMS.md, LICENSES.md, PRODUCT.md, TERMS.md와 `.gitignore`, 그림이 코어만 기준으로 쓰여 있고 지운 경로를 가리키지 않는다. 전체 검증 명령은 코어 검사와 문서 검사다. (목표, S5) — 확인: `git grep`으로 지운 경로 이름을 찾고 리뷰
- [x] C6. ADR 0006의 상태가 폐기다. (S4) — 확인: 리뷰
- [x] C7. `git grep -i telegram`과 `chat_id`가 지난 요구사항·ADR과 용어 근거(TERMS.md) 밖에서는 예로 든 글에만 걸린다. 비밀값 예시 파일은 `alertmanager_webhook_token.example` 하나다. (목표) — 확인: `git grep`, `git ls-files infrastructure/secrets`
- [x] C8. 바꾼 참고용 기본 테이블 DDL이 빈 MariaDB와 PostgreSQL에 적용되고, 그 뒤에 리소스 임계값·알림 메시지 템플릿 DDL도 적용된다. 두 DB의 열 정의가 같다. (S6) — 확인: 두 DB에 한 번 적용

## 확인 필요

없음
