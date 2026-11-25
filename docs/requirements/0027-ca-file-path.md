# 0027. OS의 CA 인증서 파일을 경로로 붙이기

- 상태: 승인
- 작성일: 2026-11-25
- 관련: [0013](0013-core-env-settings.md), [0015](0015-core-minimal-image.md), [0017](0017-core-shared-env.md), [0023](0023-version-cleanup-rollback.md), [ADR 0012](../decisions/0012-core-render-outside-image.md), [OPERATIONS.md](../OPERATIONS.md)

## 배경

Blackbox Exporter의 HTTPS 확인은 모니터링 서버 OS의 CA 인증서 파일을 쓴다. 지금은 운영자가 설치할 때 그 파일을 설치 폴더의 `certs/ca-certificates.crt`에 복사하고, compose 파일이 복사본을 Blackbox Exporter 컨테이너에만 읽기 전용으로 붙인다([0015](0015-core-minimal-image.md), [ADR 0012](../decisions/0012-core-render-outside-image.md)). 운영 명령은 복사본이 있고 컨테이너가 읽을 수 있는지만 확인한다.

복사본은 OS가 CA 인증서를 갱신해도 따라 바뀌지 않는다. 운영자가 OS의 CA를 갱신하거나 사내 인증 기관을 OS에 등록할 때마다 다시 복사해야 하고, 잊으면 새 인증서를 쓰는 HTTPS 주소의 확인이 실패한다. 관리자 서버 쪽 사람이 복사하지 않고 OS의 파일 경로를 설정 파일(`.env`)에 적어 바로 붙이기로 정했다(사람 확인: 2026-11-25, 관리자 서버 Testigo_Mng).

OS의 CA 인증서 파일은 링크인 경우가 많다. 예를 들어 RHEL 계열의 `/etc/pki/tls/certs/ca-bundle.crt`는 다른 파일을 가리키는 링크다.

CA 경로 값은 아래 문서의 문장과 부딪친다. 이 요구사항과 그 ADR은 아래 문장만 대신하고, 이전 문서는 고치지 않는다.

- [0015](0015-core-minimal-image.md)의 하지 않는 것: "설치 폴더의 `certs/` 밖의 CA 인증서 파일을 쓰게 하는 것. 설정 파일(`.env`)에 CA 파일 경로를 적는 값을 두지 않는다". 목표의 "운영자가 설치할 때 ... `certs/ca-certificates.crt`에 복사하고"는 경로를 적지 않았을 때의 동작으로 남는다. 운영 명령이 OS의 파일을 찾거나 복사하지 않는다는 것, CA 인증서 파일을 Blackbox Exporter에만 붙이는 것, 묶음에 CA 인증서 파일을 넣지 않는 것은 그대로다.
- [ADR 0012](../decisions/0012-core-render-outside-image.md)의 결정: "운영자가 운영 가이드대로 설치 폴더의 `certs/`에 복사하고, compose 파일이 그 파일을 Blackbox Exporter 컨테이너에만 읽기 전용으로 붙인다". 운영 명령이 파일을 확인만 한다는 것은 그대로다.
- [0013](0013-core-env-settings.md)이 정한 서버에서 바꾸는 값 목록. 이 요구사항이 CA 인증서 파일 경로를 더한다. 숫자 값의 허용 범위와 검사 규칙은 그대로다.

## 목표

- 운영자가 `.env`에 OS의 CA 인증서 파일 경로를 적으면, compose 파일이 그 파일을 Blackbox Exporter 컨테이너에만 `/etc/ssl/certs/ca-certificates.crt`로 읽기 전용으로 붙인다. 복사하지 않는다.
- OS가 CA 인증서를 갱신한 뒤 운영자가 띄우기 명령(`start`)을 실행하면, Blackbox Exporter가 바뀐 CA 인증서 파일을 쓴다.
- 값을 비우면 지금처럼 설치 폴더의 `certs/ca-certificates.crt`(저장소에서는 `infrastructure/certs/ca-certificates.crt`)를 쓴다. 이미 설치한 서버, Windows 개발용 띄우기, 예전 버전으로 되돌리기가 그대로 동작한다.
- 형식이 틀린 경로나 쓸 수 없는 파일이면 운영 명령이 아무것도 바꾸지 않고 멈추고, 무엇이 틀렸고 어떻게 고치는지 안내한다.
- 운영 가이드의 설치 절차가 CA 인증서 파일 복사 대신 `.env`에 경로를 적게 안내한다.

## 하지 않는 것

- 운영 명령이 OS의 CA 인증서 파일을 찾거나, 경로를 `.env`에 대신 적거나, 복사하는 것. OS별 경로는 운영 가이드에 적는다.
- Prometheus, Alertmanager에 CA 인증서 파일을 붙이는 것
- 파일 내용이 CA 인증서인지 검사하는 것. 지금처럼 내용은 OS와 운영자가 관리한다.
- 상태 명령(`status`)에 CA 인증서 파일 경로를 보이는 것. token 폴더 값처럼 보이지 않는다.
- 설치 폴더의 `certs/` 복사본을 운영 명령이 지우는 것
- 주소마다 다른 CA 인증서 파일을 쓰는 것
- 숫자 설정 값의 종류, 허용 범위, 검사 규칙을 바꾸는 것

