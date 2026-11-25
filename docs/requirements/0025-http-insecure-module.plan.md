# 0025. 인증서를 검증하지 않는 HTTP 확인 module 구현 계획

- 요구사항: [0025. 인증서를 검증하지 않는 HTTP 확인 module](0025-http-insecure-module.md)
- 상태: 진행 중

## 접근 방법

`infrastructure/blackbox/blackbox.yml`에 `http_2xx`를 그대로 옮긴 `http_2xx_insecure`를 더하고, `http.tls_config.insecure_skip_verify: true`만 다르게 둔다. 확인 제한 시간은 다른 module처럼 `@MONITORING_PROBE_TIMEOUT_SECONDS@`로 둔다.

빌드 검사(`checkBlackboxConfig`)는 펼친 `blackbox.yml`을 module 단위로 나눠 본다. 주석 줄은 설정이 아니므로 빼고 본다.

- module마다 `timeout`이 하나이고 확인 제한 시간과 같다(지금과 같다).
- `http_2xx_insecure`가 있고, 그 안에 `insecure_skip_verify: true`가 하나 있다.
- 다른 module에는 `insecure_skip_verify`가 없다.

검토했지만 고르지 않은 방법:

- **`.env` 옵션으로 `http_2xx`의 검증을 끔**: 관리자 서버 쪽 사람이 버렸다(요구사항의 하지 않는 것).
- **`http_2xx_insecure`가 `http_2xx`와 인증서 설정 밖에서 같은지 빌드 검사가 비교**: 요청에 없는 검사이고, 두 module이 다르게 바뀌어야 할 때 검사를 함께 고쳐야 한다. 리뷰에서 확인한다.
- **YAML 파서를 들여 검사**: 빌드 스크립트는 설치할 패키지가 없다(DEVELOPMENT.md의 준비). 펼친 파일은 저장소 템플릿에서 나오므로 줄 단위로 읽어도 된다.

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/blackbox/blackbox.yml` | `http_2xx_insecure` module을 더하고, 머리 주석의 CheckType과 module 짝에 새 module과 그 위험을 적는다 |
| `scripts/build-monitoring-bundle.mjs` | `checkBlackboxConfig`를 module 단위 검사로 바꾼다 |
| `docs/DEVELOPMENT.md` | 모니터링 시스템 검사의 Blackbox 설정 검사 설명 |
| `docs/INTEGRATION.md` | `module` 값 목록, 확인 방식 표, 잡지 못하는 것, 배포 순서, 읽을 수 있는 series에 두 지표 |
| `docs/OPERATIONS.md` | 감시 흐름이나 보안 설정에 인증서를 검증하지 않는 주소의 위험, 문제 해결 절에 실패 원인 보는 방법 |
| `docs/ADMIN-SERVER.md` | `module` 값을 설명하는 곳이 있으면 새 값 |
| `docs/decisions/0016-http-insecure-module.md` | 새 ADR |

### 데이터 모델 변경

해당 없음

### 계약 변경

- HTTP SD 응답의 `module` label이 받는 값에 `http_2xx_insecure`를 더한다. label 이름과 개수는 그대로다. `check_type`이 `HTTP`, `HTTPS`인 주소에 쓴다.
- 앱이 읽을 수 있는 series에 `probe_http_status_code{job="manager-blackbox"}`, `probe_duration_seconds{job="manager-blackbox"}`를 더한다. 이름과 label은 바꾸지 않는다고 적는다.

## 위험과 되돌리기

- 운영 서버에 손으로 넣은 수정이 갱신 때 사라져 유효하지 않은 인증서를 쓰는 주소가 다시 실패한다: 요구사항의 배포 순서대로 갱신 직후 관리자 서버가 새 module을 보낸다. 반영되기까지 목록 갱신(15초)과 수집 주기 한 번이 걸리고, 그 전에 장애 기준을 채우면 장애가 한 번 생겼다가 복구된다고 INTEGRATION.md에 적는다.
- 관리자 서버가 새 module을 먼저 보낸다: `MonitoringCheckProbeFailed`가 생긴다. 관리자 서버에서 `http_2xx`로 되돌리거나 모니터링 서버를 갱신한다.
- 되돌리기: 이전 버전으로 되돌리기 전에 관리자 서버가 `http_2xx_insecure`를 보내지 않게 한다. 그 뒤 운영 가이드의 이전 버전으로 되돌리기를 따른다.

### 보안 위협

- 바뀌는 신뢰 경계와 데이터 흐름: 없음. Blackbox Exporter가 감시 대상 HTTPS 서버에 연결하는 흐름에서, 관리자 서버가 고른 주소만 서버 인증서를 검증하지 않는다.
- 잘못될 수 있는 것과 막는 방법:
  - 인증서를 검증하지 않는 설정이 다른 module로 번져 모든 HTTPS 확인이 중간자를 잡지 못한다(CWE-295) → 빌드 검사가 `http_2xx_insecure` 밖의 `insecure_skip_verify`를 거부한다. C2의 사본 시험으로 확인한다.
  - `http_2xx_insecure`가 검증을 하게 바뀌어 관리자 서버가 고른 주소가 다시 실패한다 → 빌드 검사가 `insecure_skip_verify: true`가 없으면 거부한다.
- 받아들이는 위험: `http_2xx_insecure`로 확인하는 주소는 인증서 만료, 위조, 엉뚱한 서버로의 연결을 잡지 못한다(CWE-295). 관리자 서버 쪽 사람이 주소별로 고르는 조건으로 받아들였다(2026-11-25).

## 테스트 계획

완료 기준마다 다음으로 확인한다.

- **C1(빌드 검사)**: `node scripts/build-monitoring-bundle.mjs --check`가 통과한다. 두 module이 인증서 설정만 다른지는 diff 리뷰로 본다.
- **C2(검사를 잠시 망가뜨려 확인)**: 템플릿을 세 가지로 고친다. `http_2xx`에 `insecure_skip_verify: true`를 넣고, `http_2xx_insecure`에서 그 줄을 지우고, `http_2xx_insecure`의 `timeout`을 `3s`로 바꾼다. 하나씩 고칠 때마다 `--check`가 `checkBlackboxConfig`에서 실패하고 module 이름을 알려야 한다. 구현 전 코드에서는 첫 사본으로 지금 검사가 실패하는 것을 본다. 확인 뒤 템플릿을 되돌린다.
- **C3(개발 환경 수동 확인)**: `monitoring-dev.bat start`로 띄운다. 자체 서명 인증서로 HTTPS를 여는 임시 컨테이너(Prometheus 공식 이미지의 `--web.config.file` TLS)를 `monitoring` 네트워크에 띄운다. `curl "http://127.0.0.1:9115/probe?target=https://<임시>:9090/-/ready&module=http_2xx"`는 `probe_success 0`이어야 한다. `module=http_2xx_insecure`는 `1`, 없는 module 이름은 `400`이어야 한다.
- **C4, C5, C7(문서 리뷰)**: 해당 절을 읽고 요구사항 문장과 맞춘다.
- **C6(개발 환경 수동 확인과 문서 리뷰)**: C3의 환경에서 문제 해결 절의 명령(로그 `grep`, `debug=true` 요청, 첫 화면)을 그대로 실행해 결과가 문서와 같은지 본다. 리다이렉트 경고는 리다이렉트하는 주소(임시 Prometheus의 `/`)로 확인한다.
- **C8(전체 검증)**: `node scripts/build-monitoring-bundle.mjs --check`와 `node scripts/check-docs.mjs`가 통과한다.

## 작업 순서

- [x] C2의 첫 사본(`http_2xx`에 설정 넣기)으로 지금 검사가 실패하는 것과, 둘째·셋째 사본은 새 module이 없어 의미가 없다는 것을 확인한다
- [x] `checkBlackboxConfig`를 module 단위 검사로 바꾸고, `blackbox.yml`에 `http_2xx_insecure`를 더한다. C2 사본 세 개가 실패하고 원본이 통과하는 것을 본다
- [x] 개발 환경에서 C3를 확인하고, 같은 환경에서 문제 해결 절에 넣을 명령의 실제 출력을 확인한다
- [x] DEVELOPMENT.md, INTEGRATION.md, OPERATIONS.md를 고친다. ADMIN-SERVER.md는 예시의 `module` 값 밖에 module을 설명하는 곳이 없어 그대로 둔다
- [x] 구현에서 바뀐 점이 있으면 ADR 0016(계획과 함께 씀)에 반영하고 상태를 승인으로 바꾼다
- [ ] 전체 검증, 자체 리뷰, 새 세션 리뷰
  - 멈춘 지점(2026-11-25): `b68976d`에서 전체 검증은 통과했다. 세 번째 독립 리뷰에서, 검사가 주석으로 보는 줄과 YAML이 주석으로 보는 줄이 달라 생기는 사소한 우회가 하나 더 나왔다. 기존 module은 바꾸지 못하고 이름이 다른 module만 하나 더 생길 수 있다. 같은 검사를 세 번 고쳤으므로 AGENTS.md의 세션 규칙에 따라 더 고치지 않고 멈췄다.
  - 다음에 할 일: 사람이 새 세션에서 Blackbox가 실제로 읽은 설정을 꺼내(`/config`) 검사하는 방식으로 바꾸기로 정했다(2026-11-25). 줄 단위로 YAML을 흉내 내는 방식은 해석 차이로 우회가 거듭 나왔기 때문이다. 새 세션은 이 계획의 작업 순서에 그 작업을 더하고, 바꾼 검사를 이번 리뷰가 찾은 경우들로 다시 확인한 뒤 독립 리뷰를 받는다.

## 진행 중 발견

- C2는 `--check` 전체 대신, 빌드 스크립트에서 `checkBlackboxConfig`를 꺼내 템플릿을 펼친 사본과 고친 사본으로 실행하는 임시 도구(저장소 밖)로 확인했다. `--check`는 이미지 빌드 때문에 한 번에 몇 분 걸려서다. 구현 전 코드는 `http_2xx`·`tcp_connect`에 넣은 경우만 실패하고 주석의 언급에도 실패했다. 구현 뒤에는 `http_2xx`·`tcp_connect`에 넣기, `http_2xx_insecure`에서 빼기, `false`로 바꾸기, `timeout` 바꾸기가 모두 module 이름과 함께 실패하고, 원본과 주석의 언급은 통과했다. 원본 템플릿의 통합 확인은 `--check` 전체로 했다.
- 자체 리뷰와 독립 리뷰에서, 처음 구현한 검사가 템플릿에 없는 YAML 표기나 module에 속하지 않는 줄을 버려 인증서 검증 끄기를 놓칠 수 있는 것을 찾았다(CWE-295). 검사를 고쳐, 템플릿이 쓰는 블록 형식의 키와 값 밖의 표기, module에 속하지 않는 줄, 같은 module 이름의 반복, CRLF와 LF 밖의 줄바꿈 문자, 주석이 아닌 줄의 ASCII 밖 글자를 모두 거부하게 했다. 독립 리뷰를 두 번 받아 두 번째 리뷰가 찾은 경우도 막았다. 고치기 전 코드에서 리뷰가 찾은 경우가 통과하고 고친 뒤 실패하는 것을 임시 도구로 확인했다. 공개 저장소라 우회 방법은 적지 않는다.
- 운영 명령 시험 두 개(`설정을 펼치면 검사한 주소와 기본값으로…`, `바꾼 설정 값으로 보관 항목과…`)가 펼친 `blackbox.yml`의 `timeout` 줄을 3개로 세고 있어 4개로 고쳤다.
- Blackbox Exporter v0.28.0에서 실패한 확인은 원인 줄과 `msg="Probe failed"` 줄을 `level=ERROR`로 남긴다. 원인 줄은 HTTP 요청 오류(인증서, 연결)가 `Error for HTTP request`, 2xx가 아닌 응답이 `Invalid HTTP response status code`, 이름 풀이 실패가 `Error resolving address`다. 그래서 운영 가이드는 `grep 'level=ERROR'`로 찾고 원인 줄을 설명한다. 리다이렉트는 `level=WARN` `msg="Received redirect"`다. 2026-11-25에 개발 환경에서 확인했다.
- 응답을 받지 못한 확인(인증서 오류, 이름 풀이 실패)의 `probe_http_status_code`는 0이다. 2026-11-25에 개발 환경에서 확인했다.
- 기간이 지난 자체 서명 인증서는 `x509: certificate has expired or is not yet valid`, 기간 안의 자체 서명 인증서는 `x509: certificate signed by unknown authority`로 `http_2xx`가 실패하고, 두 경우 모두 `http_2xx_insecure`는 성공했다.

## 남길 결정

- 인증서를 검증하지 않는 확인을 배포 전체가 아니라 module 하나로 두고, 관리자 서버가 주소마다 고른다. 빌드 검사의 보안 규칙을 "어디에도 없음"에서 "그 module에만 있음"으로 바꾼다(ADR 0016).
