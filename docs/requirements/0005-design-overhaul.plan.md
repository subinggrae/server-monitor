# 0005. 시각 체계와 화면 전면 재정비 구현 계획

- 요구사항: [0005. 시각 체계와 화면 전면 재정비](0005-design-overhaul.md)
- 상태: 승인

## 접근 방법

승인한 시각 체계([DESIGN.md](../design/DESIGN.md))를 토큰 파일과 공통 컴포넌트에 먼저 넣고, 화면은 그 토큰과 컴포넌트만 쓰게 고친다. 화면을 하나씩 다시 그리는 대신 바닥(토큰, 글꼴, 상태 표시, 표, 절 제목)부터 바꾸면 모든 화면이 함께 바뀌고, 화면별 작업은 정보 배치와 문구에 집중할 수 있다.

1. **토큰과 글꼴**: 미리 보기 페이지에서 계산으로 확인한 값으로 `frontend/src/index.css`의 색 토큰을 바꾸고, 상태 글자 색(`--status-critical-foreground`, `--status-warning-foreground`), 글자 크기 다섯 단계, 그림자 하나, 움직임 길이와 곡선 토큰을 더한다. 화면 코드에 흩어진 `text-[13px]`, `text-[11px]` 같은 값을 토큰으로 바꾼다. 다크 모드 블록이 둘로 중복된 것은 그대로 두되 두 곳을 함께 고친다(CSS만으로 미디어쿼리와 `data-theme`를 함께 쓰는 방식이라 합칠 수 없다).
2. **글꼴**: npm 패키지 `pretendard`(1.3.9, OFL-1.1, 공식 저장소 orioncactus/pretendard, 설치 스크립트 없음)의 가변 글꼴 동적 서브셋 CSS를 `main.tsx`에서 불러온다. Vite가 쓰는 글자 묶음의 woff2만 `dist`에 넣으므로 사내망에서 CDN 없이 보인다. 글꼴 파일을 고치거나 서브셋을 새로 만들지 않아 Reserved Font Name 문제가 없다.
3. **공통 컴포넌트**: `StatusDot`이 위험은 경고 아이콘, 경고는 마름모, 나머지는 점으로 그리고, Check·서버·리소스·장애의 상태를 모두 이 컴포넌트로 그린다. `AlertListPage`와 대시보드가 따로 그리던 장애 상태도 여기로 옮긴다. `SectionHeader`, `PageHeader`, 표 컴포넌트의 글자와 간격을 토큰으로 맞춘다.
4. **화면 글**: TERMS.md의 화면 글 표와 PRODUCT.md의 제약을 먼저 고치고, [화면 글 수정 목록](#화면-글-수정-목록)에 따라 화면 글을 바꾼다. 오류 문구 가운데 Backend가 돌려주는 `detail`과 `fieldErrors` 메시지도 같은 용어로 고친다. 응답 형식은 그대로다.
5. **대시보드**: 위험 수(`--text-figure`)와 "모든 감시 항목이 정상입니다"를 맨 위에 두고, 위험한 감시 항목 표에 시작 시각과 지속 시간을 보인다. 시작 시각은 그 Check의 발생 중인 장애 가운데 가장 이른 `startsAt`이다(기존 장애 API, 새 API 없음). 감시 항목과 장애 query에 30초 `refetchInterval`을 두고, 마지막 성공 시각(`dataUpdatedAt`)을 보인다. 새로고침 실패면 이전 값을 두고 확인하지 못했다는 문구를 더한다.
6. **목록**: Check 목록의 기본 순서를 위험 → 알 수 없음 → 정상 → 사용 안 함으로 두고, 열 머리글 정렬과 기존 필터를 URL 검색 매개변수(`sort`, `status`, `q` 등)에 담는다. 정한 값이 아니면 무시한다. 장애 목록은 발생 중을 위에 두고 상태 필터를 더한다.
7. **Check 상세**: 기존 장애 API 응답을 `checkId`로 걸러 최근 장애 다섯 건을 보인다. 장애 API가 최근 N건만 돌려주므로 오래된 장애는 빠질 수 있다는 한계를 화면 안내에 쓰지 않고 위험에 적는다.
8. **화면 검사 넓히기**: `playwright.config.ts`에 다크 모드 project 둘(`screen-1280-dark`, `screen-1920-dark`, `colorScheme: 'dark'`)을 더하고, `screen.spec.ts`에 API를 500으로 바꾼 오류 화면 검사를 더한다(`page.route`). 토큰을 바꾼 뒤 `--subtle-foreground` 기준선을 지운다.

검토했지만 고르지 않은 방법은 다음과 같다.

- 글꼴 woff2 파일을 저장소에 직접 넣음: 패키지 버전 관리와 lockfile 기록을 잃는다. 동적 서브셋 파일이 90개 넘게 들어온다.
- Google Fonts 같은 CDN: 사내망에서 쓸 수 없을 수 있다(요구사항의 하지 않는 것).
- 컴포넌트 라이브러리를 새로 들임: 지금 shadcn/ui 기반 컴포넌트로 시각 체계를 만들 수 있고, 의존성을 늘릴 이유가 없다.
- 화면별로 처음부터 다시 그림: 같은 문제(글자 크기, 상태 표시)를 화면마다 따로 고치게 된다.
- Check 상세에 결과 띠: 새 API가 필요하다(요구사항의 하지 않는 것).

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `frontend/package.json`, `package-lock.json` | `pretendard` 1.3.9를 `dependencies`에 추가 |
| `frontend/src/main.tsx`, `frontend/src/index.css` | 글꼴 불러오기, 색·글자·그림자·움직임 토큰, 기본 글자 크기, `zero` 숫자 |
| `frontend/src/components/StatusDot.tsx` | 모양으로 구분하는 상태 표시, 장애 상태 |
| `frontend/src/components/ui/*.tsx`, `PageHeader.tsx`, `QueryStates.tsx`, `ConfirmDialog.tsx`, `Toolbar.tsx`, `AppLayout.tsx` | 토큰 적용, 로딩 문구 `…`, "닫기", 메뉴 이름 |
| `frontend/src/pages/DashboardPage.tsx` | 위험 우선 배치, 시작 시각과 지속 시간, 30초 새로고침, 마지막 확인 시각 |
| `frontend/src/pages/CheckListPage.tsx`, `AlertListPage.tsx` | 기본 순서, 정렬, 필터의 URL 상태 |
| `frontend/src/pages/CheckDetailPage.tsx` | 최근 장애 |
| `frontend/src/pages/*`, `frontend/src/components/**`, `frontend/src/lib/*.ts` | 문구 검토 목록의 수정(용어, 사실과 다른 안내 다섯 곳, 내부 용어, 코드 값 노출, 단위 공백) |
| `frontend/src/lib/format.ts` | `formatDateTime`을 `Intl.DateTimeFormat('ko-KR')`로(RULES.md의 날짜와 숫자 형식), 지속 시간 형식 |
| `backend/.../*Request.java`, `*Service.java`, `snmp/SnmpAuthConfigSyncService.java` | 화면에 보이는 검증·오류 문구(사용자 탓, 같은 오류의 다른 문구, 내부 용어) |
| `frontend/playwright.config.ts`, `frontend/e2e/screen.spec.ts` | 다크 모드 project, 오류 화면 검사, 기준선 삭제 |
| `frontend/e2e/*.spec.ts`, `frontend/src/**/*.test.tsx` | 바뀐 화면 글에 맞춘 이름 찾기 |
| `docs/PRODUCT.md`, `docs/TERMS.md` | 용어 기준과 화면 글 표 |
| `docs/design/DESIGN.md` | 컴포넌트 절의 새 패턴(지속 시간 표시, URL 정렬·필터) |
| `docs/TESTING.md` | 화면 검사가 다크 모드와 오류 화면을 검사함, 기준선 없음 |
| `frontend/src/pages/SettingsPage.tsx`, `vite.config.ts` | 오픈소스 고지 절: Pretendard(OFL-1.1) 출처와 라이선스, 번들한 패키지 라이선스 파일(Vite `build.license`) 링크(사람 확인: 2026-10-15) |
| `docs/LICENSES.md` | 고지 위치와 라이선스 파일 설정 |

### 화면 글 수정 목록

2026-10-15 검토 결과다. 줄 번호는 그날 `main` 기준이다. 용어 바꾸기(감시 항목, 장애, 복구, 위험·경고, 임계값, 리소스, 호스트, 포트, 필터, 토픽 ID, 받는 그룹)는 화면 전체에 적용하고, 아래에는 용어 밖의 수정만 적는다.

- **사실과 다른 안내**
  - `DashboardPage.tsx:173`: SNMP를 설정하지 않았다는 서버 절 안내를 SNMP 상태별 서버 수로 바꾼다.
  - `ServerListPage.tsx:42`, `:57`, `ServerFormDialog.tsx:31`: "사용할 예정", "붙일 때"를 지금 되는 일로 바꾼다.
  - `SettingsPage.tsx:73`: 내부 일정을 지운다. 기본 Threshold 자리는 0004에서 지운다.
  - `NotFoundPage.tsx:11`: 메뉴가 없는 화면이므로 "왼쪽 메뉴에서" 대신 주소 안내와 대시보드 링크를 쓴다.
  - `ServerFormDialog.tsx:146`: 끄면 SNMP 수집을 멈춘다고 쓴다.
- **내부 용어(RULES.md의 화면 글)**
  - `api.ts:28`, `AppLayout.tsx:106`, `SettingsPage.tsx:24`, `:30`: Backend, "서버와 통신하지 못했습니다"를 Monitoring Manager와 결과 문장으로 바꾼다. PRODUCT.md의 말투 예시도 함께 고친다.
  - `CheckDetailPage.tsx:93`, `:134`, `AlertListPage.tsx:22`, `:36`, `repeatInterval.ts:6`, `ServerSnmpSection.tsx:124`: Prometheus, Alertmanager, Service Discovery, snmp_exporter를 사용자가 겪는 결과로 바꾼다.
  - `NotificationsPage.tsx:97`, `:109`, `SettingsPage.tsx:62`: 환경 변수 이름은 보조 문구로 옮긴다.
- **코드 값 노출**: `ServerResourceSection.tsx:119`, `:240`의 `MEMORY`, `STORAGE`를 메모리, 스토리지로 보인다.
- **같은 것의 다른 이름**
  - 같은 목록의 "Check", "Monitoring Check", "Check 목록"(`CheckListPage.tsx:51`, `DashboardPage.tsx:45`, `CheckDetailPage.tsx:63`)
  - "Message Thread ID"와 "Topic"(`TelegramDestinationDialog.tsx:134`, `NotificationsPage.tsx:154`)
  - Check 설명 두 가지(`CheckListPage.tsx:52`, `:67`)
  - 장애 목록과 대시보드의 머리글 "Check"(내용은 요약 문장, `AlertListPage.tsx:45`, `DashboardPage.tsx:127`)는 "내용"으로
  - "등록하기"와 "추가"(`CheckNotificationSection.tsx:58`)
- **RULES.md 형식 규칙**
  - 로딩 문구를 `…`로(`QueryStates.tsx:12`, `:35`, `CheckDetailPage.tsx:37`, `ServerDetailPage.tsx:49`, `DashboardPage.tsx:48`, `:168`, `SettingsPage.tsx:21`, `ServerResourceSection.tsx:70`)
  - 창만 닫는 "취소"를 "닫기"로(`CheckFormDialog.tsx:343`, `ServerFormDialog.tsx:155`, `TelegramDestinationDialog.tsx:162`, `ConfirmDialog.tsx:33`), 한 컴포넌트 안의 혼용(`ServerResourceSection.tsx:181`, `:213`)
  - 진행 문구를 동작 이름으로(`ConfirmDialog.tsx:36`의 "처리 중…")
  - 버튼에 일어날 일(`NotificationsPage.tsx:187`의 "테스트 메시지" → "테스트 메시지 보내기")
  - 숫자와 단위 사이 줄바꿈 없는 공백(`ServerSnmpSection.tsx:254`, `format.ts:22`, `:26`)
  - 화면 라벨과 다른 `aria-label`(`CheckFormDialog.tsx:259`, `:271`의 `Target 1`, `Port 1`)
  - 번역투(`NotificationsPage.tsx:236`)
  - 영어 소문자 낱말(`CheckListPage.tsx:143`, `ServerListPage.tsx:121`의 filter, `ServerSnmpSection.tsx:112`, `:182`, `CheckFormDialog.tsx:48`, `:53`, `ServerFormDialog.tsx:97`)
- **Backend 오류 문구**
  - 사용자 탓: `MonitoringCheckService.java:214`, `:219`의 "올바르지 않습니다" → 입력 예시를 든 안내
  - 같은 오류의 세 가지 문구: 포트 범위(`MonitoringCheckRequest.java:69`, `MonitoringCheckService.java:231`, `ServerSnmpRequest.java:24`, `ServerSnmpSection.tsx:141`)
  - 내부 용어: `SnmpAuthConfigSyncService.java:81`, `:88`, `ResourceDiscoveryService.java:61`
  - 표기: `MonitoringCheckRequest.java:52`의 Target, `MonitoringCheckService.java:201`, `:204`, `ServerTargetRequest.java:13`의 Host·hostname, `TelegramDestinationRequest.java:18`

### 데이터 모델 변경

해당 없음

### 계약 변경

- 화면용 API의 오류 문구(`detail`, `fieldErrors`의 메시지)가 바뀐다. 응답 형식과 상태 코드는 그대로다. 이 저장소의 화면만 호출한다.
- Alertmanager·Telegram webhook, HTTP SD: 해당 없음

## 위험과 되돌리기

- 화면 글을 바꾸면 컴포넌트 테스트와 E2E가 화면 글로 요소를 찾으므로 함께 고쳐야 함: 문구를 바꾸는 커밋에서 테스트의 이름 찾기만 고치고, 검사하는 동작과 기대값의 뜻은 바꾸지 않는다. 테스트를 지우거나 느슨하게 하지 않는다.
- 글꼴 크기: 동적 서브셋이라 처음 화면에 필요한 조각만 받는다. 성능 검사(C7)에서 지금 점수(100)보다 크게 떨어지면 멈추고 묻는다.
- 30초 새로고침이 API와 Prometheus 조회를 늘림: 대시보드를 연 창 하나당 30초에 Check·장애 조회 각 한 번이다. 장애 목록이 이미 같은 간격을 쓴다.
- 대시보드의 시작 시각을 장애 API의 최근 N건에서 찾으므로, 아주 오래된 장애는 시작 시각이 비어 "알 수 없음"으로 보일 수 있음: 받아들이고 화면에 "시작 시각 모름"으로 보인다. 필요하면 장애 API에 Check 필터를 더하는 별도 작업으로 넘긴다.
- 0004와 같은 파일(`StatusDot.tsx`, `ServerResourceSection.tsx`, `SettingsPage.tsx`, `api.ts`)을 고침: 이 작업을 먼저 병합하고 0004 브랜치를 그 위로 옮긴다. 순서는 사람에게 묻는다.
- 되돌리기: DB와 계약 형식이 바뀌지 않아 커밋을 되돌리면 끝난다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 없음. URL 검색 매개변수가 화면 상태로 들어온다.
- 잘못될 수 있는 것과 막는 방법
  - 조작한 URL 매개변수(CWE-20): 정렬 열과 필터 값을 허용 목록으로만 받고, 아니면 기본값을 쓴다. 값을 HTML로 그리지 않는다.
  - 악성이거나 이름을 흉내 낸 패키지(CWE-1395): `pretendard`의 레지스트리 정보, 관리자, 설치 스크립트 없음을 확인했다. 설치 뒤 `npm audit --audit-level=high`를 실행하고 lockfile을 함께 커밋한다.
- 받아들이는 위험: 없음

## 테스트 계획

컴포넌트 테스트는 `cd frontend && npm test`, 화면 검사는 전체 실행 환경을 띄운 뒤 `cd frontend && npm run test:screen`과 `npm run test:perf`로 실행한다.

| 완료 기준 | 테스트 | 확인 |
|---|---|---|
| C1 | 문서 리뷰: DESIGN.md에 "아직 정하지 않았다"가 없고 승인 날짜가 있음 | 이미 충족(2026-10-15) |
| C2 | REVIEW.md 코드 점검: 화면 코드에서 `text-[`, `#`·`oklch(` 리터럴, 토큰 밖 그림자·모서리 검색 결과 0 | `rg` 결과를 계획에 적는다 |
| C3 | `screen.spec.ts`: 기준선 없이 밝은·다크 모드, 기준 폭 둘, API 실패 화면에서 위반과 넘침 없음 | `npm run test:screen` 통과 |
| C4 | `DashboardPage.test.tsx`(새, 가짜 시계): 위험 수와 "모든 감시 항목이 정상입니다", 시작 시각과 "12분째", 30초 뒤 다시 조회, 실패 때 이전 값과 확인하지 못했다는 문구 | `npm test` |
| C5 | `CheckListPage.test.tsx`, `AlertListPage.test.tsx`(새): 기본 순서, 머리글 정렬과 필터가 URL에 남음, URL에서 복원, 정하지 않은 값은 기본값 | `npm test` |
| C6 | 문구 목록 리뷰: 화면 글 수정 목록이 모두 고쳐졌는지, TERMS.md의 쓰지 않는 말이 화면 코드에 없는지 검색 | 결과를 계획에 적는다 |
| C7 | 성능 검사, LICENSES.md 고지 | `npm run test:perf`의 점수와 LCP, CLS, TBT를 적는다 |
| C8 | 화면을 만들지 않은 하위 에이전트에게 스크린샷(기준 폭 둘 × 밝은·다크), 미리 보기 페이지, DESIGN.md, RULES.md, REVIEW.md만 주고 판정 | 판정 결과를 계획에 적는다 |

`StatusDot`의 모양 구분은 `StatusDot.test.tsx`(새)에서 상태마다 문구와 접근 가능한 이름을 확인한다. 모양 자체는 화면 점검의 스크린샷으로 본다.

## 작업 순서

- [x] `pretendard` 설치 전 확인(설치 스크립트, `npm audit`)과 설치, 글꼴 불러오기(`build` 커밋)
- 순서: 이 작업을 0004보다 먼저 구현하고 병합한다. 0004 브랜치는 그 뒤 `main` 위로 옮긴다(사람 확인: 2026-10-15).
- [x] 토큰 파일의 색·글자·그림자·움직임 토큰
- [x] 화면 검사 넓히기: 다크 모드 project와 오류 화면 검사를 먼저 더하고 지금 위반 목록 확인, 토큰 적용 뒤 기준선 삭제
- [x] `StatusDot` 테스트와 구현, 장애 상태 일원화
- [x] 공통 컴포넌트와 화면 코드의 크기·간격 리터럴을 토큰으로
- [x] TERMS.md·PRODUCT.md 고침, Backend 오류 문구 고침
- [x] 화면 글 고침, 테스트의 이름 찾기 고침
- [x] 화면 틀 컴포넌트(`components/layout/`: 목록, 상세, 개요, 설정 틀, 절의 조회 상태, 대화상자 폼 틀)와 테스트. 사람이 2026-10-15에 틀을 승인했다
- [x] 모든 라우트와 대화상자를 틀로 옮김(C10). 서버 목록 표 아래 안내 → 알림 띠, 감시 항목 상세의 중복 편집 버튼 삭제, 절 안 폼의 저장 버튼 위치 통일, 절 조회 상태 통일
- [x] 설정 화면의 오픈소스 고지 절
- 브랜드 컴포넌트 모양은 브랜드 자료를 받기 전이라 #13으로 넘겼다. 이 작업을 먼저 병합하고 OS exporter(0006, #12)를 그 위에서 만든다(사람 확인: 2026-10-15).
- [x] 대시보드 테스트 작성(C4)과 실패 확인, 구현
- [x] 목록 테스트 작성(C5)과 실패 확인, 구현
- [x] Check 상세의 최근 장애
- [x] 코드 점검(C2, C6), 화면 점검(기준 폭 × 테마 스크린샷, 넘침, 대비), 성능 검사(C7), LICENSES.md 고지
- [x] 하위 에이전트 판정(C8)
- [x] 판정 실패 항목 고침
  - 받는 그룹이 없는 감시 항목: 빨간 글자 대신 경고 단계(`WarningLabel`, `Notice`의 `warning`)
  - 감시 항목 상세 머리: 위험이면 "N분째"(없으면 "시작 시각 모름")와 마지막 확인 시각. `firingSince`는 `lib/alerts.ts`로 옮겨 대시보드와 함께 쓴다.
  - Telegram 그룹 목록의 건수, 추가 대화상자 셋의 실행 버튼 "추가", "받는 그룹이 없는 감시 항목 N개는 …" 문장
  - 서버 상세의 지적 넷(리소스 빈 상태의 중복 문단과 다음 동작 없음, 임계값 안내가 알림 띠 밖에 있음, 탭과 같은 이름의 절 제목, 마지막 수집 시각 없음)은 고치지 않는다. 요구사항 0006(#12)이 서버 상세의 수집·리소스 절을 다시 만들면서 고친다(0006 계획의 화면 절에 적음).
- [x] 전체 검증(2026-10-16): `npm run check`(테스트 56개), `./gradlew build`, 화면 검사 72개, E2E 2개, 성능 97점(LCP 1.0 s, CLS 0.032, TBT 0 ms). 실행 환경은 `--profile local-db`도 켜야 뜬다.
- [ ] 구현하지 않은 하위 에이전트의 코드 리뷰(완료 기준, 이 계획, diff, CWE-20, CWE-1395)
- [ ] 요구사항 상태를 구현됨으로 바꾸고 이 계획을 지움(병합 전 마지막 커밋). PR은 사람이 2026-10-15에 0005를 먼저 병합하기로 정했으므로 묻지 않고 만든다. 병합 뒤 #13의 남은 것을 브랜드 모양만으로 고친다.

## 진행 중 발견

- `@theme inline`에 `--shadow-overlay: var(--shadow-overlay)`처럼 같은 이름을 두면 Tailwind가 `@layer theme`에 자기 참조 변수를 낸다. 나중 값이 이겨 동작은 했지만, 테마 값은 `--overlay-shadow`, `--scrim`처럼 다른 이름으로 두고 `@theme inline`에서 연결했다.
- Tailwind 기본 글자 크기를 `--text-*: initial`로 꺼서, 토큰 밖의 크기 클래스(`text-xs` 등)를 쓰면 스타일이 생기지 않는다. 코드 점검(C2)의 글자 크기 항목을 빌드가 대신 막는다.
- Vite 8의 `build.license`로 번들한 패키지 라이선스를 `dist/licenses.txt`에 낸다. `pretendard`는 `pretendard - 1.3.9 (OFL-1.1)`로 들어간다.
- 화면 검사(2026-10-15): 토큰을 바꾼 뒤 기준선 없이 72개(라우트 7, 대화상자 3, 오류 상태 6, 상세 2 × 기준 폭 2 × 테마 2)가 두 번 연달아 통과했다. `--subtle-foreground`를 옛 값(0.63)으로 잠시 되돌리자 설정 화면 검사가 `color-contrast`로 실패해, 기준선 없는 검사가 대비를 잡는 것을 확인했다. 기준선을 지우기 전 첫 실행이 한 번 종료 코드 255로 끝났지만 출력이 잘려 어떤 테스트인지 보지 못했고, 이후 세 번은 모두 통과했다.
- 이 PC의 8080은 다른 프로젝트가 써서, 저장소 밖 compose override로 `manager-api`의 호스트 포트를 18080으로 바꿔 띄웠다(`ports: !override`). Telegram은 `TELEGRAM_API_BASE_URL=http://127.0.0.1:1`로 막았다.
- 라이트 `--destructive`를 0.52에서 0.50으로 어둡게 했다. 삭제 버튼과 오류 글자가 밝은 바탕에서 4.5:1을 넉넉히 넘게 하기 위해서다.
- 브랜드 모양 전 점검(2026-10-15)
  - 코드 점검(C2): 화면 코드(`*.tsx`)에서 색 리터럴, `text-[`, 토큰 밖 그림자·모서리, 임의 px 간격, 자간 조정을 찾았다. 남은 것은 예시 주소 문자열뿐이다. 세 곳에 복사돼 있던 투명도 경고 상자는 `Notice`로 모았다.
  - 화면 글(C6): TERMS.md의 쓰지 않는 말은 메뉴와 화면 제목의 병기(`감시 항목(Check)`, `장애 기록(Alert)`)에만 남았다.
  - 화면 검사 72개, E2E 2개가 통과했다.
  - 성능(C7): 점수 98, LCP 1.0 s, CLS 0.032, TBT 0 ms. #3 때(100, 0.7 s, 0.012)보다 조금 낮다. 글꼴을 불러오면서 생긴 차이로 본다(추측).
- 하위 에이전트가 화면을 틀로 옮기면서 `FormDialog`에 `noValidate`를 둬 브라우저 범위 검사가 빠졌다. 범위는 서버가 검증하고 칸별 오류를 같은 자리에 보이므로 그대로 둔다. 저장 중에도 [닫기]를 누를 수 있다.
- 화면 판정(C8, 2026-10-15): 화면을 만들지 않은 하위 에이전트가 스크린샷 25개(라우트 8개와 추가 대화상자, 1280 밝은·다크, 1920 밝은)으로 판정했다. 대시보드, 감시 항목 목록, 서버 목록, 장애 기록, 설정, 대화상자는 모든 항목 통과다. 실패와 처리는 작업 순서의 "판정 실패 항목 고침"에 있다.
- 코드 리뷰는 시작 직후 멈췄다(사람이 세션을 정리하면서 중단). 결과가 없으므로 처음부터 다시 맡긴다.

## 남길 결정

없음. 시각 체계는 DESIGN.md에, 용어 기준은 PRODUCT.md와 TERMS.md에 쓴다.
