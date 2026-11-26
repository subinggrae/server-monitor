# 0028. 경로 설정 값을 운영 명령과 Compose가 같게 읽기 구현 계획

- 요구사항: [0028. 경로 설정 값을 운영 명령과 Compose가 같게 읽기](0028-secrets-dir-env-read.md)
- 상태: 진행 중(사람 승인: 2026-11-25, 넓힌 범위의 재승인: 2026-11-26)

<!-- 상태 값: 검토 중 → 승인 → 진행 중. 작업 순서를 모두 끝내고 검증과 리뷰를 마치면, 남길 결정은 ADR로, 바뀐 동작은 요구사항이나 ARCHITECTURE.md로, 진행 중 발견 가운데 다음 작업에도 맞는 사실은 그 사실을 맡는 문서로 옮긴 뒤 이 파일을 지운다. 끝난 계획을 남기면 지금 동작으로 잘못 읽힌다. 지운 계획은 PR에서 다시 볼 수 있으므로 ADR의 관련 줄에 그 링크를 쓴다. 리뷰어가 이 계획을 기준으로 diff를 보므로 리뷰가 끝나기 전에는 지우지 않는다. 토픽 브랜치에만 두고 병합 전 마지막 커밋에서 지운다. -->

## 접근 방법

운영 명령(`infrastructure/monitoring-server.sh`)에 두 가지를 더한다. 운영 명령이 읽지 않는 `.env` 줄을 알아보고 멈추는 검사를 더하고, 검사한 token 폴더 값을 Compose에 넘긴다. CA 인증서 파일 경로 값은 0027부터 검사한 값을 넘기므로 멈추는 검사만 더한다. `monitoring-dev.bat`는 render 컨테이너에 넘기는 셸 환경 변수에 token 폴더를 더한다.

2026-11-25에 넓힌 범위(요구사항의 S10~S14)를 위해 두 가지를 더 한다. 펼친 설정의 바뀜 판단에 token 파일의 실제 경로를 넣어, token 폴더만 바꾼 `start`도 컨테이너를 다시 만들게 한다. 그리고 저장소에서 `render`하면 검사한 값을 Compose용 값 파일로 남기고, `monitoring-dev.bat`가 Compose에 그 파일을 주어 `.env`를 다시 읽지 않게 한다.

### 읽지 않는 줄을 알아보는 규칙

운영 명령은 `.env`를 셸로 실행하지 않고 `env_value`로 읽는다. `env_value`가 읽는 줄은 줄 앞 공백과 `export`를 건너뛰면 변수 이름이 오고, 그 바로 뒤에(공백은 건너뜀) `=`가 오는 줄뿐이다. 새 검사는 같은 파일에서 다음 조건에 모두 맞는 줄을 찾는다.

1. 주석 줄이 아니다. 주석 줄은 줄 앞 공백(스페이스, 탭, 세로 탭, 폼 피드) 뒤 첫 글자가 `#`인 줄이다. 이렇게 건너뛰는 줄은 Compose도 주석으로 읽는다(2026-11-25 Compose v5.7.0의 `docker compose config`로 확인).
2. 변수 이름이 낱말로 들어 있다. 이름 앞뒤의 글자가 이름에 쓰는 글자(영문자, 숫자, `_`)가 아니거나 줄의 끝이다. 그래서 이름이 더 긴 다른 변수의 줄은 고르지 않는다.
3. `env_value`가 읽는 형식이 아니거나, 그 형식이어도 `=` 뒤의 값에 변수 이름이 다시 나온다.

이런 줄이 하나라도 있으면 그 변수는 운영 명령과 Compose가 다르게 읽을 수 있다고 보고 멈춘다. 안내에는 변수 이름, 줄 번호, 고칠 형식(`MONITORING_SECRETS_DIR=폴더`, `MONITORING_CA_FILE=경로`)과, 형식이 맞아 보이면 보이지 않는 글자가 있을 수 있으니 그 줄을 지우고 다시 입력하라는 말만 보인다. 줄 내용은 보이지 않는다. 읽는 형식의 줄과 다른 줄이 함께 있어도 멈춘다. 셸 환경 변수가 있어 `start`는 그 값을 쓰는 경우에도 멈춘다(사람 확인: 2026-11-25). 셸 환경 변수 없이 운영자가 직접 실행하는 `docker compose` 명령은 그 줄을 읽기 때문이다.

이 규칙은 Compose가 받아들이는 표기를 하나씩 나열하지 않고, 변수 이름이 든 줄 가운데 운영 명령이 읽는 형식 밖을 모두 고른다. 그래서 다른 변수의 값이나 줄 끝 주석에 이름이 들어 있어도 멈춘다. 드물고, 멈추는 쪽이 안전하므로 받아들인다(맡긴 쪽의 결정, 2026-11-25). 이렇게 멈출 때 안내가 "그 줄을 `이름=값` 형식으로 고치라"고만 하면 운영자가 다른 변수의 줄을 고쳐 쓸 수 있다. 그래서 안내에 "그 줄이 이 값을 적은 줄이 아니면 그 값이나 주석에서 이름을 빼라"는 줄을 더한다(두 번째 리뷰의 꼭 고침).

이 규칙이 고르지 못하는 줄은 두 방향으로 있다. 다음 두 경우 모두 `start`와 개발용 띄우기는 운영 명령이 읽은 값을 Compose에 넘기므로 검사한 파일로 뜬다. 운영자가 직접 실행하는 `docker compose`에서 남는 어긋남은 요구사항의 하지 않는 것에 있다.

- **운영 명령은 읽지만 Compose는 다른 값의 일부로 읽는 줄**: 다른 변수의 여러 줄 값 안에 `이름=값` 줄이 든 경우다. 형식이 맞아 이 규칙이 고르지 않는다.
- **운영 명령은 건너뛰지만 Compose는 그 변수로 읽는 줄**: 두 번째 독립 리뷰에서 찾았다. 다른 변수의 여러 줄 따옴표 값을 거치는 드문 표기다. 운영 명령은 이 줄을 주석으로 보고 건너뛰지만, Compose는 그 변수의 값으로 읽는다. 이 한계는 받아들인다(사람 확인: 2026-11-25). 실수로 적기 어렵고, 다른 프로그램의 읽기를 흉내 내는 검사는 고칠수록 비켜 가는 표기가 다시 나오기 때문이다. 같은 문제를 두 번 고쳤으므로 규칙을 더 넓히지 않는다. 공개 저장소라 표기는 문서와 커밋 메시지에 적지 않는다.

두 함수(`env_value`, 새 검사)는 `LC_ALL=C`로 실행해 바이트로 비교한다. 그러면 BusyBox, GNU, 로캘과 관계없이 같은 줄을 같게 본다. 새 검사의 `grep`에는 `-a`를 주어, NUL이나 잘못된 UTF-8이 든 파일에서도 GNU `grep`이 줄을 숨기지 않게 한다.

### 검사를 두는 곳

두 값의 검사는 `check_settings`에 둔다. 다른 설정 값처럼 틀린 값을 모두 한 번에 보인 뒤 `settings_ok=no`로 멈추게 하기 위해서다. `render`와 `start`는 `check_render` → `check_settings`로 이 검사를 지난다. CA 인증서 파일 경로 값은 `check_ca_setting` 안에서 값을 읽기 전에 검사한다. token 폴더 값은 새 함수가 검사한 뒤 값을 읽어 export한다. 값이 없다고 보면 빈 값을 export한다(`check_ca_setting`과 같은 방식). 그 뒤의 `token_file`은 `env_value`가 셸 환경 변수를 먼저 보므로 export한 값을 그대로 쓴다.

`install`은 `check_settings`를 부르지 않는다. 그래서 `cmd_install`이 `token_file`을 부르기 전에 token 폴더 값의 검사만 따로 부르고, 걸리면 바로 멈춘다. `install`은 CA 인증서 파일 경로 값을 다루지 않으므로 그 검사는 부르지 않는다.

### 떠 있는 채로 token 폴더를 바꾼 start

원인은 다음과 같다(진행 중 발견의 2026-11-26 확인). `cmd_start`는 `write_rendered`가 정한 `render_changed`가 `yes`일 때만 `compose_up --force-recreate`를 부른다. `render_changed`는 펼친 설정 파일 세 개와 CA 인증서 파일 내용의 해시(`config_hash`)를 지난번 값(`previous_hash`)과 비교해 정하고, token 파일의 위치는 보지 않는다. `--force-recreate` 없이 부른 `docker compose up -d`는 서비스 해시가 바뀐 컨테이너만 다시 만든다. token 파일은 compose 파일의 맨 위 `secrets` 항목(`file:`)에 있고 Alertmanager는 그 이름만 가리키므로, token 폴더 값이 바뀌어도 Alertmanager의 서비스 해시는 그대로다. CA 인증서 파일은 Blackbox Exporter의 `volumes`에 원본 경로로 들어 있어 경로가 바뀌면 해시가 바뀐다.

그래서 `write_rendered`가 token 파일의 실제 경로를 바뀜 판단에 넣는다. CA 인증서 파일 내용의 해시(`MONITORING_CA_SHA256`)와 같은 방식이다.

1. `check_token`이 검사한 token 파일(`token_file`)의 실제 경로를 `readlink -f`로 구한다. 실패하면 `token_file`의 결과를 쓴다. Docker는 컨테이너를 만들 때 링크를 따라가 실제 파일을 붙이므로, token 폴더나 파일이 링크이고 가리키는 곳이 바뀐 경우도 다시 만들어야 하기 때문이다. 같은 폴더를 상대 경로와 절대 경로로 바꿔 적은 경우는 실제 경로가 같아 다시 만들지 않는다(S11).
2. 그 경로 글자의 SHA-256(`token_path_hash`)을 `config_hash`의 입력에 더하고, `monitoring.env`에 `MONITORING_TOKEN_PATH_SHA256=<해시>`로 남긴다. `previous_hash`는 지금 `monitoring.env`에서 이 값을 읽어 같은 방식으로 계산한다. 값이 없으면 빈 값으로 계산한다.
3. 경로 글자는 `monitoring.env`에 쓰지 않는다. `monitoring.env`는 compose 파일의 `env_file`이라 Compose가 값 안의 `${…}`를 풀어 쓰고(2026-11-26 확인), token 폴더 값은 글자를 제한하지 않아 줄바꿈으로 다른 값을 만들 수도 있기 때문이다. 해시는 16진수 글자만 있다. token 파일 내용이나 그 해시는 쓰지 않는다.

