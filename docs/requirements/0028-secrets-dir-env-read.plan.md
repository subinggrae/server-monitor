# 0028. 경로 설정 값을 운영 명령과 Compose가 같게 읽기 구현 계획

- 요구사항: [0028. 경로 설정 값을 운영 명령과 Compose가 같게 읽기](0028-secrets-dir-env-read.md)
- 상태: 진행 중(사람 승인: 2026-11-25)

<!-- 상태 값: 검토 중 → 승인 → 진행 중. 작업 순서를 모두 끝내고 검증과 리뷰를 마치면, 남길 결정은 ADR로, 바뀐 동작은 요구사항이나 ARCHITECTURE.md로, 진행 중 발견 가운데 다음 작업에도 맞는 사실은 그 사실을 맡는 문서로 옮긴 뒤 이 파일을 지운다. 끝난 계획을 남기면 지금 동작으로 잘못 읽힌다. 지운 계획은 PR에서 다시 볼 수 있으므로 ADR의 관련 줄에 그 링크를 쓴다. 리뷰어가 이 계획을 기준으로 diff를 보므로 리뷰가 끝나기 전에는 지우지 않는다. 토픽 브랜치에만 두고 병합 전 마지막 커밋에서 지운다. -->

## 접근 방법

운영 명령(`infrastructure/monitoring-server.sh`)에 두 가지를 더한다. 운영 명령이 읽지 않는 `.env` 줄을 알아보고 멈추는 검사를 더하고, 검사한 token 폴더 값을 Compose에 넘긴다. CA 인증서 파일 경로 값은 0027부터 검사한 값을 넘기므로 멈추는 검사만 더한다. `monitoring-dev.bat`는 render 컨테이너에 넘기는 셸 환경 변수에 token 폴더를 더한다.

### 읽지 않는 줄을 알아보는 규칙

운영 명령은 `.env`를 셸로 실행하지 않고 `env_value`로 읽는다. `env_value`가 읽는 줄은 변수 이름 바로 뒤에(공백은 건너뜀) `=`가 오는 줄뿐이다. 새 검사는 같은 파일에서 다음 조건에 맞는 줄을 찾는다.

1. 줄 앞의 공백, `export`와 그 뒤 공백, 파일 첫머리에 편집기가 붙이는 보이지 않는 글자를 건너뛰면 변수 이름으로 시작한다.
2. 변수 이름 바로 뒤의 글자가 이름에 쓰는 글자(영문자, 숫자, `_`)가 아니다. 그래서 이름이 더 긴 다른 변수(앞부분이 같은 이름)의 줄은 고르지 않는다.
3. 그런데 `env_value`가 읽는 형식이 아니다. 파일 첫머리의 보이지 않는 글자가 붙은 줄도 `env_value`는 읽지 않으므로 여기에 든다.

이런 줄이 하나라도 있으면 그 변수는 운영 명령과 Compose가 다르게 읽을 수 있다고 보고 멈춘다. `#`로 시작하는 주석 줄은 1에서 걸러진다. 안내에는 변수 이름, 줄 번호, 고칠 형식(`MONITORING_SECRETS_DIR=폴더`, `MONITORING_CA_FILE=경로`)만 보이고 줄 내용은 보이지 않는다. 읽는 형식의 줄과 읽지 않는 줄이 함께 있어도 멈춘다. 셸 환경 변수가 있어 `start`는 그 값을 쓰는 경우에도 멈춘다(사람 확인: 2026-11-25). 셸 환경 변수 없이 운영자가 직접 실행하는 `docker compose` 명령은 그 줄을 읽기 때문이다.

이 규칙은 Compose가 받아들이는 표기를 하나씩 나열하지 않고, 운영 명령이 읽는 형식 밖을 모두 고른다. 그래서 Compose가 표기를 더 받아들이게 바뀌어도 그 줄이 변수 이름으로 시작하면 걸린다. 다른 변수의 여러 줄 값 안에 들어 있는 줄처럼 운영 명령은 읽지만 Compose는 다른 값의 일부로 읽는 줄은 이 규칙이 고르지 못한다. 이런 줄은 아래의 값 넘기기가 `start`에서 맞춘다. 운영자가 직접 실행하는 `docker compose`에서 남는 어긋남은 요구사항의 하지 않는 것에 있다.

