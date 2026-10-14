# 0002. E2E 테스트와 화면 검사 도구 구현 계획

- 요구사항: [0002. E2E 테스트와 화면 검사 도구](0002-e2e-and-screen-check.md)
- 상태: 검토 중

## 접근 방법

Playwright 하나로 E2E, 화면 검사, 성능 검사를 실행한다. `frontend/playwright.config.ts`에 project 셋을 두고, 명령마다 project를 골라 실행한다.

| project | 하는 일 | 폭 |
|---|---|---|
| `e2e` | 핵심 흐름 두 개 | 1280×800 |
| `screen-1280`, `screen-1920` | 모든 라우트에서 axe-core 접근성 검사와 가로 넘침 검사 | 1280×800, 1920×1080 |
| `perf` | Lighthouse로 대시보드의 성능 점수와 LCP, CLS, TBT를 출력 | 데스크톱 기준 |

- 브라우저는 Playwright가 버전을 고정한 Chromium을 쓴다. 개발 PC에 설치된 Chrome 버전에 따라 결과가 달라지지 않게 하기 위해서다. 지원 브라우저(Chrome, Edge)와 엔진이 같다.
- 테스트는 서버를 띄우지 않는다. 전체 실행 환경은 개발자가 `docker compose --profile local-db up -d --build`로 띄우고, 화면 주소는 `E2E_BASE_URL`(기본 `http://localhost:8081`)로 받는다. 전체 실행에는 DB와 Prometheus까지 필요해 Playwright의 `webServer`로 띄울 수 없다.
- 전역 준비(global setup)에서 `${E2E_BASE_URL}/api/checks`를 10초 timeout으로 한 번 부른다. 실패하면 "전체 실행 환경을 띄운 뒤 다시 실행하라"는 메시지로 바로 끝낸다(S4).
- 테스트 데이터의 Check 이름은 `e2e-` 접두사에 실행마다 다른 접미사를 붙인다. 테스트가 끝나면 API로 지우고, 전역 준비에서 `e2e-`로 시작하는 Check를 먼저 지운다(S6). 사람이 만든 Check는 이름으로 구분되므로 지우지 않는다.
- 흐름 ②는 대시보드의 "지금 위험한 Check" 표로 확인한다. 대시보드는 Check별 상태를 보이지 않고 위험한 Check만 표에 보이기 때문이다. `blackbox-exporter`에서 닿지 않는 TCP 대상(`127.0.0.1:1`, 연결 거부)으로 Check를 만든다. Prometheus가 SD를 다시 읽고(15초) 수집하면(15초) 위험으로 바뀐다. 고정 시간을 기다리지 않고 `expect(...).toPass({ timeout: 90_000 })` 안에서 새로고침하며 확인한다. 이 Check에는 알림 그룹을 연결하지 않아 Telegram으로 나가지 않는다.
- 화면 검사는 axe-core의 WCAG 2.2 AA까지의 태그(`wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa`)만 검사한다. best-practice 규칙은 요구사항 밖이라 넣지 않는다. 가로 넘침은 `document.documentElement.scrollWidth > clientWidth`로 잰다.
- 상세 화면 두 개는 실제 id가 필요하다. 화면 검사의 준비 단계에서 API로 `e2e-` Check와 서버를 하나씩 만들고 끝나면 지운다. 빈 상태 화면은 이 데이터를 만들기 전에 검사한다. 다만 사람이 만든 데이터가 있으면 빈 상태가 보이지 않는다. 그때 빈 상태는 컴포넌트 테스트가 맡는 것으로 보고 넘어간다.
- 성능 검사는 Playwright로 Chromium을 원격 디버깅 포트와 함께 띄우고, 같은 브라우저에 Lighthouse를 연결해 결과를 출력한다. 점수 기준으로 실패시키지 않는다(요구사항 S3은 보고만 한다).

검토했지만 고르지 않은 방법은 다음과 같다.