`MONITORING_CONFIG_SHA256`이 바뀌면 세 컨테이너의 환경 변수가 바뀌므로, `start`의 `--force-recreate`뿐 아니라 `render` 뒤 운영자가 직접 실행하는 `docker compose up -d`도 세 컨테이너를 다시 만든다(S10). Alertmanager만 다시 만들지 않고 세 컨테이너를 모두 다시 만드는 것은 CA 인증서 파일이 바뀔 때와 같다.

운영자가 겪는 변화는 다음과 같다(S12).

- **운영 서버의 갱신**: 새 버전의 묶음 폴더에는 `rendered/`가 없으므로 첫 `start`는 지금도 `render_changed=yes`로 세 컨테이너를 새 이미지로 다시 만든다. 이 변경으로 더 멈추는 일은 없다. 같은 버전 폴더에서 설정을 바꾸지 않고 다시 `start`하면 다시 만들지 않는다.
- **되돌리기**: 직전 버전의 묶음 폴더에는 그 버전이 펼친 `rendered/`가 있고, 그 버전의 운영 명령은 이 값을 모른다. 그 버전의 판단은 바뀌지 않는다.
- **저장소(개발용)**: `infrastructure/rendered/`가 이 변경 전 운영 명령이 펼친 것이면 `MONITORING_TOKEN_PATH_SHA256`이 없어 첫 `render`에서 `MONITORING_CONFIG_SHA256`이 바뀐다. 그래서 첫 `monitoring-dev.bat start`에서 Compose가 세 컨테이너를 한 번 다시 만든다.

### 개발용 띄우기가 검사한 값을 Compose에 넘기기

원인은 다음과 같다(진행 중 발견의 2026-11-26 확인). 운영 서버의 `start`는 `check_settings`가 검사한 값을 export한 셸에서 `docker compose up`을 부르므로 Compose가 `.env`를 다시 읽지 않는다. `monitoring-dev.bat start`는 `render`를 Linux 컨테이너 안에서 실행하고 그 컨테이너는 끝나면 사라지므로, export한 값이 Windows로 돌아오지 않는다. 그 뒤 Windows에서 부르는 `docker compose -f infrastructure\compose.yml up -d --build`는 `infrastructure/.env`를 스스로 읽어 compose 파일의 `${MONITORING_SECRETS_DIR…}`, `${MONITORING_CA_FILE…}`, 호스트 포트를 푼다. 운영 명령이 읽지 않는 줄은 `render`가 멈추지만(S8, S9), 운영 명령이 읽는 형식이면서 Compose가 다른 값으로 읽는 줄은 그대로 지나간다. CA 경로 값은 0027의 형식 검사(`monitoring_valid_ca_file`)가 `$`, 따옴표, 백슬래시가 든 값을 막는다. 하지만 줄 끝 주석을 다루는 방식의 차이와 다른 변수의 여러 줄 값을 거치는 표기는 형식 검사를 통과하고도 두 쪽이 다르게 읽었다.

그래서 다음과 같이 한다.

1. 저장소 배치(`LAYOUT=repository`)에서 `write_rendered`가 `rendered/compose.env`를 함께 쓴다. 다른 펼친 파일처럼 임시 폴더(stage)에 쓴 뒤 이름을 바꿔 옮기고 권한은 0644다. 검사를 통과해 export한 값을 Compose의 `.env` 형식으로 적는다. 두 경로 값(`MONITORING_SECRETS_DIR`, `MONITORING_CA_FILE`)과 호스트 포트 세 개를 적는다(사람 확인: 2026-11-26). 값은 작은따옴표로 감싼다. Compose는 작은따옴표 값을 다시 풀지 않는다(`${…}`, `$$`, `#`, 공백, 큰따옴표를 글자 그대로 읽음, 2026-11-26 확인). 값이 비었으면 `''`로 적고, Compose는 compose 파일의 `:-` 기본값을 쓴다.
2. 작은따옴표 안에서도 Compose는 `\\`와 `\'`를 푼다(2026-11-26 확인). 그래서 export한 token 폴더 값에 작은따옴표, 백슬래시, 줄바꿈이 있으면 저장소 배치의 `check_settings`가 멈추게 한다(S14). 안내에는 변수 이름과 `infrastructure/` 기준 상대 경로로 적으라는 방법만 쓰고 값은 쓰지 않는다. CA 경로 값은 형식 검사가 이 글자를 이미 막고, 포트는 정수만 통과한다. 묶음 배치에서는 이 파일을 쓰지 않고 이 검사도 하지 않는다.
3. `monitoring-dev.bat start`는 Compose를 `docker compose --env-file infrastructure\rendered\compose.env -f infrastructure\compose.yml up -d --build`로 부른다. `--env-file`을 주면 Compose는 `infrastructure/.env`를 읽지 않는다. 셸 환경 변수는 지금처럼 `--env-file`보다 먼저 쓰이고, 같은 셸 환경 변수를 render 컨테이너에도 넘기므로 두 쪽이 같다.
4. `monitoring-dev.bat stop`은 바꾸지 않는다. `down`은 붙일 파일을 고르지 않는다.
5. 다른 OS에서 저장소로 띄우는 명령도 같은 값 파일을 쓴다(요구사항 S15, 사람 확인: 2026-11-26). 운영 가이드의 저장소에서 실행, DEVELOPMENT.md의 명령 표, `infrastructure/compose.yml`의 머리 주석에 있는 `docker compose -f infrastructure/compose.yml up -d --build`에 `--env-file infrastructure/rendered/compose.env`를 더한다. 저장소 배치의 `render`가 끝날 때 보이는 안내도 이 명령을 보인다. `ps`, `down`은 붙일 파일과 포트를 고르지 않으므로 그대로 둔다.

`render`는 묶음 검사(`build-monitoring-bundle.mjs`의 `renderConfigs`)에서도 저장소 배치로 돈다. 그 검사가 만든 `compose.env`로 실제 Compose(`docker compose --env-file … config`)가 운영 명령이 검사한 token 파일 경로와 CA 원본을 푸는지 보는 검사를 더한다(C13). Compose가 작은따옴표 값을 읽는 방식이 바뀌면 이 검사가 알린다.

### 고르지 않은 방법

- **`env_value`가 Compose의 표기를 모두 읽게 맞추기**: Compose의 `.env` 읽기 규칙을 POSIX sh와 BusyBox로 따라 써야 하고, Compose가 바뀌면 다시 어긋난다. 그리고 지금 운영 명령이 읽지 않는 표기로 다른 폴더를 적은 서버에서는 검사하는 token이 바뀌어도 운영자가 모른다.
- **검사한 값을 넘기기만 하기(0027의 방식)**: 요구사항 배경대로 token이 조용히 바뀌고, 운영자가 직접 실행하는 `docker compose`와 `start`가 서로 다른 파일을 붙인다. 사람이 멈추기로 정했다(2026-11-25).
- **Compose에게 푼 값을 물어보기**(`docker compose config`의 결과에서 token 파일 경로를 읽기): Compose와 똑같이 읽지만, Docker가 없는 곳(`monitoring-dev.bat start`의 render 컨테이너, 빌드 PC의 검사)에서 `render`를 할 수 없다. 출력 형식에도 기대야 하고, 읽지 않는 표기를 쓴 운영자에게 고치라고 알리지 못한다.
- **알려진 표기 목록만 골라내기**: 목록에 없는 표기를 놓친다. 위 규칙은 운영 명령이 읽는 형식 밖을 모두 고른다.
- **두 번째 리뷰가 찾은 표기까지 줄 고르기 규칙을 더 넓히기**: 여러 줄 따옴표 값의 시작과 끝을 따라가야 하므로 사실상 Compose의 읽기를 다시 쓰는 일이고, 같은 문제를 두 번 고친 뒤에도 비켜 가는 표기가 나왔다. 사람이 한계로 받아들였다(2026-11-25).

떠 있는 채로 token 폴더를 바꾼 `start`에서 고르지 않은 방법은 다음과 같다.

- **compose 파일의 Alertmanager에 token 폴더 값을 label이나 환경 변수로 더해 Compose의 서비스 해시를 바꾸기**: Compose만으로 다시 만들지만, 위험 영역인 compose 파일과 묶음 검사를 바꿔야 한다. 값의 글자로 비교하므로 링크가 가리키는 곳이 바뀐 경우는 놓치고, 같은 폴더를 다른 형태로 적으면 쓸데없이 다시 만든다.
- **떠 있는 Alertmanager의 `docker inspect` `Mounts`를 검사한 경로와 비교하기**: 실제 상태를 보지만, `render` 뒤 직접 실행하는 `docker compose up -d`는 고치지 못한다. Docker 출력 형식과 경로 정규화에 기대고, 시험의 가짜 `docker`도 그 형식을 흉내 내야 한다.
- **`start`마다 Alertmanager를 강제로 다시 만들기**: 바뀐 것이 없어도 매번 멈춘다. S11과 맞지 않는다.
- **token 파일 내용의 해시도 바뀜 판단에 넣기**: token을 바꿨을 때도 다시 만들지만, token에서 만든 값이 세 컨테이너의 환경 변수에 남는다. token 바꾸기는 운영 가이드의 절차가 맡는다(요구사항의 하지 않는 것).
- **Alertmanager만 다시 만들기**: `MONITORING_CONFIG_SHA256`을 세 컨테이너가 함께 쓰므로 판단을 나눠야 한다. token 폴더를 바꾸는 일은 드물고, CA 인증서 파일이 바뀔 때도 세 컨테이너를 다시 만든다.

