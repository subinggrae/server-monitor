# 0020. 모니터링 코어를 모니터링 시스템으로 바꿈(이름 포함)

- 상태: 구현됨
- 작성일: 2026-11-16
- 관련: [#56](https://github.com/subinggrae/server-monitor/issues/56), [0011](0011-core-ops-script.md), [0013](0013-core-env-settings.md), [0017](0017-core-shared-env.md), [0018](0018-core-only-repository.md), 요구사항 0019(브랜치 `feature/core-external-network`)

## 배경

이 제품은 Prometheus, Alertmanager, Blackbox Exporter를 묶어 서버와 서비스를 감시한다. 지금까지 "모니터링 코어", 줄여서 "코어"라고 불렀다. 관리자 서버 앱과 함께 있을 때 그 앱이 붙는 가운데 부분이라는 뜻이었다. [요구사항 0018](0018-core-only-repository.md)로 앱을 걷어낸 뒤 저장소에는 이 제품만 남았다. 제품 이름을 "모니터링 시스템"으로 부르기로 했다(사람 확인: 2026-11-16).

이름은 글에만 있지 않다. 운영자가 치는 명령(`core-server.sh`, `core-dev.bat`), 설정 파일에 적는 변수(`CORE_RETENTION_DAYS` 등), 이미지(`monitoring-core-prometheus`), 묶음 폴더(`monitoring-core-<버전>`), compose 프로젝트(`monitoring-core`)에도 들어 있다. 이름까지 바꾸기로 했다(사람 확인: 2026-11-16).

아직 정식으로 운영하는 서버가 없고, 지켜야 할 감시 기록도 없다(사람 확인: 2026-11-16). 그래서 이전 버전에서 옮기는 방법은 만들지 않는다.

## 목표

- 문서, 주석, 운영 명령과 개발 명령의 안내와 오류 문구에서 제품을 늘 "모니터링 시스템"이라고 쓴다. "시스템"으로 줄이지 않는다. 설치한 Linux 서버는 "모니터링 서버"라고 쓴다(사람 확인: 2026-11-16).
- 운영자와 개발자가 보고 치는 이름을 다음처럼 바꾼다. 명령과 설정 변수는 이미 있는 `MONITORING_APP_ADDRESS`처럼 `monitoring`(변수는 `MONITORING_`)으로 시작하고, 제품을 가리키는 compose 프로젝트, 이미지, 묶음 폴더는 `monitoring-system`을 쓴다(사람 확인: 2026-11-16).

  | 지금 | 바뀐 뒤 |
  |---|---|
  | 운영 명령 `core-server.sh` | `monitoring-server.sh` |
  | 값 검사 규칙 `core-settings.sh` | `monitoring-settings.sh` |
  | 개발 명령 `core-dev.bat` | `monitoring-dev.bat` |
  | 빌드 스크립트 `scripts/build-core-bundle.mjs` | `scripts/build-monitoring-bundle.mjs` |
  | 설정 변수 `CORE_SECRETS_DIR`, `CORE_RETENTION_DAYS`, `CORE_RETENTION_SIZE_GB`, `CORE_PROBE_TIMEOUT_SECONDS`, `CORE_PROMETHEUS_PORT`, `CORE_ALERTMANAGER_PORT`, `CORE_BLACKBOX_PORT` | 앞의 `CORE_`를 `MONITORING_`으로 바꾼 이름(예: `MONITORING_RETENTION_DAYS`) |
  | compose 프로젝트 `monitoring-core` | `monitoring-system` |
  | 이미지 `monitoring-core-prometheus`, `monitoring-core-alertmanager`, `monitoring-core-blackbox-exporter` | `monitoring-system-prometheus`, `monitoring-system-alertmanager`, `monitoring-system-blackbox-exporter` |
  | 묶음 폴더 `monitoring-core-<버전>` | `monitoring-system-<버전>` |

- 위 표에 없는 코드와 설정 안의 이름(펼친 설정 파일, 컨테이너 안 경로, 이미지 label, 빌드 인자, 시험 파일 등)도 제품을 `core`로 부르지 않는다.
- [TERMS.md](../TERMS.md)에 "모니터링 시스템", "모니터링 서버" 행을 더한다. 0018에서 미룬 Telegram 시절 화면 글 행을 정리한다. "받는 그룹" 행은 알림 수단과 무관한 "알림 받을 곳"으로 바꾸고, "설정 안 됨"과 "토픽 ID" 행은 지운다(사람 확인: 2026-11-16).

## 하지 않는 것

- 이전 버전(모니터링 코어)에서 옮기는 방법. 운영 명령이 `.env`의 옛 이름이나 옛 이름으로 띄운 컨테이너를 찾지 않고, 운영 가이드에 옮기는 절차도 두지 않는다. 정식으로 운영하는 서버가 없기 때문이다. 이전 버전을 띄운 곳은 사람이 직접 지운다(사람 확인: 2026-11-16).
- 지난 요구사항과 ADR의 글과 파일 이름을 바꾸는 것. 결정할 때의 말을 남긴 기록이기 때문이다. 다만 바뀐 제목을 가리키는 링크는 문서 검사가 통과하도록 링크 주소만 고친다(요구사항 0013의 통합 계약 링크).
- 이름을 바꾸기 전 묶음(요구사항 0017 전 방식 포함)을 위한 운영 명령의 처리(`previous_env`, 옛 이름 `monitoring-core-<버전>` 폴더에서 `.env` 찾기)와 운영 가이드의 절차를 지우는 것. 동작이 바뀌므로 [#58](https://github.com/subinggrae/server-monitor/issues/58)에서 따로 정한다. 그때까지 이 처리는 옛 폴더 이름을 그대로 찾고, 운영 가이드에는 이름을 바꾸기 전 묶음과 갱신·되돌리기를 할 수 없다는 안내만 더한다(사람 확인: 2026-11-16).
- 리뷰와 수동 확인에서 찾은 범위 밖 문제: Dockerfile의 commit-time label 주석([#59](https://github.com/subinggrae/server-monitor/issues/59)), Alertmanager를 다시 만들면 이어지던 장애의 `startsAt`이 바뀌는 것([#60](https://github.com/subinggrae/server-monitor/issues/60)). 둘 다 이름 변경 전부터 있던 동작이다.
- 컨테이너 이름(`prometheus`, `alertmanager`, `blackbox-exporter`), 볼륨 이름(`prometheus-data`, `alertmanager-data`), 네트워크 이름(`monitoring`)을 바꾸는 것. 관리자 서버가 컨테이너 이름과 네트워크로 붙는다. 이름에 "코어"도 없다.
- node_exporter 묶음(`node-exporter-<버전>`, 이미지 `monitoring-node-exporter`)의 이름을 바꾸는 것. 이름에 "코어"가 없다.
- 관리자 서버 주소 변수 `MONITORING_APP_ADDRESS`의 이름을 바꾸는 것. 이름에 "코어"가 없다(사람 확인: 2026-11-16).
- 제품을 뜻하지 않는 core를 바꾸는 것. 문서 검사의 `--no-core`(기본 문서 확인 생략), windows_exporter의 `core` label, CPU 코어가 그렇다.
- 저장소 이름(`server-monitor`)과 GitHub 이슈·PR 제목의 옛 이름을 바꾸는 것
- 모니터링 시스템이 미리 만든 `monitoring` 네트워크를 쓰게 하는 것. 요구사항 0019에서 이 이름으로 한다.

## 사용자 경험

운영자는 지금과 같은 순서로 설치하고 갱신한다. 이름만 바뀐다.

1. 빌드 PC에서 `monitoring-dev.bat build`로 묶음을 만든다. `dist\`에 `monitoring-system-<버전>` 폴더가 생긴다.
2. 묶음 폴더를 모니터링 서버의 설치 폴더에 옮긴다.
3. 묶음 폴더에서 `sudo sh monitoring-server.sh install`, `start`를 실행한다. 설치 폴더의 `.env`에는 `MONITORING_`으로 시작하는 이름이 들어 있다.
4. 새 버전으로 갱신할 때는 새 묶음 폴더를 옆에 두고 `start`를 실행한다.

개발자는 `core-dev.bat` 대신 `monitoring-dev.bat`의 같은 작업(`check`, `build`, `start`, `stop`)을 쓴다.

## 동작

### 정상 동작

- **S1. 처음 설치**
  - 상황: 모니터링 서버에 모니터링 시스템이 없다.
  - 행동: 운영자가 운영 가이드대로 묶음 폴더에서 `install`, `start`를 실행한다.
  - 결과: 설치 폴더의 `.env`가 새 이름(`MONITORING_*`)으로 만들어지고, 모니터링 시스템이 compose 프로젝트 `monitoring-system`, 새 이름의 이미지로 뜬다. 안내와 오류 문구는 "모니터링 시스템", "모니터링 서버"라고 쓴다.

- **S2. 새 버전으로 갱신**
  - 상황: 이 변경 뒤의 버전이 떠 있다.
  - 행동: 운영자가 더 새 묶음 폴더에서 `start`를 실행한다.
  - 결과: 지금과 같이 확인 없이 새 버전으로 바뀌고, 기록, 장애 상태, token, CA 인증서 파일, 설정 값이 이어진다.

- **S3. 개발 명령**
  - 상황: 개발자가 Windows 빌드 PC의 저장소에서 작업한다.
  - 행동: `monitoring-dev.bat check`, `build`, `start`, `stop`을 실행한다.
  - 결과: 지금의 `core-dev.bat`과 같은 일을 한다.

### 예외 동작

- **S4. 설정 값 오류**
  - 상황: `.env`의 새 이름 변수에 허용 범위를 벗어나거나 형식이 틀린 값이 있다.
  - 행동: 운영자가 `render`나 `start`를 실행한다.
  - 결과: 지금과 같은 형식으로 새 변수 이름과 고치는 방법을 보이고 멈춘다. 떠 있는 모니터링 시스템은 그대로 돈다.

### 보안

- 접근 권한: 지금과 같다. 운영 명령은 모니터링 서버에서 `sudo`로 실행하고, 포트는 `127.0.0.1`에만 연다.
- 외부 입력: `.env`의 값과 셸 환경 변수. 지금처럼 `.env`를 셸로 실행하지 않고 읽고, 검사를 통과한 값만 펼친다. 이름만 바뀐다.
- 민감한 정보: webhook token. 위치(설치 폴더의 `secrets/` 또는 token 폴더 변수)와 권한은 바뀌지 않는다.
- 남길 보안 이벤트: 없음

### 바뀌지 않는 것

- HTTP SD와 Alertmanager webhook 계약([INTEGRATION.md](../INTEGRATION.md)): 경로, label, token, payload
- 컨테이너 이름, 컨테이너 안 포트, 네트워크 이름, 볼륨 이름, 호스트 포트의 기본값과 `127.0.0.1` 바인딩
- 설정 변수의 허용 범위, 기본값, 검사와 안내 형식([요구사항 0013](0013-core-env-settings.md))
- 설치 폴더 구조: `.env`, `secrets/`, `certs/`를 설치 폴더에 두고 묶음 폴더를 나란히 둔다([요구사항 0017](0017-core-shared-env.md))
- 운영 명령의 작업(`install`, `render`, `start`, `status`)과 각 작업이 하는 일, 보관 값을 줄일 때의 확인
- 이미지와 묶음에 담는 파일과 라이선스([요구사항 0015](0015-core-minimal-image.md))

## 완료 기준

- [x] C1. 지난 요구사항·ADR과 "하지 않는 것"에 적은 예외 밖에서 `git grep`으로 "코어"와 제품을 뜻하는 `core`(파일 이름 포함)가 나오지 않는다. (목표) — 확인: `git grep -n 코어`, `git grep -n -i core`, `git ls-files | grep -i core`의 결과를 예외와 대조 (2026-11-16 확인. 남은 "코어"는 TERMS.md의 쓰지 않는 말과 "CPU 코어", 남은 `core`는 `--no-core`·`CORE_DOCS`, windows `core` label, GNU coreutils, 지난 요구사항·ADR 파일 이름, #58의 옛 묶음 폴더 이름, 옛 변수 이름 시험뿐이다)
- [x] C2. 운영 명령 시험이 새 이름(명령, 변수, 이미지, 묶음 폴더)으로 통과하고, 옛 변수 이름만 적은 `.env`의 값은 쓰지 않는다. (S1, S4) — 확인: 운영 명령 시험 (2026-11-16 확인. 통과 172, 실패 0. 새 시험은 옛 값을 읽게 망가뜨린 운영 명령에서 실패했다)
- [x] C3. 빌드 PC에서 만든 묶음으로 처음 설치하면 S1대로 뜨고, 다른 커밋으로 만든 묶음으로 갱신하면 S2대로 기록과 장애 상태가 이어진다. (S1, S2) — 확인: Linux 컨테이너에서 수동 확인 (2026-11-16 확인. `docker:28-dind`에서 `ef37fa3` 묶음으로 설치하자 `.env`가 `MONITORING_*`로 생기고 compose 프로젝트 `monitoring-system`으로 떴다. `c12e64b` 묶음으로 갱신하자 가장 오래된 표본 시각, 장애의 fingerprint, 바꾼 설정 값(보관 30일, 포트 19090)이 이어지고 webhook도 계속 왔다. 장애의 `startsAt`은 바뀌었고 이는 #60)
- [x] C4. `monitoring-dev.bat check`가 통과하고, `start`, `stop`이 개발용 모니터링 시스템을 띄우고 멈춘다. (S3) — 확인: 실행 (2026-11-16 확인. `check` 통과, `start` 뒤 `/-/ready` 200, `stop` 뒤 컨테이너 없음)
- [x] C5. TERMS.md에 "모니터링 시스템", "모니터링 서버", "알림 받을 곳" 행이 있고 "받는 그룹", "설정 안 됨", "토픽 ID" 행이 없으며, `node scripts/check-docs.mjs`가 오류 없이 통과한다. (목표) — 확인: 실행 (2026-11-16 확인. `--render` 오류 0, 경고 0)

## 확인 필요

없음