## 사용자 경험

모니터링 시스템을 설치하고 운영하는 운영자 관점이다.

새로 설치할 때는 다음 순서를 따른다.

1. 운영자가 설치 절차대로 `install`로 token과 `.env`를 만든다.
2. 운영자가 `.env`의 CA 인증서 파일 경로 값에 모니터링 서버 OS의 파일 경로를 적는다. 운영 가이드에 OS별 경로가 있다. Ubuntu, Debian, Amazon Linux는 `/etc/ssl/certs/ca-certificates.crt`, RHEL 계열은 `/etc/pki/tls/certs/ca-bundle.crt`다.
3. 운영자가 `start`를 실행한다.

이미 설치한 서버는 다음과 같다.

1. 운영자가 새 버전으로 갱신해도 `.env`에 경로를 적지 않았으면 지금처럼 `certs/` 복사본을 쓴다.
2. 경로를 적고 `start`를 실행하면 그때부터 OS의 파일을 쓴다.
3. 되돌릴 수 있는 예전 버전이 남아 있는 동안은 `certs/` 복사본을 지우지 않는다. 예전 버전은 새 값을 모르고 복사본을 쓰기 때문이다. 지울 수 있는 때는 운영 가이드에 적는다.

OS의 CA 인증서를 갱신하거나 사내 인증 기관을 OS에 등록했으면, 경로를 적은 서버는 다시 복사하지 않고 `start`만 실행한다. 이때 세 컨테이너를 다시 만든다.

## 동작

아래에서 "CA 경로 값"은 `.env`(또는 같은 이름의 셸 환경 변수)에 적는 CA 인증서 파일 경로다. 이름은 `MONITORING_CA_FILE`이다(사람 확인: 2026-11-25). 운영 명령과 Compose는 다른 설정 값처럼 셸 환경 변수를 `.env`보다 먼저 본다.

### 정상 동작

- **S1. 경로를 적음**
  - 상황: CA 경로 값에 OS의 CA 인증서 파일 경로(예: `/etc/ssl/certs/ca-certificates.crt`)를 적었다.
  - 행동: 운영자가 `start`를 실행한다.
  - 결과: Blackbox Exporter 컨테이너의 `/etc/ssl/certs/ca-certificates.crt`가 그 파일이다. 읽기 전용이고, 다른 컨테이너에는 붙지 않는다. 설치 폴더의 `certs/`는 읽지 않는다.
- **S2. 링크인 경로**
  - 상황: CA 경로 값이 다른 파일을 가리키는 링크다(예: RHEL의 `/etc/pki/tls/certs/ca-bundle.crt`).
  - 행동: 운영자가 `start`를 실행한다.
  - 결과: 링크가 가리키는 실제 파일을 검사하고 붙인다.
- **S3. 값을 비움**
  - 상황: CA 경로 값이 없거나 비어 있다.
  - 행동: 운영자가 `start`나 `render`를 실행하거나, 개발자가 `monitoring-dev.bat start`를 실행한다.
  - 결과: 지금처럼 설치 폴더의 `certs/ca-certificates.crt`(저장소에서는 `infrastructure/certs/ca-certificates.crt`)를 검사하고 붙인다.
- **S4. OS의 CA가 바뀜**
  - 상황: CA 경로 값을 적은 서버에서 OS의 CA 인증서 파일 내용이 바뀌었다.
  - 행동: 운영자가 `start`를 실행한다.
  - 결과: 운영 명령이 바뀐 내용을 알아채고 세 컨테이너를 다시 만든다. 지금 `certs/` 복사본을 다시 복사했을 때와 같다. 내용이 그대로면 컨테이너를 다시 만들지 않는다.
- **S5. 예전 버전으로 되돌림**
  - 상황: CA 경로 값을 적은 서버에서, 이 값을 모르는 예전 버전으로 되돌린다. `certs/` 복사본이 남아 있다.
  - 행동: 운영자가 `rollback`을 실행한다.
  - 결과: 예전 버전이 지금처럼 `certs/` 복사본으로 뜬다.

### 예외 동작

- **S6. 형식이 틀린 경로**
  - 상황: CA 경로 값이 `/`로 시작하지 않거나, 영문자, 숫자, `.`, `_`, `/`, `-` 밖의 글자(공백, `:`, 한글 등)를 담고 있다.
  - 행동: 운영자가 `start`나 `render`를 실행한다.
  - 결과: 펼친 설정과 컨테이너를 바꾸지 않고 멈춘다. 값 이름, 적힌 값, 허용하는 형식, 고치는 방법(값을 고치거나 비우기)을 보인다.