개발용 띄우기에서 고르지 않은 방법은 다음과 같다.

- **render 컨테이너가 값을 출력하고 `monitoring-dev.bat`가 `for /f`로 받아 환경 변수로 두기**: token 폴더 값은 글자를 제한하지 않아, `cmd`가 `%`, `!`, `^`, `&` 같은 글자를 다시 해석할 수 있다(CWE-78). 값 파일은 Compose가 읽으므로 `cmd`가 값을 해석하지 않는다.
- **작은따옴표와 백슬래시를 이스케이프해 값 파일에 적기**: 2026-11-26 확인으로는 `\\`, `\'`가 풀려 가능하지만, Compose 문서가 정한 동작인지 확인하지 못했다. 개발용 token 폴더는 `infrastructure/` 기준 상대 경로라 이 글자를 쓸 일이 거의 없어, 멈추는 쪽이 단순하다.
- **Compose도 Linux 컨테이너 안에서 실행하기**: 운영 서버의 `start`와 같아지지만 Docker CLI가 든 이미지를 새로 들여야 하고(런타임 의존성), 컨테이너 안 경로와 Docker Desktop이 붙이는 호스트 경로가 달라 볼륨 원본이 어긋난다.
- **`monitoring-dev.bat`가 `docker compose config`의 결과를 render가 검사한 경로와 비교하기**: Windows 경로와 컨테이너 안 경로를 맞춰야 하고, `start`에 Node가 없어도 되는 지금 조건에서 JSON을 `cmd`로 읽어야 한다.
- **값 파일을 묶음 배치에서도 쓰고 운영 가이드의 직접 실행 명령에 `--env-file`을 붙이기**: 운영자가 직접 실행하는 `docker compose`까지 맞추지만, 요구사항의 하지 않는 것(Compose가 `.env`를 읽는 방식)을 넘는다.

## 바뀌는 부분

바뀌는 파일은 다음과 같다.

- **`infrastructure/monitoring-server.sh`**: 읽지 않는 줄을 찾는 함수와 안내를 더한다. `check_ca_setting`이 값을 읽기 전에 이 검사를 한다. token 폴더 값을 검사하고 export하는 함수를 더해 `check_settings`와 `cmd_install`에서 부른다. 머리 주석과 `check_ca_setting` 주석을 고친다.
- **`infrastructure/tests/monitoring-server.test.sh`**: 가짜 `docker`가 `compose up` 때 받은 `MONITORING_SECRETS_DIR` 값을 남긴다(`compose_ca_file`과 같은 방식). 테스트 계획의 시험을 더하고, 0027에서 읽지 않는 표기로 복사본을 붙이던 시험의 기대를 멈춤으로 바꾼다.
- **`monitoring-dev.bat`**: `start`의 render `docker run`에 `-e MONITORING_SECRETS_DIR`를 더한다. 셸 환경 변수를 넘긴다는 주석은 그대로 맞는다.
- **`infrastructure/.env.example`**: 머리 주석에 값은 `이름=값` 형식으로 한 줄에 적는다는 문장을 더한다.
- **`docs/OPERATIONS.md`**: 설치의 설정 값 적기에서 값 적는 법에 `변수=값` 형식과, 두 경로 값을 다른 형식으로 적으면 운영 명령이 멈춘다는 것을 더한다.
- **`docs/ARCHITECTURE.md`**: 지켜야 할 경계의 설정 값 항목과 신뢰 경계에, 운영 명령이 두 경로 값도 검사한 값을 Compose에 넘기고 읽지 않는 형식의 줄이 있으면 멈춘다는 것을 더한다. Compose가 `.env`에서 직접 읽는 값을 새로 더하면 같은 방식을 따른다는 규칙도 적는다.

2026-11-25에 넓힌 범위로 다음이 더 바뀐다.

- **`infrastructure/monitoring-server.sh`**:
  - `write_rendered`: token 파일의 실제 경로 해시를 `config_hash`와 `previous_hash`의 입력에 더하고 `monitoring.env`에 `MONITORING_TOKEN_PATH_SHA256`으로 남긴다. 저장소 배치에서는 `rendered/compose.env`를 함께 쓴다. 머리 주석의 `MONITORING_CONFIG_SHA256` 설명과 함수 주석을 고친다.
  - `check_settings`(또는 `check_secrets_setting`): 저장소 배치에서 export할 token 폴더 값에 작은따옴표, 백슬래시, 줄바꿈이 있으면 안내를 보이고 `settings_ok=no`로 둔다.
  - `env_unread_message`: 다른 값이나 줄 끝 주석에 이름이 든 줄일 때 이름을 빼라는 안내 줄을 더한다.
  - `cmd_render`: 저장소 배치의 안내에 `--env-file rendered/compose.env`를 준 `docker compose` 명령을 보인다(S15).
- **`infrastructure/tests/monitoring-server.test.sh`**: 테스트 계획의 C1~C3 보강 시험, C10, C12, C13, C15 시험을 더한다. 떠 있는 상태는 지금처럼 가짜 `docker`의 `running` 파일로 만든다.
- **`monitoring-dev.bat`**: `start`의 Compose 호출에 `--env-file infrastructure\rendered\compose.env`를 더하고, 이 파일을 쓰는 이유를 주석으로 적는다.
- **`scripts/build-monitoring-bundle.mjs`**: `renderConfigs`가 만든 `compose.env`를 실제 Compose(`composeConfig`와 같은 방식)로 읽어 token 파일 경로와 CA 원본이 운영 명령이 검사한 값인지 보는 검사를 더한다. 시험 값에는 Compose가 따옴표 없이 읽으면 다른 값이 되는 글자를 넣는다.
- **`docs/OPERATIONS.md`**: token 폴더 값을 설명하는 곳에, 떠 있는 채로 바꾸고 `start`하면 세 컨테이너를 다시 만들어 잠깐 감시가 멈춘다는 것을 더한다. 저장소에서 실행 절에 `monitoring-dev.bat`가 `.env` 대신 검사한 값으로 Compose를 부른다는 것과 S14의 제한을 더하고, 다른 OS의 `docker compose ... up` 명령에 `--env-file infrastructure/rendered/compose.env`를 더한다.
- **`docs/DEVELOPMENT.md`**: 명령 표의 모니터링 시스템 실행 행에 있는 다른 OS 명령에 같은 `--env-file`을 더한다.
- **`infrastructure/compose.yml`**: 머리 주석의 다른 OS 명령에 같은 `--env-file`을 더한다. 주석만 바꾸고 서비스 정의는 바꾸지 않는다.
- **`docs/ARCHITECTURE.md`**: 설정 값 항목의 0028 문장 뒤에 받아들인 한계를 일반적으로 적는다(아래 "ARCHITECTURE.md에 적을 한계"). 개발용 띄우기도 검사한 값을 Compose에 넘긴다는 것을 같은 문장에 더한다.

### ARCHITECTURE.md에 적을 한계

적는다(사람 확인: 2026-11-25). 이 경계를 바꾸는 다음 작업자가 줄 검사를 더 넓히려 하거나, 운영 명령 없이 Compose를 부르는 경로를 늘릴 때 알아야 하는 한계이기 때문이다. 요구사항과 이 계획은 병합 뒤 상태가 바뀌거나 지워지므로, 지금 동작의 한계는 ARCHITECTURE.md에 남긴다. 적을 곳은 지켜야 할 경계의 설정 값 항목에서 "Compose가 다르게 읽을 수 있어 멈춘다(요구사항 0028)." 문장 바로 뒤다. 내용은 다음 세 가지이고, 표기는 쓰지 않는다.

- 운영 명령의 줄 검사는 Compose의 `.env` 읽기를 모두 따라가지 않아, 다른 변수의 여러 줄 따옴표 값을 거치는 드문 표기는 골라내지 못한다.
- 그래도 `start`와 `monitoring-dev.bat start`는 검사한 값을 Compose에 넘기므로 검사한 파일로 뜬다. 운영 명령 없이 실행한 `docker compose`만 다를 수 있다.
- 검사를 더 넓히지 않고 받아들인 이유(실수로 적기 어려움, 흉내 내는 검사는 비켜 가는 표기가 다시 나옴)

### 데이터 모델 변경

해당 없음

### 계약 변경

해당 없음. HTTP SD와 webhook 계약, compose 파일의 변수와 기본값은 바뀌지 않는다. 운영자가 쓰는 `.env` 형식에서 두 경로 값이 `이름=값` 밖의 표기를 받지 않게 된다. 이 변화는 요구사항의 S8, S9에 있다.

`rendered/monitoring.env`에 `MONITORING_TOKEN_PATH_SHA256`이 더해지고, 저장소 배치의 `rendered/`에 `compose.env`가 생긴다. 둘 다 운영 명령과 Compose 사이의 내부 파일이다. 관리자 서버와 Prometheus HTTP API에는 나가지 않는다. 다만 `monitoring.env`의 값은 세 컨테이너의 환경 변수가 되므로 `docker inspect`에 보인다. 해시만 있고 경로와 token은 없다.

## 위험과 되돌리기