### 검사를 두는 곳

두 값의 검사는 `check_settings`에 둔다. 다른 설정 값처럼 틀린 값을 모두 한 번에 보인 뒤 `settings_ok=no`로 멈추게 하기 위해서다. `render`와 `start`는 `check_render` → `check_settings`로 이 검사를 지난다. CA 인증서 파일 경로 값은 `check_ca_setting` 안에서 값을 읽기 전에 검사한다. token 폴더 값은 새 함수가 검사한 뒤 값을 읽어 export한다. 값이 없다고 보면 빈 값을 export한다(`check_ca_setting`과 같은 방식). 그 뒤의 `token_file`은 `env_value`가 셸 환경 변수를 먼저 보므로 export한 값을 그대로 쓴다.

`install`은 `check_settings`를 부르지 않는다. 그래서 `cmd_install`이 `token_file`을 부르기 전에 token 폴더 값의 검사만 따로 부르고, 걸리면 바로 멈춘다. `install`은 CA 인증서 파일 경로 값을 다루지 않으므로 그 검사는 부르지 않는다.

### 고르지 않은 방법

- **`env_value`가 Compose의 표기를 모두 읽게 맞추기**: Compose의 `.env` 읽기 규칙을 POSIX sh와 BusyBox로 따라 써야 하고, Compose가 바뀌면 다시 어긋난다. 그리고 지금 운영 명령이 읽지 않는 표기로 다른 폴더를 적은 서버에서는 검사하는 token이 바뀌어도 운영자가 모른다.
- **검사한 값을 넘기기만 하기(0027의 방식)**: 요구사항 배경대로 token이 조용히 바뀌고, 운영자가 직접 실행하는 `docker compose`와 `start`가 서로 다른 파일을 붙인다. 사람이 멈추기로 정했다(2026-11-25).
- **Compose에게 푼 값을 물어보기**(`docker compose config`의 결과에서 token 파일 경로를 읽기): Compose와 똑같이 읽지만, Docker가 없는 곳(`monitoring-dev.bat start`의 render 컨테이너, 빌드 PC의 검사)에서 `render`를 할 수 없다. 출력 형식에도 기대야 하고, 읽지 않는 표기를 쓴 운영자에게 고치라고 알리지 못한다.
- **알려진 표기 목록만 골라내기**: 목록에 없는 표기를 놓친다. 위 규칙은 운영 명령이 읽는 형식 밖을 모두 고른다.

## 바뀌는 부분

바뀌는 파일은 다음과 같다.

- **`infrastructure/monitoring-server.sh`**: 읽지 않는 줄을 찾는 함수와 안내를 더한다. `check_ca_setting`이 값을 읽기 전에 이 검사를 한다. token 폴더 값을 검사하고 export하는 함수를 더해 `check_settings`와 `cmd_install`에서 부른다. 머리 주석과 `check_ca_setting` 주석을 고친다.
- **`infrastructure/tests/monitoring-server.test.sh`**: 가짜 `docker`가 `compose up` 때 받은 `MONITORING_SECRETS_DIR` 값을 남긴다(`compose_ca_file`과 같은 방식). 테스트 계획의 시험을 더하고, 0027에서 읽지 않는 표기로 복사본을 붙이던 시험의 기대를 멈춤으로 바꾼다.
- **`monitoring-dev.bat`**: `start`의 render `docker run`에 `-e MONITORING_SECRETS_DIR`를 더한다. 셸 환경 변수를 넘긴다는 주석은 그대로 맞는다.
- **`infrastructure/.env.example`**: 머리 주석에 값은 `이름=값` 형식으로 한 줄에 적는다는 문장을 더한다.
- **`docs/OPERATIONS.md`**: 설치의 설정 값 적기에서 값 적는 법에 `변수=값` 형식과, 두 경로 값을 다른 형식으로 적으면 운영 명령이 멈춘다는 것을 더한다.
- **`docs/ARCHITECTURE.md`**: 지켜야 할 경계의 설정 값 항목과 신뢰 경계에, 운영 명령이 두 경로 값도 검사한 값을 Compose에 넘기고 읽지 않는 형식의 줄이 있으면 멈춘다는 것을 더한다. Compose가 `.env`에서 직접 읽는 값을 새로 더하면 같은 방식을 따른다는 규칙도 적는다.

