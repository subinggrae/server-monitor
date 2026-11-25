# 0027. OS의 CA 인증서 파일을 경로로 붙이기 구현 계획

- 요구사항: [0027. OS의 CA 인증서 파일을 경로로 붙이기](0027-ca-file-path.md)
- 상태: 진행 중

## 접근 방법

token 폴더 값(`MONITORING_SECRETS_DIR`)과 같은 방식으로 compose 파일의 볼륨 원본을 변수로 둔다.

```text
전:  - ./certs/ca-certificates.crt:/etc/ssl/certs/ca-certificates.crt:ro
후:  - ${MONITORING_CA_FILE:-./certs/ca-certificates.crt}:/etc/ssl/certs/ca-certificates.crt:ro
묶음: - ${MONITORING_CA_FILE:-../certs/ca-certificates.crt}:/etc/ssl/certs/ca-certificates.crt:ro
```

묶음의 기본값은 빌드 스크립트가 지금처럼 `./certs/`를 `../certs/`로 바꿔 만든다.

운영 명령은 다음처럼 바꾼다.

- 형식 검사는 `monitoring-settings.sh`에 관리자 서버 주소 검사(`monitoring_valid_address`)처럼 함수 하나(`monitoring_valid_ca_file`)로 둔다. `/`로 시작하고 영문자, 숫자, `.`, `_`, `/`, `-`만 쓴 값만 받는다. locale에 따라 범위가 달라지지 않게 글자를 나열한 `case`로 본다.
- `check_settings`가 다른 설정 값과 함께 CA 경로 값을 검사한다. 틀린 값을 한 번에 모두 보이고 멈추는 지금 방식을 따른다. 통과한 값은 `export`해 Compose가 `.env`를 다시 읽지 않고 그 값을 쓰게 한다. 값이 있으면 `CA_FILE`을 그 경로로 바꾼다.
- `check_ca`는 지금처럼 `-d`, `-e`, `-s`, `ls -ldL`로 확인한다. 이 넷은 링크를 따라가므로 실제 파일을 본다. 경로를 적은 경우에는 안내만 바꾼다. 파일이나 폴더를 지우거나 권한을 바꾸라고 하지 않고, `.env`의 값을 고치라고 한다.
- 값을 비우고 복사본도 없을 때의 안내(`ca_hint`)는 복사 명령 대신 `.env`에 OS별 경로를 적으라고 바꾼다. 저장소 배치(`monitoring-dev.bat`)에서는 그 배치 파일이 복사본을 만들어 두므로 이 안내가 보이지 않는다.
- CA 해시(`MONITORING_CA_SHA256`)는 지금처럼 `CA_FILE`의 내용으로 계산한다. 그래서 OS의 파일 내용이 바뀌면 설정 해시가 바뀌고, `start`가 컨테이너를 다시 만든다.

검토했지만 고르지 않은 방법:

