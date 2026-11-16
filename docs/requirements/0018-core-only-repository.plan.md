# 0018. 코어만 남기고 관리자 서버 앱을 저장소에서 걷어냄 구현 계획

- 요구사항: [0018. 코어만 남기고 관리자 서버 앱을 저장소에서 걷어냄](0018-core-only-repository.md)
- 상태: 승인

## 접근 방법

앱 코드를 지우고, 앱을 기준으로 쓴 지금 기준 문서와 지침을 코어 기준으로 다시 쓴다. 지난 요구사항과 ADR은 기록이라 내용을 두고, 지운 파일을 가리키는 링크만 글로 바꾼다.

검토했지만 고르지 않은 방법은 다음과 같다.

- 앱을 `archive/` 같은 폴더로 옮겨 남기기: 빌드·문서 검사·지침이 계속 그 폴더를 의식해야 하고, 지난 코드는 Git 기록과 태그로 볼 수 있다.
- 코드만 지우고 문서는 나중에 고치기: 검증 명령과 지침이 없는 코드를 가리켜 다음 작업이 바로 실패한다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `backend/`, `frontend/`, `compose.yml`(루트), `.env.example`, `.nvmrc` | 지운다 |
| `infrastructure/secrets/*.example` 가운데 앱용 | `manager_db_password`, `manager_db_root_password`, `telegram_bot_token`, `telegram_webhook_secret`의 예시 파일을 지운다(`alertmanager_webhook_token.example`은 남김) |
| `docs/design/` | 네 문서를 지운다 |
| `docs/assets/test-scope.svg` | 지운다(화면·API·DB 테스트 범위 그림) |
| `docs/assets/architecture-overview.svg` | 코어 세 구성 요소, 이 저장소 밖의 관리자 서버, 감시 대상만 그린다 |
| `.gitignore` | Backend, Frontend 항목을 지운다 |
| `scripts/check-docs.mjs` | 화면 문서를 전제로 한 검사가 문서가 없을 때 그대로 넘어가는지 확인하고, 필요하면 그 부분만 고친다 |
| `AGENTS.md` | 문서 표의 화면 문서 행, 자주 쓰는 명령, 자체 리뷰의 화면 검사 항목, 프로젝트 고유 규칙의 Flyway 항목 |
| `README.md` | 제품 소개, 시작하기, 문서 표를 코어 기준으로 |
| `docs/ARCHITECTURE.md` | 한눈에 보기, 실행 단위, API와 데이터 모델, 코드 지도, 지켜야 할 경계, 공통 관심사를 코어와 계약 기준으로 |
| `docs/DEVELOPMENT.md` | 준비, 명령 표(전체 검증은 `core-dev.bat check`와 문서 검사), 코드 위치, 코드 작성의 언어별 기준 |
| `docs/TESTING.md` | 테스트 종류 표를 코어 검사(규칙 테스트, 설정 검사, 운영 명령 시험)와 문서 검사로, 테스트 범위 그림 대신 표, 보안 자동 검사 |
| `docs/OPERATIONS.md`, `docs/INTEGRATION.md`, `docs/ADMIN-SERVER.md` | "이 저장소의 앱(Manager)"을 예로 든 설명을 지우거나 일반적인 관리자 서버로 바꾼다 |
| `docs/WRITING.md`, `docs/DIAGRAMS.md`, `docs/LICENSES.md`, `docs/PRODUCT.md`, `docs/TERMS.md`, `docs/templates/plan.md` | 화면 문서와 앱 코드를 가리키는 곳을 고친다 |
| `docs/requirements/0002`, `0005` | 지운 화면 문서로 가는 링크만 글로 바꾼다 |
| `docs/decisions/0006-baseline-migration.md` | 상태를 `폐기`로 바꾸고 이유 한 줄 |

### 데이터 모델 변경

해당 없음. 이 저장소에 DB가 없어진다. 참고용 DDL은 그대로다.

### 계약 변경

해당 없음. 통합 계약의 내용은 바뀌지 않는다.

## 위험과 되돌리기

- 코어 빌드나 검사가 앱 파일에 기대고 있었다: `core-dev.bat check`와 `start`를 실행해 확인한다. 묶음 스크립트의 `manager-api:8080`은 검사용 주소 예시라 그대로 둔다.
- 지침과 문서에서 지운 경로를 놓친다: `git grep -nE "backend|frontend|npm|gradle|manager-web|manager-db|Flyway|docs/design|test-scope"`로 찾고, 새 하위 에이전트 리뷰로 확인한다.
- 되돌리기: 이 PR의 병합 커밋을 되돌리면 앱과 문서가 돌아온다. 데이터 이전은 없다.

### 보안 위협

해당 없음. 코드를 지우기만 하고 코어의 신뢰 경계는 바뀌지 않는다. 앱이 받던 외부 입력(화면용 API, Telegram webhook)은 저장소에서 사라진다.

## 테스트 계획

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | 파일 목록 | `git ls-files backend frontend compose.yml .env.example .nvmrc docs/design` 출력 없음 |
| C2 | 코어 검사 | `core-dev.bat check` 통과 |
| C3 | 개발용 코어 실행 | `core-dev.bat start` 뒤 세 컨테이너가 뜨고, `core-dev.bat stop`으로 멈춤 |
| C4 | 문서 검사 | `node scripts/check-docs.mjs` 오류 0, 경고 0 |
| C5 | 남은 참조 찾기와 리뷰 | 위의 `git grep`이 지난 요구사항·ADR 밖에서 걸리지 않음, 새 하위 에이전트 리뷰 |
| C6 | 리뷰 | ADR 0006 상태 확인 |

이 변경은 코드를 지우기만 하므로 새 테스트를 쓰지 않는다.

## 작업 순서

- [ ] 앱 코드, 루트 compose, 앱 설정·비밀값 예시, 화면 문서, 테스트 범위 그림을 지운다
- [ ] AGENTS.md와 README.md를 코어 기준으로 고친다
- [ ] ARCHITECTURE.md와 아키텍처 그림을 고친다
- [ ] DEVELOPMENT.md와 TESTING.md를 고친다
- [ ] 나머지 문서(OPERATIONS, INTEGRATION, ADMIN-SERVER, WRITING, DIAGRAMS, LICENSES, PRODUCT, TERMS, 템플릿), `.gitignore`, 문서 검사 스크립트, 지난 요구사항의 링크, ADR 0006을 고친다
- [ ] 전체 검증(`core-dev.bat check`, 문서 검사, `start`·`stop`)을 실행하고 새 하위 에이전트에게 리뷰를 맡긴다
- [ ] 요구사항을 구현됨으로 바꾸고 이 계획을 지운다

## 진행 중 발견

없음

## 남길 결정

없음. 앱을 지우는 것은 Git 기록으로 되돌릴 수 있고, 코어와 관리자 서버 사이의 구조는 [ADR 0007](../decisions/0007-core-app-contract.md)에 이미 있다.
