# 0028. 경로 설정 값을 운영 명령과 Compose가 같게 읽기

- 상태: 초안
- 작성일: 2026-11-25
- 관련: [0011](0011-core-ops-script.md), [0013](0013-core-env-settings.md), [0017](0017-core-shared-env.md), [0027](0027-ca-file-path.md), [ADR 0018](../decisions/0018-ca-file-path.md), [OPERATIONS.md](../OPERATIONS.md)

## 배경

모니터링 서버의 `.env`에는 경로 설정 값이 두 개 있다. token 폴더 값(`MONITORING_SECRETS_DIR`)은 Alertmanager가 관리자 서버의 webhook을 부를 때 붙이는 token 파일의 폴더다. CA 인증서 파일 경로 값(`MONITORING_CA_FILE`)은 Blackbox Exporter가 HTTPS 확인에 쓰는 OS의 CA 인증서 파일이다([0027](0027-ca-file-path.md)). 운영 명령(`monitoring-server.sh`)은 두 값으로 파일 위치를 정해 검사하고, compose 파일은 같은 값으로 그 파일을 컨테이너에 붙인다. 운영 명령이 검사한 파일과 Compose가 붙이는 파일이 같아야 검사가 뜻이 있다.

운영 명령의 `.env` 읽기는 Compose를 흉내 내지만 똑같지 않다. 2026-11-25에 같은 표기의 `.env`를 Compose(Docker Compose v5.7.0)와 운영 명령(공식 Prometheus 이미지 v3.5.0에 든 BusyBox sh)에 주어 token 폴더 값을 비교했다. 결과는 다음과 같다.

- 운영 명령이 읽지 않는 표기로 token 폴더를 적으면, 운영 명령은 값이 없다고 보고 기본 위치(설치 폴더의 `secrets/`)의 token을 검사했다. 기본 위치에 token이 있으면 `render`가 성공했다. 같은 `.env`에서 Compose는 적힌 폴더의 token 파일을 붙이는 설정을 만들었다.
- 운영 명령이 값을 읽지만 Compose와 다른 값으로 읽는 표기도 있었다. 시험한 표기에서는 운영 명령이 실제로 없는 경로를 검사해 멈췄으므로, 검사하지 않은 파일로 뜨지는 않았다.

token 폴더 값은 `start`가 Compose에 넘기지 않는다. 그래서 Compose가 `.env`를 스스로 읽고, 위 첫 경우에 검사하지 않은 위치의 token 파일로 뜬다. 그 위치에 파일이 없으면 Docker가 그 자리에 빈 폴더를 만들고 Alertmanager가 계속 다시 시작한다([0011](0011-core-ops-script.md)의 배경). 파일이 있으면 검사를 거치지 않은 파일을 token으로 쓴다. 그 값이 관리자 서버의 token과 다르면 관리자 서버가 장애를 모두 거부하고, 실제 장애가 나기 전까지 아무도 모른다. 이 어긋남은 0027의 독립 리뷰에서 찾았다.

CA 인증서 파일 경로 값은 0027부터 `start`가 검사한 값을, 값이 없다고 본 경우에는 빈 값을 Compose에 넘긴다. 그래서 `start`로 띄우면 검사한 파일이 붙는다. 하지만 운영 명령이 읽지 않는 표기로 OS의 파일 경로를 적은 운영자는 그 파일 대신 설치 폴더의 복사본(`certs/ca-certificates.crt`)이 조용히 쓰이는 줄 모른다. 복사본이 오래됐으면 새 인증서를 쓰는 HTTPS 주소의 확인이 실패한다.

검사한 값을 Compose에 넘기는 것만으로는 token 폴더 값을 고칠 수 없다. 지금 이 어긋남 덕분에 관리자 서버와 같은 token으로 돌고 있는 서버가 있을 수 있기 때문이다. 운영 명령이 읽지 않는 표기로 다른 폴더를 적었고, 기본 위치에도 `install`이 만든 token이 있는 서버다. 이런 서버에서 검사한 기본 위치만 넘기면 다음 `start`부터 token이 조용히 바뀌어 장애가 거부된다. 또 운영 가이드의 token 바꾸기처럼 운영자가 묶음 폴더에서 직접 실행하는 `docker compose` 명령은 여전히 Compose가 `.env`를 스스로 읽는다([0017](0017-core-shared-env.md)의 목표). 그러면 `start`와 그 명령이 서로 다른 파일을 붙인다. 그래서 운영 명령이 읽지 않는 표기를 만나면 멈추고 운영자가 고치게 하기로 했다(사람 확인: 2026-11-25). CA 인증서 파일 경로 값도 같은 원인이므로 이번에 함께 멈추게 한다(사람 확인: 2026-11-25).