- **운영 명령이 OS의 파일을 `certs/`로 복사**: 2026-11-10에 버린 방식이다(ADR 0012). 시스템 파일을 다루는 일을 운영 명령에 두지 않는다.
- **`readlink -f`로 실제 경로를 풀어 compose에 넘김**: Docker도 볼륨 원본의 링크를 따라가므로 필요 없다. 풀어 넘기면 `.env`의 값과 Compose가 쓰는 값이 달라진다.
- **경로를 `status`에 보임**: 요구사항의 하지 않는 것

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/compose.yml` | Blackbox Exporter의 CA 볼륨 원본을 `${MONITORING_CA_FILE:-./certs/ca-certificates.crt}`로, 머리 주석과 볼륨 주석 |
| `infrastructure/monitoring-settings.sh` | `monitoring_valid_ca_file` |
| `infrastructure/monitoring-server.sh` | `check_settings`의 CA 경로 값 검사와 `export`, `CA_FILE` 정하기, `check_ca`의 안내, 머리 주석 |
| `infrastructure/.env.example` | CA 경로 값의 뜻, OS별 예, Windows 개발 환경에서는 비워 둔다는 것 |
| `infrastructure/tests/monitoring-server.test.sh` | 아래 테스트 계획의 시험 |
| `scripts/build-monitoring-bundle.mjs` | compose 확인에 CA 경로 값을 준 경우를 더함, 실행용 compose 파일의 기본값 확인 |
| `docs/OPERATIONS.md` | 설치 절차, 설치 폴더 구조, CA 인증서 다시 복사 절, 이전 버전으로 되돌리기, 문제 해결, 운영 명령 없이 실행, 저장소에서 실행 |
| `docs/ARCHITECTURE.md` | 실행 단위의 CA 인증서 문장, 지켜야 할 경계의 설정 값 목록 |
| `docs/DEVELOPMENT.md` | 명령 표의 CA 인증서 복사 안내, compose 확인 설명 |
| `docs/decisions/0018-ca-file-path.md` | ADR 0012의 CA 복사 결정을 대신하는 새 ADR |

### 데이터 모델 변경

해당 없음

### 계약 변경

해당 없음. 관리자 서버와의 통합 계약은 바뀌지 않는다. 운영자가 쓰는 `.env` 값이 하나 는다.

## 위험과 되돌리기

- 운영자가 경로를 적은 뒤 `certs/` 복사본을 지우고 예전 버전으로 되돌린다: 예전 버전의 운영 명령이 CA 인증서 파일이 없다고 멈추고 아무것도 바꾸지 않는다. 운영 가이드에 복사본을 지울 수 있는 때를 적는다.
- Windows 개발 환경의 `.env`에 Linux 경로를 적는다: `render`가 그 파일이 없다고 멈춘다. `.env.example`에 비워 두라고 적는다.
- Docker가 볼륨 원본의 링크를 따라가지 않는 환경: 수동 확인(C7)에서 본다. 따라가지 않으면 계획을 고친다.
- 되돌리기: 이전 버전으로 되돌리면 `.env`의 새 값은 무시되고 `certs/` 복사본을 쓴다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 운영자가 적은 `.env` 값이 compose 파일의 볼륨 표기에 들어간다. `.env`를 고칠 수 있는 사람은 지금도 운영자뿐이다.
- 잘못될 수 있는 것과 막는 방법:
  - 값에 든 `:`나 공백으로 볼륨 표기가 바뀌어 다른 대상 경로나 옵션(`rw` 등)으로 붙는다(CWE-88) → 허용 글자 밖이면 거부한다. C5의 시험으로 확인한다.
  - 값에 든 셸 문자(`$`, 따옴표, 줄바꿈)가 운영 명령에서 해석된다(CWE-78) → 값을 `eval`하지 않고 따옴표로 감싸 쓰며, 허용 글자 밖이면 거부한다. C5의 시험으로 확인한다.
- 받아들이는 위험: 운영자가 CA 인증서가 아닌 다른 파일을 적으면 Blackbox Exporter가 그 파일을 읽을 수 있다. 다른 사용자가 읽을 수 있는 파일만 받고, `.env`를 고칠 수 있는 운영자는 이미 Docker로 서버의 모든 파일에 접근할 수 있으므로 새 권한이 생기지 않는다.

## 테스트 계획

운영 명령 시험은 `infrastructure/tests/monitoring-server.test.sh`에 더하고, `node scripts/build-monitoring-bundle.mjs --check`가 공식 이미지의 BusyBox `sh`로 실행한다.

- **C1**: 설치 폴더 밖의 임시 파일 경로를 `.env`에 적고 `render`하면 성공하고, `monitoring.env`의 `MONITORING_CA_SHA256`이 그 파일의 해시다. 셸 환경 변수로 줘도 같다. 빌드 검사의 compose 확인에 값을 준 경우(예: `/etc/pki/tls/certs/ca-bundle.crt`)를 더해, Blackbox Exporter에만 그 경로가 읽기 전용으로 붙는지 본다.
- **C2**: 실제 파일을 가리키는 링크는 성공한다. 폴더, 없는 파일, 빈 파일, 다른 사용자가 읽을 수 없는 파일을 가리키는 링크는 실패하고 `rendered/`를 만들지 않는다. 안내에 `.env`와 값 이름이 있고, `rmdir`, `rm`, `chmod`, `지운 뒤`가 없다.
- **C3**: 기존 CA 시험이 그대로 통과한다. 값을 빈 문자열로 적어도 `certs/` 복사본을 쓴다. 복사본이 없을 때의 안내에 `MONITORING_CA_FILE`과 두 OS 경로, `docs/OPERATIONS.md`가 있다.
- **C4**: 경로의 파일 내용을 바꾸고 `start`하면 컨테이너를 다시 만들고, 그대로면 다시 만들지 않는다. 기존 "CA 인증서 파일을 다시 복사한 뒤 띄우면" 시험과 같은 방법으로 본다.
- **C5**: 상대 경로(`certs/ca.crt`), 공백, `:`(`/a:/b`), 한글, `$`, 줄바꿈을 담은 값은 실패하고 `rendered/`를 만들지 않는다. 안내에 값 이름과 허용 형식이 있다.
- **C6**: 빌드 검사의 실행용 compose 확인이 `${MONITORING_CA_FILE:-../certs/ca-certificates.crt}`를 찾고, 값 없이 펼친 볼륨이 `../certs/ca-certificates.crt`다.
- **C7(수동 확인)**: 빌드 PC의 WSL Ubuntu에서 묶음을 설치 폴더 구조로 풀고 Docker Desktop으로 띄운다. `/tmp`에 OS의 CA 파일을 가리키는 링크를 만들어 `.env`에 적는다. 공인 인증서를 쓰는 HTTPS 주소(예: `https://prometheus.io`)를 `/probe`로 확인해 `probe_success 1`을 본다. 외부 HTTPS에 닿지 않는 망이면 결과와 함께 보고하고, 운영 서버에서의 확인을 사람에게 맡긴다.
- **C8(문서 리뷰)**: 바뀌는 부분의 문서를 읽고 요구사항 문장과 맞춘다.
- **C9(전체 검증)**: `node scripts/build-monitoring-bundle.mjs --check`와 `node scripts/check-docs.mjs`가 통과한다.

