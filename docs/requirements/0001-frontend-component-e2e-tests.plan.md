# 0001. Frontend 컴포넌트 테스트와 E2E 테스트 도입 구현 계획

- 요구사항: [0001. Frontend 컴포넌트 테스트와 E2E 테스트 도입](0001-frontend-component-e2e-tests.md)
- 상태: 진행 중

## 접근 방법

도구와 실행 환경을 고른 이유와 고르지 않은 선택지는 [ADR 0014](../decisions/0014-frontend-test-tools.md)에 쓴다. 이 문서는 그 결정을 어떻게 적용하는지 쓴다.

### 컴포넌트 테스트

- Vitest 5, jsdom 29, Testing Library(React 16, user-event 14, jest-dom 7), MSW 2를 쓴다.
- MSW는 3이 최신이다. Vitest 5의 `@vitest/mocker`가 선택 peer로 `msw@^2.4.9`를 요구해서, 3을 쓰면 Node.js 22 기본 npm 10의 `npm ci`가 실패한다. `frontend/Dockerfile`도 `npm ci`를 쓰므로 2를 쓴다.
- jsdom은 30이 최신이다. 30은 Node.js 22.22.2 이상을 요구하고, `DEVELOPMENT.md`는 Node.js 22만 요구한다. 그래서 Node.js 22.13 이상에서 도는 29를 쓴다.
- 테스트 파일은 대상 옆에 `*.test.ts`, `*.test.tsx`로 둔다. 공통 준비 코드는 `src/test/`에 둔다.
  - `setup.ts`: jest-dom matcher 등록, MSW server 시작·초기화·종료. 처리하지 않은 요청은 오류로 만든다(`onUnhandledRequest: 'error'`). 그래서 테스트가 실제 네트워크로 나가지 않는다.
  - `render.tsx`: 재시도를 끈 `QueryClient`와 `MemoryRouter`로 감싸 그리는 함수
  - `server.ts`: MSW server와 자주 쓰는 응답(Check 종류 목록 등)
- `vitest.config.ts`는 `vite.config.ts`를 합쳐 `@` alias를 그대로 쓴다. 테스트 대상은 `src/**/*.test.{ts,tsx}`다.
- 테스트 파일은 `tsconfig.app.json`의 `src`에 들어 있어 `npm run build`의 `tsc -b`가 타입을 검사한다. `vite build`는 `index.html`에서 시작해 import한 파일만 묶으므로 테스트 파일은 번들에 들어가지 않는다.

### E2E 테스트

- Playwright 1.63.0과 Chromium 하나를 쓴다. 브라우저 build가 Playwright version에 묶여 있어 version을 정확히 고정한다(`^` 없음).
- E2E 전용 Compose 파일 `frontend/e2e/compose.yml`로 다음 세 container를 띄운다. 운영용 `compose.yml`과 `infrastructure/`는 고치지 않는다.

| 서비스 | 내용 |
|---|---|
| `manager-db` | `mariadb:11.4`. data를 `tmpfs`에 둬서 실행마다 빈 Database로 시작한다. port를 열지 않는다 |
| `manager-api` | `backend/Dockerfile`로 build한 `monitoring-manager-api:e2e`. healthcheck가 통과해야 다음으로 넘어간다 |
| `manager-web` | `frontend/Dockerfile`로 build한 `monitoring-manager-web:e2e`. `127.0.0.1:${E2E_WEB_PORT:-18081}`만 연다 |

- 개발용 구성과 겹치지 않게 한다.
  - Compose project 이름은 `monitoring-manager-e2e`다. 개발용 구성의 container, network, volume을 건드리지 않는다.
  - image 이름은 `:e2e` tag를 쓴다. 개발용 `:local` image를 덮어쓰지 않는다.
  - 개발용 구성은 8080, 8081, 13306을 쓴다. E2E는 18081 하나만 연다.
- `manager-api` 설정은 모두 환경 변수로 넣고 secret 파일을 쓰지 않는다.
  - DB 비밀번호와 `APP_SECRET_KEY`는 E2E 전용 값을 Compose 파일에 그대로 적는다. 이 DB는 port를 열지 않고 실행이 끝나면 사라진다.
  - `TELEGRAM_BOT_TOKEN`은 비운다. `TELEGRAM_API_BASE_URL`, `PROMETHEUS_URL`, `ALERTMANAGER_URL`, `SNMP_EXPORTER_RELOAD_URL`은 연결할 수 없는 주소(`http://127.0.0.1:9`)로 둔다. 설정이 잘못돼도 외부로 요청이 나가지 않는다.
