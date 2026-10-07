# 0014. Frontend는 Vitest 컴포넌트 테스트와 실제 Backend에 붙는 Playwright E2E로 검증한다

- 상태: 승인
- 날짜: 2026-10-01
- 관련: [요구사항 0001](../requirements/0001-frontend-component-e2e-tests.md), [TESTING.md](../TESTING.md)

## 배경과 문제

Frontend에 자동 테스트가 없다. 화면에만 있는 규칙(입력 칸 전환, 기존 Target id 전달, 오류 표시)과 화면·API 계약을 확인할 방법이 필요하다.

제약은 다음과 같다.

- Frontend는 Vite 8, React 19, TypeScript 6을 쓴다. 테스트 도구가 Vite 설정과 `@` alias를 그대로 써야 한다.
- `scripts/verify fast`는 Docker 없이 돌아야 한다. CI가 없어 공유 반영 전 `scripts/verify all`이 유일한 전체 검증이다.
- 테스트가 실제 Telegram이나 운영 secret을 쓰면 안 된다.
- `DEVELOPMENT.md`는 Node.js 22를 요구한다.

## 검토한 선택지

컴포넌트 테스트 실행기

- **Vitest + jsdom**: Vite 설정을 그대로 읽고 Node.js만 있으면 돈다 / 실제 브라우저가 아니어서 layout과 일부 pointer 동작이 다르다.
- **Vitest browser mode**: 실제 브라우저에서 그린다 / `fast`에도 브라우저가 필요하다.
- **Jest**: 자료가 많다 / ESM, TypeScript, Vite alias를 위한 변환 설정을 따로 유지해야 한다.
- **happy-dom**: jsdom보다 빠르다 / Radix 같은 접근성 기반 component와의 호환 사례가 jsdom보다 적다.

API 응답 대체

- **MSW**: URL과 method로 응답을 정하고 처리하지 않은 요청을 오류로 만들 수 있다 / 의존성이 하나 는다.
- **`fetch`를 직접 mock**: 의존성이 없다 / 호출 순서와 인자 모양에 테스트가 묶인다.

E2E 대상 환경

- **실제 Backend와 빈 Manager Database**: 화면·API 계약 불일치를 찾는다 / Docker가 필요하고 수 분 걸린다.
- **Frontend만 띄우고 API mock**: 빠르다 / 컴포넌트 테스트와 겹치고 계약 불일치를 못 찾는다.

E2E 브라우저

- **Playwright가 내려받는 Chromium**: PC마다 같은 브라우저로 돈다 / 처음 한 번 인터넷 연결과 내려받기가 필요하다.
- **PC에 설치된 Chrome·Edge**: 내려받지 않는다 / 브라우저 version이 PC마다 달라 결과가 달라질 수 있다.

## 결정

- 컴포넌트 테스트는 Vitest, jsdom, Testing Library, MSW로 쓴다. Node.js 22.13 이상에서 도는 jsdom 29를 쓴다. MSW는 Vitest가 선택 peer로 요구하는 2를 쓴다. 3을 쓰면 Node.js 22 기본 npm 10의 `npm ci`가 실패한다.
- E2E는 Playwright와 Playwright가 내려받는 Chromium으로 쓴다. Playwright version은 정확히 고정한다.
- E2E는 E2E 전용 Compose project로 MariaDB, Backend, Frontend image를 띄워 실행한다. 운영용 `compose.yml`과 secret 파일을 쓰지 않고, Prometheus·Alertmanager·exporter·Telegram에는 연결하지 않는다.
- 컴포넌트 테스트는 `fast`와 `all`, E2E는 `all`에서 실행한다.

화면 규칙은 빠르고 Docker 없이 도는 컴포넌트 테스트가 맡고, 화면·API 계약은 실제 Backend에 붙는 소수의 E2E 흐름이 맡는다. 같은 동작을 두 수준에서 반복 검사하지 않는다.

## 결과

- 좋아지는 점: 화면 규칙의 회귀를 `fast`에서 찾는다. Backend 응답이 바뀌어 화면이 깨지는 것을 `all`에서 찾는다.
- 감수할 점: `all`이 E2E 시간만큼 길어지고 Chromium을 한 번 내려받아야 한다. 수집 상태(OK·CRITICAL)와 알림 전송은 E2E로 확인하지 않는다. jsdom 때문에 브라우저에 기대는 동작 일부는 E2E에서만 확인한다.
- 다시 볼 신호: CI를 도입할 때 E2E 실행 위치를 다시 정한다. Node.js 요구 version을 22.22.2 이상으로 올리면 jsdom 30으로 올릴 수 있다.

## 확인 방법

원래 기록에 없다.