- 읽지 않는 표기로 두 값을 적은 서버는 새 버전의 `start`가 멈춘다: 의도한 동작이다. 떠 있는 모니터링 시스템은 그대로 돌고, 안내대로 줄을 고치면 뜬다. 고칠 수 없으면 `rollback`으로 직전 버전에 돌아간다. 예전 버전의 운영 명령은 이 검사를 하지 않는다.
- 줄 고르기 규칙이 다른 변수의 줄을 고를 수 있다: 다른 변수의 값이나 줄 끝 주석에 이름이 들어 있으면 멈춘다. 드물고 멈추는 쪽이 안전하므로 받아들인다. 이름이 더 긴 변수의 줄과 주석 줄은 고르지 않는지 시험으로 확인한다. 멈추면 안내의 줄 번호로 운영자가 그 줄을 찾을 수 있다.
- token 폴더 값을 export하면 Compose가 쓰는 값이 바뀌는 서버가 생길 수 있다: 운영 명령과 Compose가 다르게 읽는 표기를 쓴 서버만 해당한다. 시험한 표기에서는 지금 `start`가 그 값의 검사에서 멈추므로, 지금 `start`로 도는 서버의 token 파일은 바뀌지 않는다. C3의 시험과 C6의 수동 확인으로 본다.
- 0027 시험 하나의 기대가 성공에서 멈춤으로 바뀐다: 요구사항 C2대로 사람이 승인했다(2026-11-25). 시험을 지우지 않고 기대만 바꾼다.
- token 폴더를 바꾼 `start`가 세 컨테이너를 다시 만들어 잠깐 감시가 멈춘다: 의도한 동작이다(S10). CA 인증서 파일이 바뀔 때와 같고, token 폴더를 바꾸는 일은 드물다. OPERATIONS.md에 적는다.
- 지금 도는 서버가 이 버전으로 갱신한 뒤 뜻하지 않게 다시 만들어질 수 있다: 새 버전의 묶음 폴더에는 `rendered/`가 없어 첫 `start`는 지금도 다시 만든다. 같은 버전 폴더에서 설정을 바꾸지 않고 `start`하면 다시 만들지 않는 것을 C10의 시험(기존 "펼친 설정이 그대로면 강제로 다시 만들지 않는다" 시험 포함)으로 본다. 저장소(개발용)는 갱신 뒤 첫 `monitoring-dev.bat start`에서 한 번 다시 만든다(C12).
- 실제 경로를 구하지 못하는 서버가 있을 수 있다: `readlink -f`는 0027의 CA 검사가 이미 쓰고, 실패하면 `token_file`의 결과를 쓴다. 이때는 링크가 가리키는 곳만 바뀐 경우를 놓치지만 폴더 값을 바꾼 경우는 잡는다.
- 개발자가 지금 쓰는 token 폴더 값이 S14로 멈출 수 있다: 작은따옴표, 백슬래시, 줄바꿈이 든 값만 해당하고 저장소 배치에서만 멈춘다. 안내대로 `infrastructure/` 기준 상대 경로로 고친다. Windows 경로(백슬래시)는 지금도 `render`가 token 파일이 없다고 멈추는 값이다.
- Compose가 작은따옴표 값을 읽는 방식이 바뀔 수 있다: 빌드 검사(C13)가 실제 Compose로 값 파일을 읽어 알린다.
- `rendered/compose.env`에 경로 글자가 남는다: 개발 PC의 `infrastructure/rendered/`에만 생기고 Git에서 제외된다(`.gitignore`의 `infrastructure/rendered/`). 경로는 비밀값이 아니다.
- 되돌리기: 코드와 문서만 바뀌고 데이터 이전이 없다. 커밋을 되돌리면 이전 동작으로 돌아간다. 되돌린 뒤 첫 `start`는 `monitoring.env`의 형식이 달라 한 번 다시 만든다. 예전 버전의 운영 명령은 `MONITORING_TOKEN_PATH_SHA256`과 `compose.env`를 읽지 않는다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 바뀌지 않는다. `.env`와 셸 환경 변수의 두 경로 값이 운영 명령과 Compose로 들어가는 흐름은 그대로다. 다만 `start`에서 Compose가 token 폴더 값을 `.env`에서 다시 읽지 않고 운영 명령이 검사한 값을 받는다.
- 잘못될 수 있는 것과 막는 방법:
  - 운영 명령과 Compose가 같은 `.env`를 다르게 읽어 검사하지 않은 파일을 붙임(CWE-20): 읽지 않는 줄이 있으면 멈추고, `start`는 검사한 값을 넘긴다. C1~C5의 시험으로 확인한다.
  - 검사와 띄우기 사이에 `.env`가 바뀌어 다른 값으로 뜸(CWE-367): `start`가 검사한 값을 넘기므로 Compose가 그 사이의 `.env`를 다시 읽지 않는다. C3의 시험으로 확인한다.
  - 안내나 시험 출력에 token 값이 나옴(CWE-532, CWE-798): 안내에는 변수 이름, 줄 번호, 경로만 쓰고 줄 내용과 token 파일 내용은 쓰지 않는다. C1의 시험이 출력에 시험용 token 값이 없는지 본다.
  - `.env` 내용이 명령으로 실행됨(CWE-78): 새 검사는 `.env`를 셸로 실행하거나 `eval`하지 않는다. 고정된 변수 이름으로만 패턴을 만들고 `sed`, `grep`으로 읽는다. 리뷰에서 확인한다.
  - 검사한 token 파일과 떠 있는 Alertmanager가 붙인 파일이 다름(CWE-367): token 폴더만 바꾼 `start`가 검사는 새 파일로 하고 컨테이너는 예전 파일로 둔다. token 파일의 실제 경로를 바뀜 판단에 넣어 다시 만든다. C10의 시험과 C11의 수동 확인으로 본다.
  - 개발용 띄우기에서 검사한 값과 Compose가 쓰는 값이 다름(CWE-367): Compose가 `.env`를 다시 읽지 않고 `render`가 쓴 값 파일을 읽는다. C13의 시험과 빌드 검사, C14의 수동 확인으로 본다.
  - 값 파일에 쓴 경로 글자가 다른 값을 만들거나 Compose가 값을 다시 풂(CWE-93, CWE-74): token 폴더 값은 글자를 제한하지 않으므로 줄바꿈으로 다른 변수 줄(예: 호스트 포트)을 만들 수 있다. 값을 작은따옴표로 감싸고, 작은따옴표·백슬래시·줄바꿈이 든 값이면 저장소 배치에서 멈춘다. C15의 시험이 이 값마다 멈추고 값 파일을 쓰지 않는지 보고, C13의 빌드 검사가 `$`, `#`, 공백, 큰따옴표가 든 값을 Compose가 글자 그대로 읽는지 본다.
  - `monitoring.env`에 넣은 값을 Compose가 풀거나 줄을 나눔(CWE-74): 경로 글자 대신 16진수 해시만 넣는다. C10의 시험이 `monitoring.env`에 경로 글자가 없는지 본다.
  - token에서 만든 값이 컨테이너 환경 변수에 남음(CWE-200): token 파일 내용과 그 해시는 쓰지 않는다. C10의 시험이 펼친 파일에 시험용 token 값이 없는지 본다.
  - `monitoring-dev.bat`가 경로 값을 `cmd`로 해석함(CWE-78): 값을 `cmd` 변수로 받지 않고 Compose가 값 파일을 읽게 한다. 리뷰에서 확인한다.
- 받아들이는 위험:
  - 운영 서버의 token 폴더 값의 글자와 형식을 제한하지 않는다. 요구사항의 하지 않는 것에 있고, 사람이 요구사항과 함께 승인했다(2026-11-25).
  - 운영자가 직접 실행하는 `docker compose` 명령은 이 규칙이 고르지 못하는 줄을 `start`와 다르게 읽을 수 있다. 요구사항의 하지 않는 것에 있고, 사람이 요구사항과 함께 승인했다(2026-11-25).
  - 다른 변수의 여러 줄 따옴표 값을 거치는 드문 표기는 줄 검사가 골라내지 못한다. 요구사항의 하지 않는 것에 있고, 사람이 받아들였다(2026-11-25).

## 테스트 계획

운영 명령 시험은 기존 `infrastructure/tests/monitoring-server.test.sh`에 더하고, 지금처럼 공식 Prometheus 이미지의 BusyBox `sh`에서 가짜 `docker`로 돌린다. 이 시험만 돌릴 때는 다음 명령을 쓴다. 첫 줄은 `monitoring-dev.bat`처럼 Prometheus Dockerfile의 `src` 단계를 빌드해 이미지 ID를 얻는다.

```sh
TOOLS=$(docker build -q --target src -f infrastructure/prometheus/Dockerfile infrastructure)
docker run --rm --user 0 -v "$PWD/infrastructure:/src:ro" --entrypoint /bin/sh "$TOOLS" /src/tests/monitoring-server.test.sh /src/monitoring-server.sh
```

시험 코드에는 2026-11-25 조사에서 찾은 표기를 그대로 쓰고, 시험 이름과 주석은 표기를 들지 않고 일반적으로 쓴다(사람 확인: 2026-11-25). 결함을 잡으려면 실제 표기가 필요하기 때문이다. 결함을 잡는지는 고친 사본으로 본다. `infrastructure/`를 OS 임시 폴더에 복사하고, 사본의 `monitoring-server.sh`에서 해당 부분을 지운 뒤 위 명령의 `/src`를 그 사본으로 바꿔 돌린다. 지운 부분을 맡는 시험이 실패해야 한다. 사본은 확인 뒤 지운다.

완료 기준마다 확인하는 방법은 다음과 같다. C1~C5는 위 명령으로 돌리는 운영 명령 시험이다.