## 작업 순서

- [x] C1~C5의 운영 명령 시험을 먼저 쓰고 지금 코드에서 실패하는 것을 본다
- [x] `monitoring-settings.sh`, `monitoring-server.sh`를 고쳐 시험을 통과시킨다
- [x] `compose.yml`과 빌드 스크립트의 compose 확인을 고친다(C1, C6)
- [x] C7을 확인한다(WSL 대신 Docker Desktop의 Linux VM에서, 아래 진행 중 발견)
- [x] `.env.example`, OPERATIONS.md, ARCHITECTURE.md, DEVELOPMENT.md를 고친다
- [ ] 구현에서 바뀐 점이 있으면 ADR 0018(계획과 함께 씀)에 반영하고 상태를 승인으로 바꾼다
- [ ] 전체 검증, 자체 리뷰, 새 세션 리뷰

## 진행 중 발견

- 새 운영 명령 시험 19개 가운데 17개가 구현 전 코드에서 실패했다. 나머지 둘(경로를 비우면 복사본을 씀, 내용이 그대로면 컨테이너를 다시 만들지 않음)은 지금 동작을 지키는 시험이라 처음부터 통과한다. 구현 뒤 운영 명령 시험 253개가 모두 통과했다.
- 가짜 `docker compose up`이 받은 `MONITORING_CA_FILE` 값을 남기게 해, 운영 명령이 검사한 값을 Compose에 넘기는지 본다.
- Windows의 `docker compose config`는 `MONITORING_CA_FILE`의 절대 경로(`/etc/pki/tls/certs/ca-bundle.crt`)를 바꾸지 않고, 상대 경로만 project 기준 Windows 경로로 푼다. 그래서 빌드 검사의 compose 확인은 기대한 원본이 `/`로 시작하면 글자 그대로, 아니면 project 기준 상대 경로로 비교한다.
- C7은 계획의 WSL Ubuntu로 하지 못했다. 그 배포판은 Docker Desktop 연동이 꺼져 있고, 연동을 켜면 사람의 Docker Desktop 설정이 바뀌어서 켜지 않았다. 대신 2026-11-25에 Docker Desktop의 Linux VM 안에 실제 CA 파일과 그 파일을 가리키는 링크를 만들고, 링크 경로를 볼륨 원본으로 Blackbox Exporter(운영 이미지)를 띄웠다. `https://prometheus.io`의 `http_2xx` 확인이 `probe_success 1`이었고, 링크가 CA가 아닌 파일을 가리키게 바꾸자 `x509: certificate signed by unknown authority`로 실패했다. Docker가 링크를 따라 실제 파일을 붙인다는 것을 본 것이고, 운영 명령 전체 흐름은 실제 Linux 서버에서 돌리지 않았다.
- `install`은 `.env.example`로 `.env`를 만들므로, 새로 설치한 서버의 `.env`에는 주석 처리한 `MONITORING_CA_FILE` 줄과 설명이 들어간다.

## 남길 결정

- Blackbox Exporter가 쓰는 CA 인증서 파일을 `.env`의 경로 값으로 OS의 파일을 바로 붙일 수 있게 하고, 값을 비우면 설치 폴더의 복사본을 쓴다(ADR 0018).