Windows 개발 환경의 `monitoring-dev.bat start`에도 같은 종류의 어긋남이 있다. 이 명령은 운영 명령의 `render`를 Linux 컨테이너 안에서 실행하고, Compose는 Windows에서 실행한다. 셸 환경 변수가 `.env`보다 먼저 쓰이도록 설정 값의 셸 환경 변수를 컨테이너에 넘기지만, token 폴더 값은 넘기지 않는다. 그래서 개발자가 셸 환경 변수로 token 폴더를 주면 `render`는 `.env`의 폴더를 검사하고, Compose는 셸 환경 변수의 폴더를 붙인다. 이 문제도 이번에 함께 고친다(사람 확인: 2026-11-25).

0027과 [ADR 0018](../decisions/0018-ca-file-path.md)은 고치지 않는다. 이 요구사항은 0027의 CA 인증서 파일 경로 값에 운영 명령이 읽지 않는 표기를 만나면 멈추는 동작만 더한다. 0027이 정한 형식 검사, 파일 검사, 기본값은 그대로다.

## 목표

- `start`로 띄운 Alertmanager가 쓰는 token 파일이 운영 명령이 검사한 파일과 같다. token 폴더를 `.env`에 어떤 표기로 적었든, 셸 환경 변수로 주었든, 적지 않았든 같다.
- `.env`에 token 폴더를 운영 명령이 읽지 않는 표기로 적었으면 `start`, `render`, `install`이 아무것도 바꾸지 않고 멈춘다. 그리고 그 줄을 `MONITORING_SECRETS_DIR=폴더` 형식으로 고치라고 안내한다. 그래서 token이 조용히 바뀌지 않고, 고친 뒤에는 운영자가 직접 실행하는 `docker compose` 명령도 같은 token 파일을 붙인다.
- `.env`에 CA 인증서 파일 경로를 운영 명령이 읽지 않는 표기로 적었으면 `start`와 `render`가 아무것도 바꾸지 않고 멈춘다. 그리고 그 줄을 `MONITORING_CA_FILE=경로` 형식으로 고치라고 안내한다. 이 값을 다루는 운영 명령은 이 둘뿐이다. `install`은 CA 인증서 파일을 다루지 않는다.
- 운영 명령과 Compose가 다른 값으로 읽는 표기로 두 값을 적었으면, `start`로 띄운 컨테이너에 운영 명령이 검사한 값의 파일이 붙거나 운영 명령이 검사에서 멈춘다.
- `monitoring-dev.bat start`에서 셸 환경 변수로 token 폴더를 주면 `render`가 그 폴더를 검사하고 Compose도 그 폴더의 token 파일을 붙인다.

## 하지 않는 것

- 운영 명령 없이 Compose로 직접 띄우는 경우(운영 가이드의 운영 명령 없이 실행, token 바꾸기의 `docker compose` 명령)에 Compose가 `.env`를 읽는 방식을 바꾸는 것. 이 경우에는 그 전에 실행한 `render`나 `start`의 멈춤이 운영 명령이 읽지 않는 표기를 막는다.
- token 폴더 값에 허용 글자나 형식 검사를 더하는 것. 지금 적어 둔 값으로 도는 서버가 멈출 수 있고, 이 값은 설정에 펼치지 않는다.
- CA 인증서 파일 경로 값의 형식 검사(0027의 S6)와 파일 검사(0027의 S7)를 바꾸는 것
- 숫자 설정 값, 서버 사용량 수집 값, 관리자 서버 주소의 읽기와 검사를 바꾸는 것
- 이 변경 전의 묶음으로 되돌린 경우의 동작. 예전 버전의 운영 명령은 바뀌지 않는다.
- 상태 명령(`status`)에 token 폴더나 CA 인증서 파일 경로를 보이는 것
- token 값을 새로 만들거나 옮기는 것
- `monitoring-dev.bat start`에서 Windows 경로를 token 폴더 값으로 쓰게 하는 것. `render`가 Linux 컨테이너 안에서 돌아 Windows 경로를 볼 수 없으므로, 그런 값이면 지금처럼 `render`가 token 파일이 없다고 멈춘다.