- **C1**: 읽지 않는 표기의 token 폴더 줄로 `start`, `render`, `install`을 실행한다. 표기는 2026-11-25 조사에서 찾은 것마다 하나씩 쓰고, 기본 위치와 적은 폴더의 token 유무 네 조합과 셸 환경 변수가 있는 경우를 넣는다. 종료 코드가 0이 아니고 `compose` 호출이 없으며, 펼친 설정과 token 파일과 `.env`가 그대로여야 한다. 출력에는 변수 이름, 줄 번호, 고칠 형식이 있고 시험용 token 값이 없어야 한다. 주석 줄과 이름이 더 긴 변수의 줄로는 멈추지 않아야 하고, 다른 변수의 값에 이름이 든 줄로는 멈춰야 한다. 운영 명령이 읽는 형식의 여러 모양(`export`, 줄 앞 공백, `=` 앞뒤 공백, CRLF 줄 끝)으로 두 값을 적으면 멈추지 않고 그 값을 Compose에 넘겨야 한다. 멈춤을 지운 사본과 읽는 형식을 좁힌 사본에서는 해당 시험이 실패해야 한다.
  - 두 번째 리뷰에서 더하는 것: 출력의 줄 번호를 `번째 줄`이 있는지만 보지 않고, 시험이 적은 줄의 번호(`N번째 줄`)와 같은지 본다. 앞 줄에 NUL이 든 `.env`로도 BusyBox에서 줄 번호가 맞는지 본다. 다른 변수의 값과 줄 끝 주석에 이름이 든 줄로 멈출 때 출력에 이름을 빼라는 안내가 있는지 본다. 이 안내 줄을 지운 사본에서 실패해야 한다.
  - `grep -a`: 운영 명령 시험과 묶음 검사는 모두 BusyBox(공식 Prometheus 이미지)에서 돌고, 빌드가 받는 이미지에는 GNU `grep`이 든 것이 없다. 그래서 `-a`를 지운 사본으로도 운영 명령 시험은 실패하지 않을 것으로 본다. 구현 때 이를 사본으로 확인하고 진행 중 발견에 적는다. GNU `grep`이 든 이미지를 새로 들이는 것은 빌드 의존성을 늘리는 일이라 하지 않는다. 대신 NUL이 든 `.env`로 `-a`가 있을 때와 없을 때의 결과를 GNU `grep`(예: Windows의 Git Bash)에서 수동으로 확인해 적는다.
- **C2**: 읽지 않는 표기의 CA 경로 줄로 `start`와 `render`를 실행한다. 적은 경로의 파일과 복사본의 유무를 조합한다. 0027의 기존 시험은 기대를 멈춤으로 바꾼다. 종료 코드가 0이 아니고 `compose` 호출이 없으며, 펼친 설정이 그대로이고 출력에 변수 이름, 시험이 적은 줄의 번호, 고칠 형식이 있어야 한다. 다른 변수의 값에 이름이 든 줄이면 이름을 빼라는 안내도 있어야 한다. 멈춤을 지운 사본에서는 실패해야 한다.
- **C3**: 가짜 `docker`가 받은 `MONITORING_SECRETS_DIR`를 다섯 경우에서 본다. `.env`에 `이름=폴더`로 적은 경우, 셸 환경 변수, 적지 않은 경우, 운영 명령과 Compose가 다르게 읽는 표기, 다른 변수의 여러 줄 따옴표 값 안에 `이름=폴더` 줄이 있는 경우다. 넷째 경우에는 운영 명령이 읽은 글자 그대로의 폴더에, 다섯째 경우에는 그 줄의 폴더에 token을 둔다. 받은 값은 운영 명령이 검사한 값과 같아야 하고, 적지 않으면 unset이 아니라 빈 값이어야 한다. 운영 명령이 읽는 정상 형식(큰따옴표 값, 작은따옴표 값, 공백 뒤 줄 끝 주석이 붙은 값, `이름=`처럼 빈 값)으로 두 경로 값을 적으면 멈추지 않고, 가짜 `docker`가 받은 값이 따옴표와 주석을 뺀 값(빈 값이면 빈 값)이어야 한다. export를 지운 사본에서는 실패해야 한다.
- **C4**: 운영 명령과 Compose가 다르게 읽는 표기로 CA 경로를 적고 `start`한다. 0027의 형식 검사에서 멈추고 `compose` 호출이 없어야 한다. 형식에 맞는 값이면 가짜 `docker`가 받은 `MONITORING_CA_FILE`이 검사한 값이어야 한다. 값 넘기기를 지운 사본에서는 실패해야 한다.
- **C5**: 운영 명령과 Compose가 다르게 읽는 표기로 token 폴더를 적고, Compose가 읽는 위치에만 token을 둔다. `start`와 `render`가 종료 코드 0이 아니고 `compose` 호출이 없어야 한다. 기존 token 검사 시험(없음, 폴더, 빈 파일, 읽을 수 없음)도 그대로 통과해야 한다.
- **C6**: docker:dind로 띄운 Linux Docker 호스트에 묶음을 운영 가이드대로 설치하고 수동으로 확인한다. C3, C4의 경우마다 `start`한 뒤 `docker inspect`의 `Mounts`에서 Alertmanager의 token 파일과 Blackbox Exporter의 CA 인증서 파일의 원본 경로를 본다. 원본 경로가 운영 명령이 검사한 파일이어야 한다. C1, C2의 표기로는 `start`가 멈추고 컨테이너가 바뀌지 않아야 한다. token 파일 내용은 열지 않고, dind 컨테이너는 확인 뒤 지운다.
- **C7**: Windows에서 수동으로 확인한다. 저장소의 `infrastructure/` 아래에 시험용 폴더와 시험용 token을 만들고, 셸 환경 변수 `MONITORING_SECRETS_DIR`에 그 상대 경로를 준 뒤 `monitoring-dev.bat start`를 실행한다. 고치기 전에는 시험용 폴더에 token이 없고 `.env`의 폴더에만 있어도 render가 통과하는 것을 본다(결함 재현). 고친 뒤에는 Alertmanager `Mounts`의 원본이 시험용 폴더의 token 파일이어야 하고, 시험용 폴더에 token이 없으면 render가 멈추고 Compose를 부르지 않아야 한다. 확인 뒤 `monitoring-dev.bat stop`을 실행하고 시험용 폴더를 지운다.
- **C8**: OPERATIONS.md, ARCHITECTURE.md, `.env.example`이 바뀐 동작과 맞는지 문서 리뷰로 본다.
- **C9**: `node scripts/build-monitoring-bundle.mjs --check`(Windows는 `monitoring-dev.bat check`)와 `node scripts/check-docs.mjs`가 통과해야 한다.
- **C10**: 운영 명령 시험이다. 시험은 구현 전에 쓰고, 지금 코드에서 첫 두 경우가 실패하는 것을 먼저 본다.
  - 떠 있는 상태(`running`)에서 기본 위치로 한 번 `start`한 뒤, `.env`의 token 폴더를 token이 있는 다른 폴더로 바꾸고 `start`한다. `compose.* up -d --force-recreate` 호출이 있어야 한다. 셸 환경 변수로 바꾼 경우도 같다.
  - `render`만 두 번(폴더를 바꾸기 전과 뒤) 실행하면 `MONITORING_CONFIG_SHA256`이 달라야 한다.
  - 같은 폴더를 상대 경로(`../secrets`)에서 절대 경로로만 바꾸고 `start`하면 `force-recreate`가 없어야 한다. token 폴더가 링크이고 가리키는 곳만 바꾼 경우에는 있어야 한다.
  - `monitoring.env`에 `MONITORING_TOKEN_PATH_SHA256`이 64자리 16진수로 있고, 시험용 token 값과 token 폴더 경로 글자가 없어야 한다.
  - 결함을 잡는지: 경로 해시를 `config_hash` 입력에서 뺀 사본, 실제 경로 대신 `.env` 값 글자를 쓰는 사본에서 해당 시험이 실패해야 한다.
- **C11**: C6과 같은 방법(`docker:dind`, `--privileged --memory 1536m --cpus 2`)으로 묶음을 설치해 수동으로 확인한다. 고치기 전 묶음(`ef5b185`)으로 띄운 뒤 token 폴더 값만 바꾸고 `start`해, Alertmanager `Mounts`의 token 원본이 예전 파일로 남는 것을 먼저 본다. 고친 묶음에서는 같은 순서로 원본이 새 폴더의 파일로 바뀌어야 하고, 이어서 `start`하면 컨테이너 ID가 그대로여야 한다. token 파일 내용은 열지 않고, 확인 뒤 dind 컨테이너와 볼륨, 받은 이미지, 만든 묶음을 지운다.
- **C12**: 운영 명령 시험이다. `start`로 펼친 뒤 `monitoring.env`에서 `MONITORING_TOKEN_PATH_SHA256` 줄을 지워 이 변경 전 운영 명령이 펼친 상태를 만든다. 이 변경 전 운영 명령의 펼친 결과와 다른 점은 이 줄과 `MONITORING_CONFIG_SHA256` 값뿐이다. 다음 `start`에는 `force-recreate`가 있고, 그다음 `start`에는 없어야 한다.
- **C13**: 운영 명령 시험과 빌드 검사다.
  - 운영 명령 시험: 저장소 배치(시험의 `REPO`)에서 `render`한 뒤 `rendered/compose.env`를 본다. 운영 명령과 Compose가 다르게 읽는 표기, 다른 변수의 여러 줄 따옴표 값 안의 줄, 셸 환경 변수, 적지 않은 경우마다 `MONITORING_SECRETS_DIR`와 `MONITORING_CA_FILE` 줄이 운영 명령이 검사한 값을 작은따옴표로 감싼 것이어야 한다(적지 않으면 `''`). 포트 세 줄도 검사한 값(적지 않으면 기본값)이어야 하고, 머리 주석 밖의 줄은 이 다섯 줄뿐이어야 한다. 묶음 배치의 `render` 뒤에는 이 파일이 없어야 한다. `.env`의 줄을 그대로 옮기는 사본에서 실패해야 한다.
  - 빌드 검사: `renderConfigs`의 `.env`에 Compose가 따옴표 없이 읽으면 다른 값이 되는 글자(변수 치환과 주석에 쓰는 글자, 큰따옴표)가 든 token 폴더를 적고 그 폴더에 검사용 token을 둔다. 만든 `compose.env`를 `docker compose --env-file … config --format json`으로 읽어 `secrets.alertmanager_webhook_token.file`이 그 폴더의 token 파일이고, Blackbox Exporter의 CA 원본이 `certs/ca-certificates.crt`인지 본다. 값을 작은따옴표 없이 쓰는 사본에서 실패해야 한다.