- **S7. 쓸 수 없는 파일**
  - 상황: CA 경로 값이 가리키는 파일(링크면 실제 파일)이 없거나, 폴더거나, 비어 있거나, 다른 사용자가 읽을 수 없다.
  - 행동: 운영자가 `start`나 `render`를 실행한다.
  - 결과: 펼친 설정과 컨테이너를 바꾸지 않고 멈춘다. 지금의 CA 인증서 파일 확인과 같은 문제를 알리되, 운영자가 적은 경로는 OS의 파일이므로 그 파일이나 폴더를 지우거나 권한을 바꾸라고 안내하지 않고 `.env`의 값을 고치라고 안내한다.
- **S8. 값을 비웠는데 복사본이 없음**
  - 상황: CA 경로 값이 비어 있고 설치 폴더의 `certs/ca-certificates.crt`도 없다.
  - 행동: 운영자가 `start`를 실행한다.
  - 결과: 지금처럼 멈추고, `.env`에 OS의 CA 인증서 파일 경로를 적는 방법을 OS별 경로와 함께 안내한다.

### 보안

- 접근 권한: 바뀌지 않는다. `.env`는 운영자가 고치고, 운영 명령은 운영자 권한으로 실행한다.
- 외부 입력: `.env`나 셸 환경 변수의 CA 경로 값. 운영자가 적는 값이지만 compose 파일의 볼륨 표기(`원본:대상:ro`)에 그대로 들어가므로, 표기를 깨는 글자(`:`, 공백 등)를 받지 않는다.
- 민감한 정보: 없음. CA 인증서 파일은 공개된 인증서 묶음이다.
- 남길 보안 이벤트: 없음

### 바뀌지 않는 것

- CA 인증서 파일을 Blackbox Exporter에만 읽기 전용으로 붙이고, 컨테이너 안 경로가 `/etc/ssl/certs/ca-certificates.crt`인 것
- 경로를 적지 않은 서버의 동작과 운영 명령의 안내 밖 동작
- 운영 명령이 OS의 파일을 찾거나 복사하지 않는 것
- 다른 설정 값의 이름, 허용 범위, 검사 규칙
- 묶음에 CA 인증서 파일이 들어가지 않는 것

## 완료 기준

- [ ] C1. CA 경로 값에 절대 경로를 적고 `render`하면 성공하고, 펼친 `monitoring.env`의 CA 해시가 그 파일 내용의 해시다. Compose가 Blackbox Exporter에만 그 파일을 `/etc/ssl/certs/ca-certificates.crt:ro`로 붙인다. (S1) — 확인: 운영 명령 시험(`infrastructure/tests/monitoring-server.test.sh`), 빌드 검사의 compose 확인
- [ ] C2. CA 경로 값이 실제 파일을 가리키는 링크면 성공하고, 링크가 폴더, 없는 파일, 빈 파일, 다른 사용자가 읽을 수 없는 파일을 가리키면 실패한다. 실패 안내에 그 파일을 지우거나 권한을 바꾸라는 말이 없고 `.env`의 값을 고치라는 말이 있다. (S2, S7) — 확인: 운영 명령 시험
- [ ] C3. 값이 없거나 비어 있으면 지금과 같은 `certs/ca-certificates.crt`를 검사하고 붙인다. 기존 운영 명령 시험과 빌드 검사가 그대로 통과한다. (S3, S8) — 확인: 운영 명령 시험, `node scripts/build-monitoring-bundle.mjs --check`
- [ ] C4. CA 경로 값의 파일 내용을 바꾸고 `start`하면 Blackbox Exporter 컨테이너를 다시 만들고, 바꾸지 않았으면 다시 만들지 않는다. (S4) — 확인: 운영 명령 시험
- [ ] C5. 상대 경로, 공백·`:`·한글·`$` 등을 담은 경로를 적으면 펼친 설정을 바꾸지 않고 실패하고, 안내에 값 이름과 허용 형식이 있다. (S6) — 확인: 운영 명령 시험
- [ ] C6. 묶음의 실행용 compose 파일에서 값을 비웠을 때의 기본값이 설치 폴더의 `certs/ca-certificates.crt`(`../certs/ca-certificates.crt`)다. (S3, S5) — 확인: 빌드 검사
- [ ] C7. Linux 모니터링 서버(또는 Linux VM)에서 링크인 OS의 CA 인증서 파일 경로를 적고 띄우면, 공인 인증서를 쓰는 HTTPS 주소의 확인이 성공한다. (S1, S2) — 확인: 수동 확인
- [ ] C8. [OPERATIONS.md](../OPERATIONS.md)의 설치 절차가 CA 인증서 파일 복사 대신 `.env`에 경로를 적게 하고 OS별 경로를 보이며, OS의 CA를 갱신하면 `start`를 실행한다는 것, 되돌릴 수 있는 예전 버전이 남은 동안 `certs/` 복사본을 지우지 않는다는 것과 지울 수 있는 때가 있다. `.env.example`에 값의 뜻과 예가 있다. [ARCHITECTURE.md](../ARCHITECTURE.md)의 설정 값 목록에 이 값이 있다. 이 결정을 남긴 ADR이 있다. (목표) — 확인: 문서 리뷰
- [ ] C9. `node scripts/build-monitoring-bundle.mjs --check`와 `node scripts/check-docs.mjs`가 통과한다. — 확인: 명령 실행

## 확인 필요

없음