## 사용자 경험

모니터링 시스템을 설치하고 운영하는 운영자 관점이다.

1. 운영자가 `.env`에 두 경로 값을 `변수=값` 형식으로 적었거나 적지 않았으면 지금과 같다. 운영 명령이 그 값(적지 않았으면 기본 위치)의 파일을 검사하고 붙인다.
2. 운영 명령이 읽지 않는 표기로 적었으면 그 값을 다루는 운영 명령이 멈추고, 변수 이름과 고칠 형식을 보인다. token 폴더는 `start`, `render`, `install`이, CA 인증서 파일 경로는 `start`, `render`가 멈춘다. 떠 있는 모니터링 시스템은 그대로 돈다.
3. 운영자가 그 줄을 고친 뒤 다시 실행한다. 고친 값의 파일을 쓸 수 있으면 그대로 뜬다.

개발자는 Windows에서 `monitoring-dev.bat start`를 쓴다. 셸 환경 변수로 token 폴더를 주면 `render`와 Compose가 모두 그 폴더를 쓴다. `.env`의 표기는 운영자와 같은 규칙을 따른다.

## 동작

아래에서 "token 폴더 값"은 `.env`(또는 같은 이름의 셸 환경 변수)의 `MONITORING_SECRETS_DIR`이고, "CA 경로 값"은 `MONITORING_CA_FILE`이다. 운영 명령과 Compose는 다른 설정 값처럼 셸 환경 변수를 `.env`보다 먼저 본다. token 폴더의 상대 경로는 compose 파일이 있는 폴더(묶음 폴더) 기준이다.

### 정상 동작

- **S1. `.env`에 token 폴더를 적음**
  - 상황: `.env`에 `MONITORING_SECRETS_DIR=폴더` 형식으로 token 폴더를 적었고, 그 폴더에 쓸 수 있는 token 파일이 있다.
  - 행동: 운영자가 `start`를 실행한다.
  - 결과: 운영 명령이 그 폴더의 token 파일을 검사하고, Alertmanager에 그 파일이 붙는다.
- **S2. token 폴더를 적지 않거나 비움**
  - 상황: token 폴더 값이 없거나 비어 있다.
  - 행동: 운영자가 `start`를 실행한다.
  - 결과: 운영 명령이 설치 폴더의 `secrets/`(저장소에서는 `infrastructure/secrets/`)의 token 파일을 검사하고, Alertmanager에 그 파일이 붙는다.
- **S3. 셸 환경 변수로 token 폴더를 줌**
  - 상황: 셸 환경 변수 `MONITORING_SECRETS_DIR`가 있다.
  - 행동: 운영자가 `start`를 실행한다.
  - 결과: `.env`의 값과 관계없이 셸 환경 변수의 폴더를 검사하고, Alertmanager에 그 폴더의 token 파일이 붙는다.
- **S4. 운영 명령과 Compose가 token 폴더를 다르게 읽는 표기**
  - 상황: 운영 명령이 token 폴더 값을 읽지만, Compose가 같은 줄을 다른 값으로 읽는 표기로 적었다.
  - 행동: 운영자가 `start`를 실행한다.
  - 결과: Alertmanager에 운영 명령이 검사한 폴더의 token 파일이 붙는다. Compose가 스스로 읽은 값은 쓰지 않는다. 검사한 폴더에 쓸 수 있는 token이 없으면 S7대로 멈춘다.
- **S5. 운영 명령과 Compose가 CA 경로를 다르게 읽는 표기**
  - 상황: 운영 명령이 CA 경로 값을 읽지만, Compose가 같은 줄을 다른 값으로 읽는 표기로 적었다.
  - 행동: 운영자가 `start`를 실행한다.
  - 결과: 운영 명령이 읽은 값이 0027의 형식에 맞지 않으면 0027의 S6대로 멈춘다. 형식에 맞으면 Blackbox Exporter에 운영 명령이 검사한 파일이 붙는다. Compose가 스스로 읽은 값은 쓰지 않는다.
