# 0001. Frontend 컴포넌트 테스트와 E2E 테스트 도입

- 상태: 구현됨
- 작성일: 2026-10-01
- 관련: [TESTING.md의 테스트 범위](../TESTING.md#테스트-범위)

## 배경

Frontend에는 자동 테스트가 없다. 화면 동작은 lint, 타입 검사, 사람이 직접 화면을 열어 보는 방법으로만 확인한다.

Backend 통합 테스트는 API가 맞게 동작하는지는 확인하지만, 화면이 그 API를 맞게 부르는지는 확인하지 않는다. 그래서 다음과 같은 회귀를 자동으로 찾지 못한다.

- Check 종류를 바꾸면 대상 입력을 비우고 TCP일 때만 Port 칸을 보여 주는 동작처럼, 화면에만 있는 규칙이 깨진다.
- Check를 수정할 때 기존 대상의 id를 함께 보내지 않으면 Prometheus time series가 끊긴다. 이 id는 화면이 보낸다.
- Backend가 돌려준 입력 오류가 해당 입력 칸 옆에 보이지 않는다.
- 화면이 기대하는 응답 모양과 Backend 응답이 어긋난다.

## 목표

- 화면에만 있는 규칙과 API 오류 처리를 컴포넌트 테스트로 확인한다. 이 테스트는 Docker와 Backend 없이 돈다.
- 핵심 사용자 흐름을 실제 Backend와 Manager Database에 연결한 브라우저에서 E2E 테스트로 확인한다.
- 개발자가 `scripts/verify fast`로 컴포넌트 테스트를, `scripts/verify all`로 E2E 테스트까지 실행한다.
- 새 테스트를 어디에 어떤 기준으로 쓰는지 `TESTING.md`에서 알 수 있다.

## 하지 않는 것

- 모든 화면과 컴포넌트에 테스트를 쓰지 않는다. 분기가 있는 화면 규칙과 핵심 흐름만 다룬다.
- 화면 모양 비교(스크린숏 비교) 테스트를 하지 않는다.
- E2E에서 Prometheus, Alertmanager, exporter, 실제 SNMP 장비, Telegram에 연결하지 않는다. 그래서 Check와 Server의 수집 상태(OK·CRITICAL)와 알림 전송은 E2E로 확인하지 않는다. 이 부분은 Backend 통합 테스트가 맡는다.
- CI를 만들지 않는다.
- 운영 화면과 API 동작을 바꾸지 않는다.

## 사용자 경험

사용자는 개발자다.

| 하는 일 | 명령 | 필요한 것 |
|---|---|---|
| 화면 코드를 고친 뒤 빠르게 확인 | `scripts/verify fast` | Node.js. Docker 필요 없음 |
| 공유 반영 전에 전체 확인 | `scripts/verify all` | Node.js, JDK 21, Docker, E2E용 Chromium |
| 컴포넌트 테스트만 실행 | Frontend 테스트 명령 하나 | Node.js |
| E2E만 실행 | E2E 명령 하나 | Docker, E2E용 Chromium |

E2E용 Chromium은 처음 한 번 명령으로 내려받는다. 이 명령과 위 표의 실제 명령은 `DEVELOPMENT.md`에 적는다.

## 동작

### 정상 동작

- **컴포넌트 테스트 실행**
  - 상황: `frontend` 의존성을 설치했다. Backend와 Docker는 없다.
  - 행동: 컴포넌트 테스트 명령을 실행한다.
  - 결과: API 응답을 테스트가 만들어 화면을 그리고 결과를 판정한다. 네트워크 밖으로 요청을 보내지 않는다.

- **E2E 테스트 실행**
  - 상황: Docker가 떠 있고 E2E용 Chromium을 내려받았다.
  - 행동: E2E 명령을 실행한다.
  - 결과: 빈 Manager Database와 Backend, 화면을 띄우고 브라우저로 흐름을 실행한다. 끝나면 띄운 container를 내린다. 실패하면 어느 단계에서 실패했는지와 그때의 화면을 남긴다.

- **E2E 흐름: Check 관리**
  - 대상 두 개로 HTTP Check를 추가한다. 목록과 상세 화면에서 이름, 종류, 대상 두 개를 확인한다.
  - 대상 하나를 지우고 하나를 더해 수정한다. 상세 화면에 바뀐 대상이 보인다.
  - TCP Check를 Port 없이 저장하면 오류 안내가 보이고 저장되지 않는다.
  - Check를 삭제하면 목록에서 사라진다.

- **E2E 흐름: Server와 SNMP 설정**
  - Server를 추가하고 상세 화면에서 SNMP를 설정한다.
  - 저장한 뒤 화면 어디에도 입력한 community가 보이지 않는다. community 칸은 비어 있고, community가 설정되어 있다는 안내가 보인다.
  - 연결 테스트를 누르면 장비가 없으므로 실패 안내가 보인다.

- **E2E 흐름: Telegram 그룹과 알림 연결**
  - Telegram 그룹을 등록하고 Check에 알림을 연결한다. Check 상세 화면에서 연결된 그룹과 critical·warning 설정을 확인한다.
  - Bot Token이 없으므로 알림 화면에 Bot Token이 설정되지 않았다는 안내가 보인다. 테스트 메시지 전송은 실패 안내로 끝난다.

- **컴포넌트 테스트 대상**
  - Check 입력 화면: 종류를 바꾸면 대상 입력이 비워진다. Port 칸은 Port가 필요한 종류에서만 보인다. 대상이 하나뿐이면 삭제할 수 없다. 수정할 때 기존 대상 id를 함께 보낸다.
  - Check·Server·Telegram 그룹 입력 화면: Backend가 돌려준 칸별 오류는 해당 칸에, 칸이 없는 오류는 입력 화면 아래에 보인다.
  - SNMP 설정 화면: community를 비워 두고 저장하면 기존 community를 유지하도록 빈 값을 보낸다. 저장은 됐지만 exporter 반영이 실패하면 경고가 보인다.
  - API 호출 공통 처리: 오류 응답의 `detail`과 `fieldErrors`를 읽는다. 응답 본문이 없거나 네트워크가 끊기면 정해진 안내 문구를 쓴다. 204 응답은 본문 없이 성공으로 처리한다.

### 예외 동작

- **Docker가 없을 때 `all` 실행**
  - 상황: Docker가 떠 있지 않다.
  - 행동: `scripts/verify all`을 실행한다.
  - 결과: Backend 통합 테스트와 같이 실패한다. 원인이 Docker라는 것을 출력에서 알 수 있다.

- **E2E용 Chromium이 없을 때**
  - 상황: Chromium을 내려받지 않았다.
  - 행동: E2E를 실행한다.
  - 결과: 테스트를 시작하기 전에 멈추고, 내려받는 명령을 알려 준다.

- **개발용 구성이 이미 떠 있을 때**
  - 상황: 개발용 Compose 구성이나 개발 서버가 떠 있다.
  - 행동: E2E를 실행한다.
  - 결과: E2E는 개발용 구성과 port, container, 데이터를 함께 쓰지 않는다. 개발용 데이터를 지우거나 바꾸지 않는다.

- **E2E가 중간에 실패할 때**
  - 상황: 테스트 하나가 실패한다.
  - 행동: E2E가 끝난다.
  - 결과: 띄운 container를 내린다. 다음 실행은 빈 Database로 시작한다.

### 바뀌지 않는 것

- 운영 화면, API, Database schema, 운영용 `compose.yml`과 `infrastructure/` 설정
- `npm run build` 결과물. 테스트 코드와 테스트 도구는 운영 번들에 들어가지 않는다.
- `scripts/verify fast`는 Docker 없이 돈다.
- 실제 secret 파일(`infrastructure/secrets/` 아래 값 파일)을 읽지 않는다. E2E는 테스트 전용 값을 쓴다.

## 완료 기준

- [x] 컴포넌트 테스트가 Docker와 Backend 없이 통과한다 — 확인: Docker에 연결할 수 없게 한 상태(`DOCKER_HOST`)에서 `npm test`. 개발용 구성이 떠 있어 Docker 자체는 끄지 않았다
- [x] 동작의 "컴포넌트 테스트 대상"에 적은 규칙마다 테스트가 있다 — 확인: 테스트 목록 리뷰
- [x] 컴포넌트 테스트가 해당 규칙이 깨지면 실패한다 — 확인: 규칙마다 화면 코드를 일부러 망가뜨려 실패를 본 뒤 되돌린다
- [x] E2E 세 흐름이 빈 Database에서 통과한다 — 확인: `scripts/verify all`
- [x] E2E를 두 번 연달아 실행해도 통과한다 — 확인: E2E 명령 두 번 실행
- [x] E2E 실패 뒤 container가 남지 않는다 — 확인: 일부러 실패시킨 뒤 `docker ps -a`
- [x] 개발용 Compose 구성이 떠 있어도 E2E가 통과하고 개발용 데이터가 그대로다 — 확인: 수동
- [x] E2E가 Telegram API와 실제 secret 파일을 쓰지 않는다 — 확인: E2E 설정 리뷰
- [x] 운영 번들에 테스트 코드와 테스트 도구가 없다 — 확인: `npm run build` 결과물 검색
- [x] `TESTING.md`, `DEVELOPMENT.md`, `AGENTS.md`의 명령과 테스트 범위 그림이 바뀐 내용과 맞다 — 확인: `node scripts/check-docs.mjs --render`

## 확인 필요

없음. 2026-10-01에 다음을 확정했다.

- E2E를 넣어 `all`이 길어지는 시간에 상한을 두지 않는다. 구현 후 실제 시간을 보고한다.
- E2E가 띄우는 Backend는 지금 코드로 build한 image를 쓴다. image build 시간이 더해지는 것을 허용한다.
- 컴포넌트 테스트는 브라우저 대신 Node.js의 가상 DOM에서 돈다. Dialog 열림처럼 브라우저에 기대는 동작 일부는 E2E에서만 확인한다.