### 데이터 모델 변경

해당 없음

### 계약 변경

해당 없음. HTTP SD와 webhook 계약, compose 파일의 변수와 기본값은 바뀌지 않는다. 운영자가 쓰는 `.env` 형식에서 두 경로 값이 `이름=값` 밖의 표기를 받지 않게 된다. 이 변화는 요구사항의 S8, S9에 있다.

## 위험과 되돌리기

- 읽지 않는 표기로 두 값을 적은 서버는 새 버전의 `start`가 멈춘다: 의도한 동작이다. 떠 있는 모니터링 시스템은 그대로 돌고, 안내대로 줄을 고치면 뜬다. 고칠 수 없으면 `rollback`으로 직전 버전에 돌아간다. 예전 버전의 운영 명령은 이 검사를 하지 않는다.
- 줄 고르기 규칙이 다른 변수의 줄을 잘못 고를 수 있다: 이름이 더 긴 변수, 주석 줄, 다른 변수의 값에 이름이 든 줄을 고르지 않는지 시험으로 확인한다. 잘못 고르면 안내의 줄 번호로 운영자가 알 수 있다.
- token 폴더 값을 export하면 Compose가 쓰는 값이 바뀌는 서버가 생길 수 있다: 운영 명령과 Compose가 다르게 읽는 표기를 쓴 서버만 해당한다. 시험한 표기에서는 지금 `start`가 그 값의 검사에서 멈추므로, 지금 `start`로 도는 서버의 token 파일은 바뀌지 않는다. C3의 시험과 C6의 수동 확인으로 본다.
- 0027 시험 하나의 기대가 성공에서 멈춤으로 바뀐다: 요구사항 C2대로 사람이 승인했다(2026-11-25). 시험을 지우지 않고 기대만 바꾼다.
- 되돌리기: 코드와 문서만 바뀌고 데이터 이전이 없다. 커밋을 되돌리면 이전 동작으로 돌아간다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 바뀌지 않는다. `.env`와 셸 환경 변수의 두 경로 값이 운영 명령과 Compose로 들어가는 흐름은 그대로다. 다만 `start`에서 Compose가 token 폴더 값을 `.env`에서 다시 읽지 않고 운영 명령이 검사한 값을 받는다.
- 잘못될 수 있는 것과 막는 방법:
  - 운영 명령과 Compose가 같은 `.env`를 다르게 읽어 검사하지 않은 파일을 붙임(CWE-20): 읽지 않는 줄이 있으면 멈추고, `start`는 검사한 값을 넘긴다. C1~C5의 시험으로 확인한다.
  - 검사와 띄우기 사이에 `.env`가 바뀌어 다른 값으로 뜸(CWE-367): `start`가 검사한 값을 넘기므로 Compose가 그 사이의 `.env`를 다시 읽지 않는다. C3의 시험으로 확인한다.
  - 안내나 시험 출력에 token 값이 나옴(CWE-532, CWE-798): 안내에는 변수 이름, 줄 번호, 경로만 쓰고 줄 내용과 token 파일 내용은 쓰지 않는다. C1의 시험이 출력에 시험용 token 값이 없는지 본다.
  - `.env` 내용이 명령으로 실행됨(CWE-78): 새 검사는 `.env`를 셸로 실행하거나 `eval`하지 않는다. 고정된 변수 이름으로만 패턴을 만들고 `sed`, `grep`으로 읽는다. 리뷰에서 확인한다.
- 받아들이는 위험:
  - token 폴더 값의 글자와 형식을 제한하지 않는다. 요구사항의 하지 않는 것에 있고, 사람이 요구사항과 함께 승인했다(2026-11-25).
  - 운영자가 직접 실행하는 `docker compose` 명령은 이 규칙이 고르지 못하는 줄을 `start`와 다르게 읽을 수 있다. 요구사항의 하지 않는 것에 있고, 사람이 요구사항과 함께 승인했다(2026-11-25).

## 테스트 계획