- **S6. 개발용 띄우기에서 셸 환경 변수로 token 폴더를 줌**
  - 상황: Windows의 저장소 폴더에서 셸 환경 변수 `MONITORING_SECRETS_DIR`에 저장소의 `infrastructure/` 기준 상대 경로를 주었고, 그 폴더에 쓸 수 있는 token 파일이 있다.
  - 행동: 개발자가 `monitoring-dev.bat start`를 실행한다.
  - 결과: `render`가 셸 환경 변수의 폴더를 검사하고, Compose가 띄운 Alertmanager에 그 폴더의 token 파일이 붙는다. 그 폴더에 쓸 수 있는 token이 없으면 `render`가 멈추고 Compose를 부르지 않는다.

### 예외 동작

- **S7. 검사한 폴더의 token을 쓸 수 없음**
  - 상황: 운영 명령이 읽은 token 폴더에 token 파일이 없거나, 그 자리에 폴더가 있거나, 파일이 비었거나, 컨테이너 사용자가 읽을 수 없다. S4의 표기로 적어 Compose가 읽는 위치에만 token이 있는 경우도 포함한다.
  - 행동: 운영자가 `start`나 `render`를 실행한다.
  - 결과: 지금처럼 경우를 구분해 알리고, Compose를 부르지 않고 펼친 설정을 바꾸지 않는다.
- **S8. 운영 명령이 읽지 않는 표기의 token 폴더**
  - 상황: `.env`에 token 폴더 값을 운영 명령이 읽지 않는 표기로 적었다. 기본 위치나 적은 폴더에 token이 있는지와는 관계없다.
  - 행동: 운영자가 `start`, `render`, `install` 가운데 하나를 실행한다.
  - 결과: 펼친 설정, token 파일, `.env`, 컨테이너를 바꾸지 않고 실패로 끝난다. Compose를 부르지 않는다. 안내에는 변수 이름, 운영 명령이 그 줄을 읽지 못했다는 것, `MONITORING_SECRETS_DIR=폴더` 형식으로 고치라는 방법이 있다. 떠 있는 모니터링 시스템은 그대로 돈다.
- **S9. 운영 명령이 읽지 않는 표기의 CA 경로**
  - 상황: `.env`에 CA 경로 값을 운영 명령이 읽지 않는 표기로 적었다. 적은 경로의 파일이나 설치 폴더의 복사본이 있는지와는 관계없다.
  - 행동: 운영자가 `start`나 `render`를 실행한다.
  - 결과: 펼친 설정과 컨테이너를 바꾸지 않고 실패로 끝난다. Compose를 부르지 않는다. 안내에는 변수 이름, 운영 명령이 그 줄을 읽지 못했다는 것, `MONITORING_CA_FILE=경로` 형식으로 고치라는 방법이 있다. 떠 있는 모니터링 시스템은 그대로 돈다.

### 보안

- 접근 권한: 바뀌지 않는다. `.env`는 설치 폴더에 쓸 수 있는 운영자가 고치고, 운영 명령은 운영자 권한으로 실행한다.
- 외부 입력: `.env`와 셸 환경 변수의 token 폴더 값과 CA 경로 값. 운영자가 적지만 복사해 붙인 값이나 셸에 남은 환경 변수처럼 의도하지 않은 값이 들어올 수 있다([ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)의 신뢰 경계). 운영 명령은 지금처럼 `.env`를 셸로 실행하지 않고 읽는다.
- 민감한 정보: webhook token. 운영 명령은 token 값을 화면, 로그, 설정 파일에 쓰지 않는다. S8의 안내에도 변수 이름과 경로만 보이고 token 값은 보이지 않는다. 법이 암호화를 요구하는 항목은 아니다. CA 인증서 파일은 공개된 인증서 묶음이다.
- 남길 보안 이벤트: 없음

### 바뀌지 않는 것

- 두 경로 값을 `변수=값` 형식으로 적었거나, 셸 환경 변수로 주었거나, 적지 않은 서버가 쓰는 파일 위치
- token 파일의 네 가지 검사(없음, 폴더, 빈 파일, 읽을 수 없음)와 그 안내
- CA 경로 값의 형식 검사와 파일 검사, 그 안내(0027의 S6, S7, S8)
- `install`이 token 폴더와 파일을 만드는 권한, 이미 있는 token과 `.env`를 덮어쓰지 않는 것
- compose 파일의 두 값 기본값(token 폴더는 저장소 `./secrets`, 묶음 `../secrets`, CA 인증서 파일은 저장소 `./certs/ca-certificates.crt`, 묶음 `../certs/ca-certificates.crt`)
- 숫자 설정 값, 서버 사용량 수집 값, 관리자 서버 주소의 읽기와 검사
- `monitoring-dev.bat start`가 넘기는 다른 설정 값과 CA 인증서 파일 준비