- Playwright `globalSetup`이 실행 환경을 준비한다.
  1. Chromium이 없으면 `npm run test:e2e:install`을 실행하라고 알리고 멈춘다.
  2. `docker compose version`이 실패하면 Docker가 필요하다고 알리고 멈춘다.
  3. 이전 실행이 남긴 container를 `down -v --remove-orphans`로 지운다.
  4. `up -d --build --wait`로 띄운다. 실패하면 `logs`를 출력하고 `down -v`한 뒤 멈춘다.
  5. teardown 함수를 돌려준다. teardown은 테스트 결과와 관계없이 `down -v --remove-orphans`를 실행한다.
- 실패하면 screenshot과 trace를 `frontend/test-results/`에 남긴다. 이 디렉터리는 Git에 넣지 않는다.
- worker는 1개, 재시도는 0회다. 흐름마다 이름에 고유 값을 붙여 실행 순서와 관계없이 통과하게 한다. 불안정한 테스트를 재시도로 숨기지 않는다.

### 검증 명령

| 명령 | 변경 전 | 변경 후 |
|---|---|---|
| `npm test` | 없음 | `vitest run` |
| `npm run test:e2e` | 없음 | `playwright test` |
| `npm run test:e2e:install` | 없음 | `playwright install chromium` |
| `scripts/verify fast` | Frontend lint, build, Backend 하위 package 테스트 | Frontend lint, `npm test`, build, Backend 하위 package 테스트 |
| `scripts/verify all` | Frontend lint, build, `./gradlew build` | Frontend lint, `npm test`, build, `./gradlew build`, `npm run test:e2e` |

E2E는 가장 느리므로 `all`의 마지막에 둔다. 앞 단계가 실패하면 E2E까지 가지 않는다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `frontend/package.json`, `package-lock.json` | 테스트 devDependencies와 npm script 추가. dependencies는 그대로 |
| `frontend/vitest.config.ts` | 새 파일. Vitest 설정 |
| `frontend/tsconfig.node.json` | `include`에 `vitest.config.ts`, `playwright.config.ts` 추가 |
| `frontend/tsconfig.e2e.json`, `frontend/tsconfig.json` | E2E 코드용 tsconfig를 만들고 references에 추가. `tsc -b`가 E2E 코드 타입도 검사한다 |
| `frontend/src/test/` | 새 디렉터리. 컴포넌트 테스트 공통 준비 코드 |
| `frontend/src/**/*.test.ts(x)` | 새 컴포넌트 테스트 |
| `frontend/playwright.config.ts` | 새 파일. Playwright 설정 |
| `frontend/e2e/` | 새 디렉터리. `compose.yml`, `global-setup.ts`, 흐름별 `*.spec.ts` |
| `frontend/.gitignore`, `frontend/.dockerignore` | `test-results/`, `playwright-report/` 추가 |
| `scripts/verify` | `fast`와 `all`에 위 명령 연결 |
| `docs/TESTING.md`, `docs/assets/test-scope.svg` | Frontend 테스트 종류, 위치, 이름, profile, 범위 그림 |
| `docs/DEVELOPMENT.md`, `AGENTS.md` | 준비 단계의 Chromium 설치와 명령 |
| `docs/ARCHITECTURE.md` | Frontend 위치 표에 `src/test/`, `e2e/` 추가 |

운영 코드(`frontend/src`의 화면 코드, `backend/`), 운영용 `compose.yml`, `infrastructure/`, `Dockerfile`, `nginx.conf`는 고치지 않는다. 테스트하기 어려운 화면 코드를 발견해도 고치지 않고 보고한다.

## 위험과 되돌리기