운영 명령 시험은 기존 `infrastructure/tests/monitoring-server.test.sh`에 더하고, 지금처럼 공식 Prometheus 이미지의 BusyBox `sh`에서 가짜 `docker`로 돌린다. 이 시험만 돌릴 때는 다음 명령을 쓴다. 첫 줄은 `monitoring-dev.bat`처럼 Prometheus Dockerfile의 `src` 단계를 빌드해 이미지 ID를 얻는다.

```sh
TOOLS=$(docker build -q --target src -f infrastructure/prometheus/Dockerfile infrastructure)
docker run --rm --user 0 -v "$PWD/infrastructure:/src:ro" --entrypoint /bin/sh "$TOOLS" /src/tests/monitoring-server.test.sh /src/monitoring-server.sh
```

시험 코드에는 2026-11-25 조사에서 찾은 표기를 그대로 쓰고, 시험 이름과 주석은 표기를 들지 않고 일반적으로 쓴다(사람 확인: 2026-11-25). 결함을 잡으려면 실제 표기가 필요하기 때문이다. 결함을 잡는지는 고친 사본으로 본다. `infrastructure/`를 OS 임시 폴더에 복사하고, 사본의 `monitoring-server.sh`에서 해당 부분을 지운 뒤 위 명령의 `/src`를 그 사본으로 바꿔 돌린다. 지운 부분을 맡는 시험이 실패해야 한다. 사본은 확인 뒤 지운다.

완료 기준마다 확인하는 방법은 다음과 같다. C1~C5는 위 명령으로 돌리는 운영 명령 시험이다.

- **C1**: 읽지 않는 표기의 token 폴더 줄로 `start`, `render`, `install`을 실행한다. 표기는 2026-11-25 조사에서 찾은 것마다 하나씩 쓰고, 기본 위치와 적은 폴더의 token 유무 네 조합과 셸 환경 변수가 있는 경우를 넣는다. 종료 코드가 0이 아니고 `compose` 호출이 없으며, 펼친 설정과 token 파일과 `.env`가 그대로여야 한다. 출력에는 변수 이름, 줄 번호, 고칠 형식이 있고 시험용 token 값이 없어야 한다. 주석 줄과 이름이 더 긴 변수의 줄로는 멈추지 않아야 한다. 멈춤을 지운 사본에서는 멈추는 시험이 실패해야 한다.
- **C2**: 읽지 않는 표기의 CA 경로 줄로 `start`와 `render`를 실행한다. 적은 경로의 파일과 복사본의 유무를 조합한다. 0027의 기존 시험은 기대를 멈춤으로 바꾼다. 종료 코드가 0이 아니고 `compose` 호출이 없으며, 펼친 설정이 그대로이고 출력에 변수 이름과 고칠 형식이 있어야 한다. 멈춤을 지운 사본에서는 실패해야 한다.
- **C3**: 가짜 `docker`가 받은 `MONITORING_SECRETS_DIR`를 네 경우에서 본다. `.env`에 `이름=폴더`로 적은 경우, 셸 환경 변수, 적지 않은 경우, 운영 명령과 Compose가 다르게 읽는 표기다. 마지막 경우에는 운영 명령이 읽은 글자 그대로의 폴더에 token을 둔다. 받은 값은 운영 명령이 검사한 값과 같아야 하고, 적지 않으면 unset이 아니라 빈 값이어야 한다. export를 지운 사본에서는 실패해야 한다.
- **C4**: 운영 명령과 Compose가 다르게 읽는 표기로 CA 경로를 적고 `start`한다. 0027의 형식 검사에서 멈추고 `compose` 호출이 없어야 한다. 형식에 맞는 값이면 가짜 `docker`가 받은 `MONITORING_CA_FILE`이 검사한 값이어야 한다. 값 넘기기를 지운 사본에서는 실패해야 한다.
- **C5**: 운영 명령과 Compose가 다르게 읽는 표기로 token 폴더를 적고, Compose가 읽는 위치에만 token을 둔다. `start`와 `render`가 종료 코드 0이 아니고 `compose` 호출이 없어야 한다. 기존 token 검사 시험(없음, 폴더, 빈 파일, 읽을 수 없음)도 그대로 통과해야 한다.
- **C6**: docker:dind로 띄운 Linux Docker 호스트에 묶음을 운영 가이드대로 설치하고 수동으로 확인한다. C3, C4의 경우마다 `start`한 뒤 `docker inspect`의 `Mounts`에서 Alertmanager의 token 파일과 Blackbox Exporter의 CA 인증서 파일의 원본 경로를 본다. 원본 경로가 운영 명령이 검사한 파일이어야 한다. C1, C2의 표기로는 `start`가 멈추고 컨테이너가 바뀌지 않아야 한다. token 파일 내용은 열지 않고, dind 컨테이너는 확인 뒤 지운다.
- **C7**: Windows에서 수동으로 확인한다. 저장소의 `infrastructure/` 아래에 시험용 폴더와 시험용 token을 만들고, 셸 환경 변수 `MONITORING_SECRETS_DIR`에 그 상대 경로를 준 뒤 `monitoring-dev.bat start`를 실행한다. 고치기 전에는 시험용 폴더에 token이 없고 `.env`의 폴더에만 있어도 render가 통과하는 것을 본다(결함 재현). 고친 뒤에는 Alertmanager `Mounts`의 원본이 시험용 폴더의 token 파일이어야 하고, 시험용 폴더에 token이 없으면 render가 멈추고 Compose를 부르지 않아야 한다. 확인 뒤 `monitoring-dev.bat stop`을 실행하고 시험용 폴더를 지운다.
- **C8**: OPERATIONS.md, ARCHITECTURE.md, `.env.example`이 바뀐 동작과 맞는지 문서 리뷰로 본다.
- **C9**: `node scripts/build-monitoring-bundle.mjs --check`(Windows는 `monitoring-dev.bat check`)와 `node scripts/check-docs.mjs`가 통과해야 한다.