- **C14**: Windows에서 수동으로 확인한다. 저장소의 `infrastructure/` 아래에 시험용 폴더 두 개를 만든다. 하나는 운영 명령이 글자 그대로 읽는 이름이고, 다른 하나는 같은 값을 Compose가 풀어 읽는 이름이다. `.env`의 token 폴더를 그 표기로 적는다. Windows 폴더 이름에 쓸 수 없는 글자(`:` 등)가 들지 않는 표기를 고른다. 고치기 전 `monitoring-dev.bat`(`ef5b185`)로 Compose가 붙이는 폴더가 운영 명령이 검사한 폴더와 다른 것을 먼저 본다. 고친 뒤에는 Alertmanager `Mounts`의 원본이 운영 명령이 읽은 폴더의 파일이고, 그 폴더에서 token을 치우면 `render`가 멈추고 Compose를 부르지 않아야 한다. CA 경로 값을 비우면 Blackbox Exporter의 원본이 `infrastructure/certs/ca-certificates.crt`여야 한다. 확인 뒤 `monitoring-dev.bat stop`을 실행하고 시험용 폴더와 `.env`의 시험 줄을 지운다.
- **C15**: 운영 명령 시험이다. 저장소 배치에서 token 폴더 값에 작은따옴표, 백슬래시가 든 `.env`와, 줄바꿈이 든 셸 환경 변수로 각각 `render`한다. 종료 코드가 0이 아니고, 펼친 설정과 `compose.env`가 그대로이며, 출력에 변수 이름과 고칠 방법이 있고 값은 없어야 한다. 같은 이름의 폴더에 token을 둔 묶음 배치에서는 멈추지 않아야 한다. 이 멈춤을 지운 사본에서 실패해야 한다.
- **C16**: 문서 리뷰로 운영 가이드, DEVELOPMENT.md, compose 파일 머리 주석, `render`의 저장소 안내가 같은 명령인지 본다. 수동 확인은 C11과 같은 `docker:dind`에 저장소의 `infrastructure/`를 복사해 운영 가이드의 다른 OS 명령을 그대로 실행한다(이미지는 `--build` 대신 같은 커밋으로 만든 묶음의 이미지를 불러와 쓴다). `.env`의 token 폴더를 운영 명령과 Compose가 다르게 읽는 표기로 적고 호스트 포트 하나를 바꾼다. 고치기 전 명령(`--env-file` 없음)으로는 Compose가 다른 폴더를 붙이는 것을 먼저 본다. 고친 명령으로는 Alertmanager `Mounts`의 원본이 `render`가 검사한 폴더의 파일이고 `PortBindings`가 바꾼 포트여야 한다.

## 작업 순서

- [x] C7의 고치기 전 확인: 고치기 전의 `monitoring-dev.bat start`로 결함을 재현하고 결과를 진행 중 발견에 적는다. 띄운 컨테이너는 멈추고 시험용 폴더는 지운다.
- [x] 시험 먼저: 가짜 `docker`에 `MONITORING_SECRETS_DIR` 기록을 더하고 C1~C5의 시험을 쓴다. 0027 시험의 기대를 바꾼다. 위 명령으로 돌려 새 시험과 바꾼 시험이 실패하는지 본다(커밋: `test:`).
- [x] 읽지 않는 줄을 찾는 함수와 안내를 더하고, `check_ca_setting`과 token 폴더 검사, `cmd_install`에서 부른다. C1, C2 시험이 통과하는지 본다.
- [x] token 폴더 값을 export한다. C3, C5 시험이 통과하고 C4가 그대로 통과하는지 본다.
- [x] 고친 사본으로 결함을 잡는지 본다(멈춤을 지운 사본, export를 지운 사본, CA 값 넘기기를 지운 사본).
- [x] `monitoring-dev.bat`에 `-e MONITORING_SECRETS_DIR`를 더하고 C7의 고친 뒤 확인을 한다.
- [x] 코드 주석, `.env.example`, OPERATIONS.md, ARCHITECTURE.md를 고치고 `node scripts/check-docs.mjs`를 돌린다(커밋: `docs:` 또는 구현 커밋에 함께).
- [x] C6 수동 확인을 하고 결과를 진행 중 발견에 적는다.
- [x] 전체 검증(C9)을 돌린다. `2dbed71`에서 `node scripts/build-monitoring-bundle.mjs --check`(운영 명령 시험 통과 328, 실패 0)와 `node scripts/check-docs.mjs`가 통과했다.
- [x] 두 번째 독립 리뷰 반영과 범위 확장(아래 작업으로 나눠 마쳤다)
  - 멈춘 지점(2026-11-25): `431ba6d`에서 전체 검증은 통과했다. 두 번째 독립 리뷰에서, 다른 변수의 여러 줄 따옴표 값을 거치는 드문 표기는 운영 명령이 주석으로 보고 건너뛰지만 Compose는 그 변수로 읽는 경우가 남았다. 같은 문제를 두 번 고쳤으므로 AGENTS.md의 세션 규칙대로 더 고치지 않고 멈췄다. 공개 저장소라 표기는 적지 않는다.
  - 사람이 정한 것(2026-11-25):
    - 이 드문 표기는 받아들인다. 실수로 적기 어렵고, 다른 프로그램의 읽기를 흉내 내는 검사는 고칠수록 우회가 다시 나온다. 요구사항의 하지 않는 것, ARCHITECTURE.md, 이 계획에 한계를 일반적으로 적는다.
    - 떠 있는 채로 token 폴더 값만 바꾸고 `start`하면 Alertmanager를 다시 만들지 않는 문제(진행 중 발견의 범위 밖 문제)를 이번에 함께 고친다.
    - `monitoring-dev.bat start`에서 `.env`에 운영 명령과 Compose가 다르게 읽는 표기(변수 치환 등)로 두 값을 적으면 render와 Windows의 Compose가 어긋나는 문제(개발 환경만)를 이번에 함께 고친다.
  - 다음에 할 일:
    1. 요구사항 0028에 위 세 결정을 반영하고(한계는 하지 않는 것, 두 문제는 목표·동작·완료 기준), 이 계획에 두 문제의 접근 방법, 위험(지금 도는 서버가 갱신 뒤 첫 `start`에 다시 만들어지는지 등), 테스트 계획을 더한다. 사람에게 다시 승인받는다.
    2. 두 번째 리뷰의 고치면 좋음을 반영한다: 다른 값이나 줄 끝 주석에 변수 이름이 있어 멈출 때의 안내 문구(꼭 고침), 여러 줄 따옴표 값 안의 `이름=값` 줄, `grep -a`를 지키는 시험(GNU에서 돌리지 않으면 이유를 적음), NUL이 든 줄의 BusyBox 줄 번호, 시험의 줄 번호 확인과 정상 형식(따옴표 값, 줄 끝 주석, 빈 값) 시험.
    3. 구현 뒤 전체 검증, 새 독립 리뷰, PR 생성 여부를 사람에게 묻는다.
  - 진행(2026-11-26): 1번의 문서 작업을 했다. 두 문제의 원인을 코드와 `docker compose config`로 확인해 진행 중 발견에 적었다. 요구사항에 배경(범위를 넓힌 날짜와 이유), 목표 두 개, 하지 않는 것(받아들인 한계 등), S10~S14, C10~C15를 더하고 S8, S9, C1~C3, C8을 고쳤다. 정하지 못한 두 가지(개발용 값 파일에 포트를 넣을지, 다른 OS의 저장소 실행 안내를 고칠지)는 요구사항의 확인 필요에 적었다. 이 계획에는 두 문제의 접근 방법과 고르지 않은 방법, 바뀌는 부분, ARCHITECTURE.md에 적을 한계, 위험, 보안 위협, 테스트 계획, 아래 작업을 더했다. 2번은 테스트 계획(C1~C3)과 아래 작업에 넣었다. 남은 일은 사람의 재승인이다.
- [x] 사람에게 고친 요구사항(확인 필요 두 가지의 답 포함)과 이 계획을 다시 승인받는다. 2026-11-26에 승인받았다. 포트는 값 파일에 넣고, 다른 OS의 저장소 실행 안내도 이번에 고친다(요구사항 S13, S15, C16). 답은 요구사항 본문에 반영하고 확인 필요에서 지웠다.
- [x] 시험 먼저(C10, C12): 떠 있는 채로 token 폴더를 바꾼 `start`와 `render`, 같은 폴더의 다른 형태, 링크, `monitoring.env` 내용, 이 변경 전 펼친 설정 시험을 쓴다. 지금 코드에서 해당 시험이 실패하는 것을 본다.
- [x] `write_rendered`에 token 파일 실제 경로의 해시를 더한다. C10, C12와 기존 시험이 통과하는지 본다(커밋: `fix:`). `e8f1bbe`에 시험과 함께 커밋했다(시험 먼저 실패 6, 구현 뒤 통과 486, 실패 0).
- [x] 시험 먼저(C13, C15): 저장소 배치의 `compose.env` 내용과 묶음 배치에서 없음, 넘길 수 없는 글자의 멈춤 시험을 쓰고 실패를 본다.
- [x] 저장소 배치의 `compose.env` 쓰기와 S14의 멈춤을 구현하고, `monitoring-dev.bat`에 `--env-file`을 더한다. C13, C15 시험이 통과하는지 본다(커밋: `fix:`). 빌드 검사, 다른 OS 명령의 compose 파일 머리 주석, `render`의 저장소 안내와 함께 `edfd12e`에 커밋했다(시험 먼저 실패 10, 구현 뒤 통과 498, 실패 0). 문서는 `d03a78b`다.
- [x] 빌드 검사에 `compose.env`를 실제 Compose로 읽는 검사를 더하고(C13), `node scripts/build-monitoring-bundle.mjs --check`로 통과와, 작은따옴표 없이 쓰는 사본에서의 실패를 본다.
- [x] 두 번째 리뷰의 고치면 좋음: `env_unread_message`에 이름을 빼라는 안내를 더하고, C1~C3의 보강 시험(정확한 줄 번호, NUL 뒤 줄 번호, 안내 문구, 여러 줄 따옴표 값 안의 줄, 정상 형식)을 쓴다. 시험이 먼저 실패하는 것(안내 문구)을 본다. 시험 먼저 실패 5(안내 문구 4, NUL 뒤 줄 번호 1)였고, NUL 뒤 줄 번호를 고친 뒤 `53f6f11`에 커밋했다(통과 510, 실패 0).
- [x] 고친 사본으로 결함을 잡는지 본다: 경로 해시를 뺀 사본, `.env` 값 글자로 해시하는 사본, `compose.env`에 `.env` 줄을 옮기는 사본, S14 멈춤을 지운 사본, 안내 줄을 지운 사본, `grep -a`를 지운 사본(실패하지 않을 것으로 봄, 이유 기록). GNU `grep`에서 `-a`의 차이를 수동으로 확인해 적는다.
- [x] 코드 주석, OPERATIONS.md(token 폴더를 바꾼 `start`, 저장소에서 실행의 값 파일과 S14, 다른 OS 명령), DEVELOPMENT.md와 compose 파일 머리 주석의 다른 OS 명령, ARCHITECTURE.md(받아들인 한계, 개발용 띄우기의 값 넘기기)를 고치고 `node scripts/check-docs.mjs`를 돌린다.
- [x] C11, C16(dind)과 C14(Windows) 수동 확인을 하고 결과를 진행 중 발견에 적는다. 띄운 컨테이너, 받은 이미지, 시험용 폴더는 확인 뒤 지운다.
- [ ] 전체 검증(C9)을 돌리고 커밋 SHA와 결과를 적는다.
- [ ] 구현하지 않은 세션이나 하위 에이전트에 리뷰를 맡긴다. 요구사항의 완료 기준, 이 계획, diff, 검증한 커밋 SHA와 명령·결과, 위 보안 위협의 CWE를 준다.
- [ ] 리뷰를 반영한 뒤 계획 템플릿의 상태 값 설명대로 남길 것을 옮기고, 요구사항을 구현됨으로 바꾸고, 이 계획을 지운다(병합 전 마지막 커밋).