- 컴포넌트 테스트처럼 API를 대역으로 바꾸고 Vite 개발 서버만 띄우는 E2E: 빠르지만 Manager DB와 Prometheus를 거치지 않아 요구사항의 "실제 환경을 끝까지"를 만족하지 못한다.
- 화면 검사를 `@axe-core/cli`로 따로 실행: 브라우저 드라이버를 따로 맞춰야 하고, 기준 폭과 화면 상태(상세 화면의 데이터)를 만들기 어렵다.
- Lighthouse CLI를 그대로 실행: 개발 PC에 설치된 Chrome을 찾아 쓰므로 버전이 PC마다 다르고, 점수와 지표만 출력하려면 결과 JSON을 다시 읽어야 한다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `frontend/package.json`, `package-lock.json` | 개발 의존성 `@playwright/test`, `@axe-core/playwright`, `lighthouse` 추가. 스크립트 `test:e2e`, `test:screen`, `test:perf` 추가 |
| `frontend/playwright.config.ts` | 새 파일. project 넷, `E2E_BASE_URL`, `timezoneId: 'Asia/Seoul'`, `locale: 'ko-KR'`, `forbidOnly: !!process.env.CI`, `retries: 0`, 실패 때만 스크린샷과 trace, 결과 폴더 `test-results/` |
| `frontend/e2e/global-setup.ts` | 새 파일. 환경 접속 확인, 남은 `e2e-` Check 정리 |
| `frontend/e2e/support/api.ts` | 새 파일. 테스트 데이터를 만들고 지우는 API 호출 |
| `frontend/e2e/check-lifecycle.spec.ts` | 새 파일. 흐름 ① |
| `frontend/e2e/dashboard-status.spec.ts` | 새 파일. 흐름 ② |
| `frontend/e2e/screen.spec.ts` | 새 파일. 라우트마다 axe 검사와 넘침 검사 |
| `frontend/e2e/perf.spec.ts` | 새 파일. Lighthouse 결과 출력 |
| `frontend/tsconfig.node.json` | include에 `playwright.config.ts`, `e2e/**/*.ts` 추가. `npm run build`의 타입 검사에 들어간다 |
| `frontend/.gitignore` | `test-results/`, `playwright-report/` 추가 |
| `docs/design/DESIGN.md` | 개요의 화면 검사 도구, 레이아웃의 기준 폭 |
| `docs/TESTING.md` | 테스트 범위 문장, 종류별 기준에 E2E와 화면 검사 행, 결정적 실행의 재시도와 `.only` 거부, 위치와 이름에 `frontend/e2e/` |
| `docs/assets/test-scope.svg` | E2E 행 추가 |
| `docs/DEVELOPMENT.md` | 준비에 `npx playwright install chromium`, 명령 표에 E2E·화면 검사·성능 검사, 명령 이름 표에 `test:screen`, `test:perf` |
| `docs/ARCHITECTURE.md` | 코드 지도에 `frontend/e2e` |

### 데이터 모델 변경

해당 없음

### 계약 변경

해당 없음. 테스트가 기존 화면용 API(`GET`, `POST`, `DELETE /api/checks`, 서버 API)를 부르기만 한다.

## 위험과 되돌리기

- 개발 DB에 테스트 데이터가 쌓이거나 사람의 데이터를 지움: `e2e-` 접두사가 붙은 Check와 서버만 만들고 지운다. 전역 준비도 접두사로만 고른다.
- 흐름 ②가 Prometheus 수집 시간 때문에 가끔 실패: 고정 시간 대신 상태가 바뀔 때까지 새로고침하며 최대 90초 기다린다. 새 테스트를 세 번 연달아 실행해 확인한다(C1). 그래도 흔들리면 멈추고 보고한다.
- 화면 검사를 처음 실행하면 기존 위반이 한꺼번에 나와 명령이 실패한다: 요구사항 C9대로 목록을 보고하고, 이번에 고칠지 기준선으로 등록할지 묻는다. 묻기 전에는 예외로 등록하지 않는다.
- 다른 브랜치의 이미지로 띄운 환경에서 실행하면 결과가 다를 수 있다: DEVELOPMENT.md에 같은 브랜치에서 `--build`로 띄운 뒤 실행한다고 쓴다.
- Lighthouse는 간접 의존성이 많다: 설치한 뒤 `npm query`로 새로 들어온 패키지의 라이선스를 모두 확인한다. 조건 확인이나 상업 이용 불가 분류가 있으면 멈추고 묻는다.
- 되돌리기: 개발 의존성, 설정 파일, `frontend/e2e/`, 문서만 바뀐다. 커밋을 되돌리면 끝난다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 없음. 테스트는 개발 PC의 `localhost` 환경에만 접속한다.
- 잘못될 수 있는 것과 막는 방법
  - 악성이거나 이름을 흉내 낸 패키지(CWE-1395): 세 패키지를 레지스트리에서 확인했다(관리자 Microsoft, Deque, Google Chrome 팀). 설치 스크립트를 설치 전에 확인하고, `npm audit --audit-level=high`를 실행하고, lockfile을 함께 커밋한다.
  - 운영 환경에 실행(테스트 데이터 생성과 삭제): `E2E_BASE_URL` 기본값은 `localhost`다. 지우는 대상은 `e2e-` 접두사로 한정한다.
  - 실패 기록에 비밀값이 남음(CWE-532): 화면이 비밀값을 보이지 않으므로 스크린샷과 trace에 들어가지 않는다. 결과 폴더는 Git이 무시한다.