## 작업 순서

- [x] C7의 고치기 전 확인: 고치기 전의 `monitoring-dev.bat start`로 결함을 재현하고 결과를 진행 중 발견에 적는다. 띄운 컨테이너는 멈추고 시험용 폴더는 지운다.
- [x] 시험 먼저: 가짜 `docker`에 `MONITORING_SECRETS_DIR` 기록을 더하고 C1~C5의 시험을 쓴다. 0027 시험의 기대를 바꾼다. 위 명령으로 돌려 새 시험과 바꾼 시험이 실패하는지 본다(커밋: `test:`).
- [x] 읽지 않는 줄을 찾는 함수와 안내를 더하고, `check_ca_setting`과 token 폴더 검사, `cmd_install`에서 부른다. C1, C2 시험이 통과하는지 본다.
- [x] token 폴더 값을 export한다. C3, C5 시험이 통과하고 C4가 그대로 통과하는지 본다.
- [x] 고친 사본으로 결함을 잡는지 본다(멈춤을 지운 사본, export를 지운 사본, CA 값 넘기기를 지운 사본).
- [x] `monitoring-dev.bat`에 `-e MONITORING_SECRETS_DIR`를 더하고 C7의 고친 뒤 확인을 한다.
- [x] 코드 주석, `.env.example`, OPERATIONS.md, ARCHITECTURE.md를 고치고 `node scripts/check-docs.mjs`를 돌린다(커밋: `docs:` 또는 구현 커밋에 함께).
- [x] C6 수동 확인을 하고 결과를 진행 중 발견에 적는다.
- [ ] 전체 검증(C9)을 돌린다.
- [ ] 구현하지 않은 세션이나 하위 에이전트에 리뷰를 맡긴다. 요구사항의 완료 기준, 이 계획, diff, 검증한 커밋 SHA와 명령·결과, 위 보안 위협의 CWE를 준다.
- [ ] 리뷰를 반영한 뒤 계획 템플릿의 상태 값 설명대로 남길 것을 옮기고, 요구사항을 구현됨으로 바꾸고, 이 계획을 지운다(병합 전 마지막 커밋).

## 진행 중 발견