- **E2E 첫 실행이 느리다**: Backend image build가 Gradle 의존성을 container 안에서 내려받는다. 두 번째 실행부터는 Docker layer cache를 쓴다. 첫 실행과 두 번째 실행 시간을 재서 보고한다.
- **18081 port가 이미 쓰인다**: `E2E_WEB_PORT`로 바꿀 수 있다. 바꾸는 방법을 `DEVELOPMENT.md`에 적는다.
- **jsdom이 Radix Dialog 일부를 지원하지 않는다**: `setup.ts`에 필요한 최소 polyfill만 넣는다. polyfill로도 안 되는 동작은 컴포넌트 테스트에서 빼고 E2E에서 확인한다. 뺀 동작은 보고한다.
- **E2E가 불안정하다**: 고정 시간 대기를 쓰지 않는다. role과 label로 요소를 찾고 Playwright의 자동 대기를 쓴다. 완료 기준에 따라 두 번 연달아 실행해 확인한다.
- **E2E가 중단돼 container가 남는다**: 다음 실행이 시작할 때 먼저 지운다. 직접 지울 때는 `docker compose -p monitoring-manager-e2e down -v`를 쓴다.
- **frontend image build가 느려진다**: `Dockerfile`의 `npm ci`가 테스트 devDependencies도 설치한다. 번들 결과는 같다. 늘어난 시간을 보고한다.
- **되돌리기**: 운영 코드와 운영 설정을 바꾸지 않으므로 이 작업의 커밋을 revert하면 된다. Database와 데이터 이전이 없다.

## 테스트 계획

| 완료 기준 | 테스트 |
|---|---|
| 컴포넌트 테스트가 Docker와 Backend 없이 통과한다 | Docker를 끈 상태에서 `scripts/verify fast` |
| "컴포넌트 테스트 대상" 규칙마다 테스트가 있다 | `lib/api.test.ts`, `CheckFormDialog.test.tsx`, `ServerFormDialog.test.tsx`, `TelegramDestinationDialog.test.tsx`, `ServerSnmpSection.test.tsx` |
| 컴포넌트 테스트가 규칙이 깨지면 실패한다 | 규칙마다 화면 코드를 일부러 망가뜨려 실패 확인 후 되돌림 |
| E2E 세 흐름이 빈 Database에서 통과한다 | `e2e/checks.spec.ts`, `e2e/servers.spec.ts`, `e2e/notifications.spec.ts`를 `scripts/verify all`로 실행 |
| E2E를 두 번 연달아 실행해도 통과한다 | `npm run test:e2e` 두 번 |
| E2E 실패 뒤 container가 남지 않는다 | 기대값을 일부러 틀리게 바꿔 실행한 뒤 `docker ps -a --filter name=monitoring-manager-e2e` |
| 개발용 구성이 떠 있어도 통과하고 개발용 데이터가 그대로다 | 개발용 Compose 구성을 띄운 채 E2E 실행. 전후 Check 목록 비교 |
| E2E가 Telegram API와 실제 secret 파일을 쓰지 않는다 | `e2e/compose.yml` 리뷰. `secrets` 항목이 없고 Telegram 주소가 연결할 수 없는 주소다 |
| 운영 번들에 테스트 코드와 도구가 없다 | `npm run build` 뒤 `dist/`에서 `vitest`, `msw`, `playwright`, 테스트 문구 검색 |
| 문서와 그림이 바뀐 내용과 맞다 | `node scripts/check-docs.mjs --render`, `--font "Malgun Gothic"` 실행과 PNG 확인 |

## 작업 순서

- [x] 요구사항, 계획, ADR 커밋
- [x] Vitest 도구 설정, `src/test/` 준비 코드, `npm test`, `scripts/verify` 연결
- [x] API 호출 공통 처리 테스트
- [x] Check 입력 화면 테스트
- [x] Server·Telegram 그룹 입력 화면과 SNMP 설정 화면 테스트
- [x] Playwright 설정, `e2e/compose.yml`, `global-setup.ts`, `scripts/verify all` 연결
- [x] E2E 세 흐름
- [x] `TESTING.md`, `DEVELOPMENT.md`, `AGENTS.md`, `ARCHITECTURE.md`, 범위 그림 수정
- [ ] 완료 기준 확인, 새 세션 리뷰, 요구사항 상태를 "구현됨"으로, ADR을 "승인"으로 바꾸고 이 계획 문서 삭제

## 남길 결정

- Frontend 테스트 도구와 E2E 실행 환경: [ADR 0014](../decisions/0014-frontend-test-tools.md)