- 받아들이는 위험: axe-core(MPL-2.0)를 개발 도구로 쓴다. 사람이 2026-10-14에 쓰기로 골랐다.

## 테스트 계획

E2E와 화면 검사는 전체 실행 환경을 띄운 뒤 `frontend`에서 실행한다.

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | `check-lifecycle.spec.ts`: Check를 등록하면 목록과 상세에 보이고 삭제하면 목록에서 사라진다. `dashboard-status.spec.ts`: 닿지 않는 대상의 Check가 대시보드의 위험한 Check 표에 보인다 | `npm run test:e2e` 3회 연달아 통과 |
| C2 | 위 두 테스트 | 저장 버튼 문구를 잠시 바꾸면 해당 단계 이름으로 실패하고 `test-results/`에 스크린샷이 남는다. 확인 뒤 되돌린다 |
| C3 | 위 두 테스트와 전역 준비 | 실행 전후 `GET /api/checks`의 목록이 같다. 실행 중 Ctrl+C로 멈춘 뒤 다시 실행해도 통과한다 |
| C4 | `screen.spec.ts`: 라우트마다 axe 위반이 없고 가로 넘침이 없다 | `npm run test:screen`. 입력 칸 하나의 라벨을 잠시 지우면 규칙 이름, 주소, project 이름(폭)과 함께 실패한다. 확인 뒤 되돌린다 |
| C5 | `perf.spec.ts` | `npm run test:perf`가 대시보드의 성능 점수와 LCP, CLS, TBT를 출력한다 |
| C6 | 전역 준비 | 환경을 내린 상태에서 `npm run test:e2e`, `npm run test:screen`이 30초 안에 접속 실패 메시지로 끝난다 |
| C7 | 없음 | `npm run check` 출력에 Playwright 실행이 없다 |
| C8 | 문서 검사 | `node scripts/check-docs.mjs --render` 오류 0, 그림 렌더링 확인 |
| C9 | `screen.spec.ts` 첫 실행 | 기존 위반 목록을 보고에 쓰고 처리 방법을 묻는다 |

## 작업 순서

- [ ] 의존성 설치 전 확인: 세 패키지의 설치 스크립트, `npm audit`. 설치 뒤 `npm query`로 새 패키지 라이선스 확인. `npx playwright install chromium`
- [ ] Playwright 설정, 전역 준비, `tsconfig.node.json`, `.gitignore`, 스크립트(`build`: 의존성과 설정 커밋)
- [ ] 흐름 ①, ② 테스트 작성과 실행(C1~C3, C6)
- [ ] 화면 검사 테스트 작성과 첫 실행(C4, C9). 기존 위반 목록을 보고하고 처리 방법을 물음
- [ ] 성능 검사 작성(C5)
- [ ] DESIGN.md, TESTING.md, test-scope.svg, DEVELOPMENT.md, ARCHITECTURE.md 갱신(C8)
- [ ] 전체 검증(C7)과 새 세션이나 하위 에이전트의 리뷰
- [ ] 요구사항 상태를 구현됨으로 바꾸고 이 계획을 지움(병합 전 마지막 커밋)

## 진행 중 발견

- 대시보드는 Check별 상태를 보이지 않고 위험한 Check만 표로 보인다(`DashboardPage.tsx`). 그래서 흐름 ②는 닿지 않는 대상의 Check가 그 표에 나타나는지로 확인한다.
- `frontend/test-results/`가 Git에 추적되지 않은 채 있다. 안의 `.last-run.json`은 Playwright 형식이다. 이번에 `.gitignore`에 넣는다.

## 남길 결정

없음. 고른 도구와 명령은 DESIGN.md와 TESTING.md에 쓴다.