## 진행 중 발견

- **시험과 구현을 한 커밋에 담음**: 작업 순서에는 시험을 `test:` 커밋으로 먼저 남긴다고 적었지만, [DEVELOPMENT.md의 커밋](../DEVELOPMENT.md#커밋)은 커밋 전에 바꾼 부분의 시험이 통과해야 한다고 정한다. 그래서 시험을 먼저 쓰고 실패를 본 뒤(통과 260, 실패 68) 구현과 함께 `edd924c`에 커밋했다. 구현 뒤에는 통과 328, 실패 0이다.
- **결함을 잡는지 본 사본**: `infrastructure/`에서 시험에 필요한 파일만 OS 임시 폴더에 복사하고, 사본의 `monitoring-server.sh`를 한 곳씩 고쳐 시험을 돌렸다. token 폴더의 멈춤을 지운 사본은 38개, token 폴더의 export를 지운 사본은 4개, CA 경로의 멈춤을 지운 사본은 26개, CA 경로의 export를 지운 사본은 2개, 이름 경계 검사를 지운 사본은 1개, 파일 첫머리의 보이지 않는 글자 처리를 지운 사본은 20개, `install`의 멈춤을 지운 사본은 12개 시험이 실패했다. 모두 고친 부분을 맡는 시험이다.
- **C7(2026-11-25, Windows, Docker Desktop 29.9.3)**: 고치기 전의 `monitoring-dev.bat start`에 셸 환경 변수로 token이 없는 폴더를 주자 render가 통과했다. 이어서 Compose가 `secret file ... does not exist` 경고만 내고 그 폴더의 token 경로를 Alertmanager에 붙여 띄웠고, Docker가 그 자리에 빈 폴더를 만들었다. 고친 뒤에는 그 폴더를 render가 검사해 멈췄고(`token 자리에 폴더가 있습니다`, 폴더를 지운 뒤에는 `token 파일이 없습니다`) Compose를 부르지 않았다. 그 폴더에 시험용 token을 두면 Alertmanager의 token 원본이 그 폴더의 파일이었다. 개발용 시스템은 확인 뒤 내렸고 시험용 폴더는 지웠다.
- **C6(2026-11-25, `docker:29-dind`, `--privileged --memory 1536m --cpus 2`, 안쪽 Docker 29.10.1)**: `ddae432`로 만든 묶음을 운영 가이드대로 설치했다. 컨테이너를 지운 뒤 `start`하면 Alertmanager의 token 원본이 경우마다 운영 명령이 검사한 파일이었다. `.env`에 적지 않으면 설치 폴더의 `secrets/`, `.env`에 `이름=폴더`로 적으면 그 폴더, 셸 환경 변수로 주면 그 폴더였다. 운영 명령과 Compose가 다르게 읽는 표기로 적은 경우에는 운영 명령이 읽은 글자 그대로의 폴더였고, 같은 `.env`로 `docker compose config`를 실행하면 Compose는 다른 폴더로 풀었다. Blackbox Exporter의 CA 원본은 `.env`에 적은 OS의 파일이었다. CA 경로를 다르게 읽는 표기와, 두 값을 읽지 않는 표기로 적은 경우에는 `start`가 멈추고 컨테이너가 바뀌지 않았다. 확인 뒤 dind 컨테이너와 볼륨, 받은 이미지, 만든 묶음을 지웠다.
- **독립 리뷰 뒤 줄 고르기 규칙을 넓힘(2026-11-25)**: 처음 규칙은 줄 앞의 공백, `export`, 파일 첫머리의 보이지 않는 글자를 건너뛰면 변수 이름으로 시작하는 줄만 골랐다. 독립 리뷰가 Compose v5.7.0으로 확인한 바로는, 변수 이름으로 시작하지 않아도 Compose가 그 변수로 읽는 줄이 있었고 처음 규칙은 이런 줄을 고르지 못했다. 그러면 세 명령이 멈추지 않고 `start`가 기본 위치를 붙여, 이 변경이 만든 퇴행이 된다. 일부는 BusyBox와 GNU의 C 로캘에서만 놓쳐 실행 환경에 따라 결과가 달랐다. 그래서 표기를 하나씩 더하지 않고, 변수 이름이 낱말로 든 줄 가운데 주석 줄과 운영 명령이 읽는 형식을 뺀 나머지를 모두 고르게 바꿨다. 읽는 형식이어도 값에 같은 이름이 다시 나오면 고른다. Compose가 같은 줄의 뒤쪽 값을 읽을 수 있어서다. 두 함수는 `LC_ALL=C`로 실행하고 `grep`에 `-a`를 줬다. GNU grep 3.0에서 `-a` 없이는 NUL이 든 파일에서 줄 번호 대신 `Binary file (standard input) matches`가 나오고, `-a`를 주면 줄 번호가 나오는 것을 확인했다. 운영 명령 시험은 BusyBox에서만 돌아 이 차이를 잡지 못한다. 안내와 OPERATIONS.md에는 형식이 맞아 보이면 그 줄을 지우고 다시 입력하라는 말을 더했다. 바꾼 뒤 `e0a23fc`로 만든 묶음을 같은 한도의 `docker:29-dind`에 설치해 다시 확인했다. 운영 명령이 읽는 여러 모양으로 두 값을 적으면 띄웠고 Mounts의 원본이 적은 값이었다. 리뷰가 찾은 표기 가운데 세 가지(token 폴더)와 한 가지(CA 경로)로 적으면, 같은 `.env`에서 Compose는 그 폴더로 풀었지만 `start`는 줄 번호와 안내를 보이고 컨테이너를 바꾸지 않고 멈췄다.
- **C6에서 찾은 범위 밖 문제**: 모니터링 시스템이 떠 있는 채로 token 폴더 값만 바꾸고 `start`하면, 운영 명령은 새 폴더의 token을 검사하고 성공으로 끝나지만 Alertmanager를 다시 만들지 않아 예전 token 파일을 계속 붙인다. Compose가 서비스를 다시 만들지 판단할 때 token 파일 경로를 보지 않고, 운영 명령도 펼친 설정과 CA 인증서 파일이 바뀔 때만 다시 만들게 하기 때문이다(`write_rendered`의 `render_changed`). 이 변경 전부터 있던 동작이고, 이번 변경은 이 동작을 바꾸지 않았다. 사람이 이번에 함께 고치기로 했다(2026-11-25). 요구사항의 S10~S12가 이 문제다.
- **떠 있는 채로 token 폴더를 바꾼 start의 원인 확인(2026-11-26)**: Docker 데몬이 꺼진 Windows PC에서 Docker Compose v5.7.0의 `docker compose config --hash "*"`로 확인했다. compose 파일만 OS 임시 폴더에 복사하고 빈 `--env-file`을 주어 실제 `.env`는 읽지 않았다. token 폴더 값을 두 값으로 바꿔도 세 서비스의 해시가 모두 같았고, CA 경로 값을 바꾸면 Blackbox Exporter의 해시만 바뀌었다. 코드에서는 `cmd_start`가 `render_changed=yes`일 때만 `--force-recreate`를 주고, `write_rendered`의 `config_hash`는 펼친 설정 세 개와 CA 인증서 파일 내용만 본다. 그래서 token 폴더만 바꾸면 Compose도 운영 명령도 Alertmanager를 다시 만들지 않는다. 같은 확인에서 `env_file`(`rendered/monitoring.env`)의 값도 Compose가 `${…}`를 풀어 쓰는 것을 봤다. 그래서 `monitoring.env`에는 경로 글자를 넣지 않고 해시만 넣기로 했다.
- **개발용 띄우기의 어긋남 원인 확인(2026-11-26)**: `monitoring-dev.bat start`는 render 컨테이너의 export를 받지 못하고 Windows의 Compose가 `infrastructure/.env`를 다시 읽는다(`monitoring-dev.bat`의 `docker compose -f infrastructure\compose.yml up -d --build`). 같은 임시 폴더에서 `.env` 표기를 바꿔 가며 운영 명령의 `env_value`, `env_unread_line`, 0027의 `monitoring_valid_ca_file`(Git Bash의 GNU sed로 실행)과 `docker compose config`가 푼 CA 원본과 token 파일 경로를 비교했다. 변수 치환, 따옴표, 백슬래시가 든 CA 경로 값은 형식 검사가 막았다. 하지만 줄 끝 주석을 다루는 방식이 다른 표기 하나와, 다른 변수의 여러 줄 값을 거치는 표기 세 가지는 형식 검사와 줄 검사를 모두 통과하고 두 쪽이 다른 값을 읽었다. 네 가지 모두 운영 서버의 `start`에서는 운영 명령이 읽은 값을 export하므로 맞는다. token 폴더 값은 형식 검사가 없어 변수 치환 같은 표기도 그대로 지나간다. 공개 저장소라 표기는 적지 않는다.
- **Compose의 작은따옴표 값 읽기(2026-11-26)**: 같은 방법으로 `--env-file`에 작은따옴표로 감싼 token 폴더 값을 주었다. `${…}`, `$$`, `#`, 공백, 큰따옴표, `\t` 같은 글자는 글자 그대로 읽었다. `\\`는 `\`로, `\'`는 `'`로 풀었고, 값이 `\`로 끝나면 따옴표가 닫히지 않았다고 멈췄다. `''`는 빈 값이라 compose 파일의 기본값을 썼다. `--env-file`을 둘 주면 뒤의 파일이 이기고, 셸 환경 변수는 `--env-file`보다 먼저 쓰였다. 이 결과로 접근 방법의 값 파일 형식과 S14의 멈춤을 정했다.

- **NUL이 든 줄 뒤의 줄 번호(2026-11-26)**: 두 번째 리뷰의 지적대로 BusyBox `grep -n`은 NUL을 줄의 끝으로 세어, NUL이 든 줄 뒤의 줄 번호를 하나 크게 보였다(공식 Prometheus 이미지에서 `tr`, `sed`는 줄을 그대로 두고 `grep -n`만 밀림). `env_unread_line`이 NUL을 `?`로 바꾼 뒤 고르게 했다. `?`는 이름에 쓰지 않는 글자라 NUL이 붙은 이름도 낱말로 골라 넓게 멈춘다. 바꾼 뒤에는 BusyBox와 GNU grep 3.0(Git Bash) 모두 실제 줄 번호를 보였다.
- **`grep -a`(2026-11-26)**: 빌드가 받는 이미지는 모두 BusyBox(공식 Prometheus 등)라 GNU `grep`에서 운영 명령 시험을 돌릴 수 없다. `-a`를 지운 사본으로 시험을 돌리면 그대로 통과(510, 실패 0)해, 시험이 `-a`를 지키지 못한다. 새 이미지는 빌드 의존성이라 들이지 않았다. 대신 Git Bash의 GNU grep 3.0에서 수동으로 봤다. NUL을 바꾸기 전 함수에서 `-a`가 없으면 줄 번호 대신 `Binary file (standard input) matches`가 나오고, 있으면 줄 번호가 나왔다. 지금은 NUL을 먼저 바꾸므로 NUL로 생기는 이 차이는 없어졌고, `-a`는 잘못된 UTF-8에 대비해 남긴다.
- **결함을 잡는지 본 사본(2026-11-26)**: `infrastructure/`에서 시험에 필요한 파일만 OS 임시 폴더에 복사하고 한 곳씩 바꿔 운영 명령 시험을 돌렸다. token 경로 해시를 상수로 바꾼 사본(경로를 판단에서 뺀 것과 같음)은 4개, 실제 경로 대신 `token_file`의 글자를 쓰는 사본은 1개(링크), `compose.env` 값을 작은따옴표 없이 쓰는 사본은 6개, `compose.env`에 `.env`의 줄을 그대로 옮기는 사본은 5개, S14 멈춤을 지운 사본은 3개, 이름을 빼라는 안내 줄을 지운 사본은 4개, NUL 바꾸기를 지운 사본은 1개 시험이 실패했다. `-a`를 지운 사본은 실패하지 않았다(위 항목). 경로 해시를 `config_hash`에서만 뺀 첫 사본은 지난번 값과 늘 달라 결함을 흉내 내지 못해 버렸다. 빌드 검사의 새 확인(C13)은 같은 명령을 사본에 손으로 실행해 봤다. 작은따옴표 없이 쓰는 사본에서는 Compose가 다른 폴더를 풀어 일치하지 않았고, 지금 코드에서는 일치했다.
- **빌드 검사의 `$$`(2026-11-26)**: `docker compose config`의 출력은 글자 `$`를 다시 읽을 수 있게 `$$`로 적는다. 그래서 C13의 빌드 검사는 token 파일 경로의 `$$`를 `$`로 되돌린 뒤 비교한다.
- **전체 빌드 검사(2026-11-26, `edfd12e` 커밋 직전의 작업 트리)**: `node scripts/build-monitoring-bundle.mjs --check`가 새 `compose.env` 확인을 포함해 통과했다(운영 명령 시험 통과 498, 실패 0).
- **C11(2026-11-26, `docker:29-dind`, `--privileged --memory 1536m --cpus 2`)**: `53f6f11`로 만든 묶음을 운영 가이드대로 설치했다. 처음 `start` 뒤 Alertmanager의 token 원본은 설치 폴더의 `secrets/`였다. `.env`의 token 폴더만 다른 폴더로 바꾸고 `start`하자 컨테이너 ID가 바뀌고 원본이 새 폴더의 파일이 됐다. 이어서 그대로 `start`하면 컨테이너 ID가 같았다. 고치기 전 재현은 묶음을 다시 만들지 않고, 같은 설치 폴더의 `monitoring-server.sh`를 `ef5b185`의 것으로 바꾸고 `SHA256SUMS`의 그 줄을 맞춰 했다. token 폴더를 바꾼 `start`가 성공으로 끝났지만 컨테이너 ID와 token 원본이 그대로였다. 확인 뒤 스크립트를 되돌렸다.
- **C16(2026-11-26, 같은 dind)**: 저장소의 `infrastructure/`를 `git archive HEAD`로 옮기고, 묶음 이미지에 `:dev` 태그를 붙여 `--build` 없이 띄웠다(dind 안에서 공식 이미지를 다시 받지 않기 위해서다). `.env`에 token 폴더를 운영 명령과 Compose가 다르게 읽는 표기로 적고 Alertmanager 포트를 19093으로 했다. 고치기 전 명령(`--env-file` 없음)은 Compose가 푼 폴더의 token을 붙였고, 운영 가이드의 고친 명령은 `render`가 검사한 폴더의 token을 붙였다. 두 경우 모두 포트는 19093이었다. `render`의 저장소 안내에 고친 명령이 보였다. 확인 뒤 dind 컨테이너(볼륨 포함), 받은 `docker:29-dind` 이미지, `dist/`의 `53f6f11` 묶음과 그 이미지를 지웠다.
- **C14(2026-11-26, Windows, Docker Desktop 29.9.3)**: 실제 `infrastructure/.env`와 `rendered/`는 내용을 읽지 않고 이름만 바꿔 두었다. 시험용 `.env`에 token 폴더를 운영 명령과 Compose가 다르게 읽는 표기로 적고, 두 폴더에 시험용 token을 두고 Prometheus 포트를 19090으로 했다. 고친 `monitoring-dev.bat start` 뒤 Alertmanager의 token 원본은 `render`가 검사한 폴더의 파일, Blackbox Exporter의 CA 원본은 `infrastructure/certs/ca-certificates.crt`, Prometheus 포트는 19090이었다. 같은 상태에서 고치기 전 bat의 Compose 호출(`--env-file` 없음)이 붙일 파일을 `docker compose config`로 보니 Compose가 푼 다른 폴더였다. 고치기 전 bat을 실제로 띄우지는 않았다. 검사한 폴더에서 token을 치우고 다시 실행하자 `render`가 `token 파일이 없습니다`로 멈추고 Compose를 부르지 않았다(컨테이너 ID 그대로). 확인 뒤 `monitoring-dev.bat stop`, 시험용 폴더와 `.env` 삭제, 원래 `.env`와 `rendered/` 되돌리기를 했다. 개발용 띄우기의 `--build`가 `monitoring-system-*:dev` 이미지를 이 브랜치로 다시 빌드했고, 예전 `:dev` 이미지는 Docker가 지워 되돌리지 못했다.
- **범위 밖에서 본 것**: 작업 전부터 Docker에 이름 없는 볼륨이 200개 가까이 있었고, 이번 작업 중에도 어느 컨테이너도 쓰지 않는 볼륨 3개가 생겼다. 공식 Prometheus 이미지에 `VOLUME`이 있어 `docker create` 뒤 `-v` 없이 `docker rm`하면 볼륨이 남는데, 빌드 검사가 그렇게 지운다(예: `extractRules`). 누가 만든 볼륨인지 확인하지 못해 지우지 않았다.

## 남길 결정

없음. Compose가 `.env`에서 직접 읽는 값은 운영 명령이 검사한 값을 넘기고, 운영 명령이 읽지 않는 형식이면 멈춘다는 규칙은 ARCHITECTURE.md의 지켜야 할 경계에만 남긴다(사람 확인: 2026-11-25). 되돌려도 운영자의 `.env`나 다른 모듈을 고칠 필요가 없어서 ADR로 남기지 않는다. 받아들인 한계와 개발용 띄우기가 검사한 값을 넘긴다는 것도 같은 절에 남긴다(ARCHITECTURE.md에 적을 한계). `monitoring.env`의 `MONITORING_TOKEN_PATH_SHA256`과 저장소의 `compose.env`는 운영 명령 안의 구현이라 되돌리기 쉬워 ADR로 남기지 않는다.