- **시험과 구현을 한 커밋에 담음**: 작업 순서에는 시험을 `test:` 커밋으로 먼저 남긴다고 적었지만, [DEVELOPMENT.md의 커밋](../DEVELOPMENT.md#커밋)은 커밋 전에 바꾼 부분의 시험이 통과해야 한다고 정한다. 그래서 시험을 먼저 쓰고 실패를 본 뒤(통과 260, 실패 68) 구현과 함께 `edd924c`에 커밋했다. 구현 뒤에는 통과 328, 실패 0이다.
- **결함을 잡는지 본 사본**: `infrastructure/`에서 시험에 필요한 파일만 OS 임시 폴더에 복사하고, 사본의 `monitoring-server.sh`를 한 곳씩 고쳐 시험을 돌렸다. token 폴더의 멈춤을 지운 사본은 38개, token 폴더의 export를 지운 사본은 4개, CA 경로의 멈춤을 지운 사본은 26개, CA 경로의 export를 지운 사본은 2개, 이름 경계 검사를 지운 사본은 1개, 파일 첫머리의 보이지 않는 글자 처리를 지운 사본은 20개, `install`의 멈춤을 지운 사본은 12개 시험이 실패했다. 모두 고친 부분을 맡는 시험이다.
- **C7(2026-11-25, Windows, Docker Desktop 29.9.3)**: 고치기 전의 `monitoring-dev.bat start`에 셸 환경 변수로 token이 없는 폴더를 주자 render가 통과했다. 이어서 Compose가 `secret file ... does not exist` 경고만 내고 그 폴더의 token 경로를 Alertmanager에 붙여 띄웠고, Docker가 그 자리에 빈 폴더를 만들었다. 고친 뒤에는 그 폴더를 render가 검사해 멈췄고(`token 자리에 폴더가 있습니다`, 폴더를 지운 뒤에는 `token 파일이 없습니다`) Compose를 부르지 않았다. 그 폴더에 시험용 token을 두면 Alertmanager의 token 원본이 그 폴더의 파일이었다. 개발용 시스템은 확인 뒤 내렸고 시험용 폴더는 지웠다.
- **C6(2026-11-25, `docker:29-dind`, `--privileged --memory 1536m --cpus 2`, 안쪽 Docker 29.10.1)**: `ddae432`로 만든 묶음을 운영 가이드대로 설치했다. 컨테이너를 지운 뒤 `start`하면 Alertmanager의 token 원본이 경우마다 운영 명령이 검사한 파일이었다. `.env`에 적지 않으면 설치 폴더의 `secrets/`, `.env`에 `이름=폴더`로 적으면 그 폴더, 셸 환경 변수로 주면 그 폴더였다. 운영 명령과 Compose가 다르게 읽는 표기로 적은 경우에는 운영 명령이 읽은 글자 그대로의 폴더였고, 같은 `.env`로 `docker compose config`를 실행하면 Compose는 다른 폴더로 풀었다. Blackbox Exporter의 CA 원본은 `.env`에 적은 OS의 파일이었다. CA 경로를 다르게 읽는 표기와, 두 값을 읽지 않는 표기로 적은 경우에는 `start`가 멈추고 컨테이너가 바뀌지 않았다. 확인 뒤 dind 컨테이너와 볼륨, 받은 이미지, 만든 묶음을 지웠다.
- **C6에서 찾은 범위 밖 문제**: 모니터링 시스템이 떠 있는 채로 token 폴더 값만 바꾸고 `start`하면, 운영 명령은 새 폴더의 token을 검사하고 성공으로 끝나지만 Alertmanager를 다시 만들지 않아 예전 token 파일을 계속 붙인다. Compose가 서비스를 다시 만들지 판단할 때 token 파일 경로를 보지 않고, 운영 명령도 펼친 설정과 CA 인증서 파일이 바뀔 때만 다시 만들게 하기 때문이다(`write_rendered`의 `render_changed`). 이 변경 전부터 있던 동작이고, 이번 변경은 이 동작을 바꾸지 않았다. 처리 방법은 사람에게 묻는다.

## 남길 결정

없음. Compose가 `.env`에서 직접 읽는 값은 운영 명령이 검사한 값을 넘기고, 운영 명령이 읽지 않는 형식이면 멈춘다는 규칙은 ARCHITECTURE.md의 지켜야 할 경계에만 남긴다(사람 확인: 2026-11-25). 되돌려도 운영자의 `.env`나 다른 모듈을 고칠 필요가 없어서 ADR로 남기지 않는다.