## 완료 기준

- [ ] C1. `.env`에 token 폴더를 운영 명령이 읽지 않는 표기로 적으면, 기본 위치와 적은 폴더에 token이 있든 없든 `start`, `render`, `install`이 실패 종료 코드로 끝난다. Compose를 부르지 않고, 펼친 설정과 token 파일과 `.env`가 바뀌지 않는다. 안내에 변수 이름과 고칠 형식이 있고 token 값은 없다. (S8) — 확인: 운영 명령 시험(`infrastructure/tests/monitoring-server.test.sh`). 이 멈춤을 지운 사본에서 시험이 실패하는지 본다.
- [ ] C2. `.env`에 CA 경로 값을 운영 명령이 읽지 않는 표기로 적으면, 적은 경로의 파일과 설치 폴더의 복사본이 있든 없든 `start`와 `render`가 실패 종료 코드로 끝난다. Compose를 부르지 않고 펼친 설정이 바뀌지 않으며, 안내에 변수 이름과 고칠 형식이 있다. 0027에서 이 표기로 복사본을 붙이던 시험은 이 기준에 맞게 바꾼다. (S9) — 확인: 운영 명령 시험. 이 멈춤을 지운 사본에서 시험이 실패하는지 본다.
- [ ] C3. `start`가 Compose를 부를 때 Compose가 받는 token 폴더 값이 운영 명령이 검사한 값과 같다. `.env`에 `MONITORING_SECRETS_DIR=폴더`로 적은 경우, 셸 환경 변수로 준 경우, 적지 않은 경우(빈 값), 운영 명령과 Compose가 다르게 읽는 표기로 적은 경우를 각각 확인한다. (S1~S4) — 확인: 운영 명령 시험. 값을 넘기지 않는 사본에서 시험이 실패하는지 본다.
- [ ] C4. 운영 명령과 Compose가 다르게 읽는 표기로 CA 경로 값을 적으면, `start`가 형식 검사에서 멈추거나 Compose에 운영 명령이 검사한 값을 넘긴다. (S5) — 확인: 운영 명령 시험. 값을 넘기지 않는 사본에서 시험이 실패하는지 본다.
- [ ] C5. 운영 명령이 읽은 token 폴더의 token을 쓸 수 없으면 `start`와 `render`가 실패 종료 코드로 끝나고 Compose를 부르지 않는다. 운영 명령과 Compose가 다르게 읽는 표기로 적어 Compose가 읽는 위치에만 token이 있는 경우도 확인한다. (S4, S7) — 확인: 운영 명령 시험
- [ ] C6. Linux Docker 호스트에서 실제 Compose로 `start`했을 때 Alertmanager에 붙은 token 파일과 Blackbox Exporter에 붙은 CA 인증서 파일이 운영 명령이 검사한 파일이다. C3, C4의 경우를 확인하고, C1, C2의 표기에서 멈추는 것도 확인한다. (S1~S5, S8, S9) — 확인: 수동 확인
- [ ] C7. Windows에서 셸 환경 변수 `MONITORING_SECRETS_DIR`에 `.env`와 다른 상대 경로 폴더를 주고 `monitoring-dev.bat start`를 실행하면, Alertmanager에 그 폴더의 token 파일이 붙는다. 그 폴더에 token이 없고 `.env`의 폴더에만 있으면 `render`가 멈추고 Compose를 부르지 않는다. (S6) — 확인: 수동 확인. 셸 환경 변수를 넘기지 않는 원래 명령에서는 두 번째 경우에 `render`가 통과하는 것을 먼저 본다.
- [ ] C8. 바뀐 동작에 맞춰 [OPERATIONS.md](../OPERATIONS.md)와 [ARCHITECTURE.md](../ARCHITECTURE.md)의 `.env` 설명과 신뢰 경계를 고쳤다. (목표) — 확인: 문서 리뷰
- [ ] C9. 기존 운영 명령 시험(C2에서 바꾸는 시험 밖), `node scripts/build-monitoring-bundle.mjs --check`, `node scripts/check-docs.mjs`가 통과한다. (바뀌지 않는 것) — 확인: 명령 실행

## 확인 필요

없음
