# 0025. 인증서를 검증하지 않는 HTTP 확인 module

- 상태: 승인
- 작성일: 2026-11-25
- 관련: [0012](0012-check-label-continuity.md), [0013](0013-core-env-settings.md), [0024](0024-consecutive-failure-down.md), [INTEGRATION.md](../INTEGRATION.md)

## 배경

관리자 서버에 등록한 HTTPS 주소 가운데 일부는 내부 DNS가 중간 장비를 거치지 않고 감시 대상 서버의 Docker nginx로 바로 간다. 관리자 서버 쪽 사람은 이 nginx에 유효한 인증서를 두지 않기로 했고, 앞으로도 두지 않는다(사람 확인: 2026-11-25, 관리자 서버 Testigo_Mng). 그래서 Blackbox Exporter의 HTTPS 확인이 `x509: certificate has expired or is not yet valid`로 계속 실패하고, 장애(`MonitoringCheckDown`)로 기록된다.

지금 Blackbox module은 `icmp`, `tcp_connect`, `http_2xx` 셋이고 모두 인증서를 검증한다. 빌드 검사(`node scripts/build-monitoring-bundle.mjs --check`)는 펼친 Blackbox 설정 어디에든 `insecure_skip_verify`가 있으면 실패한다. 그래서 주소마다 인증서 검증을 끌 방법이 없다.

운영 서버에서는 임시로 펼친 `rendered/blackbox.yml`의 `http_2xx`에 인증서 검증을 끄는 설정을 손으로 넣고 Blackbox Exporter를 다시 띄워 두었다. 이 수정은 운영 명령이 설정을 다시 펼치는 다음 `start`, 갱신, 되돌리기 때 사라진다. 그동안은 그 서버의 모든 HTTPS 주소가 인증서 검증 없이 확인된다.

관리자 서버는 주소의 실패 원인을 화면에 보이려고 Prometheus에서 `probe_http_status_code`와 `probe_duration_seconds`를 읽을 예정이다. 지금은 Blackbox가 내는 지표를 모두 저장하지만, 통합 계약([INTEGRATION.md](../INTEGRATION.md))에서 앱이 읽는 series로 적은 것은 `probe_success`뿐이다. 그래서 나중에 저장 대상을 줄이면 두 지표가 빠질 수 있다.

운영자가 확인이 왜 실패했는지 보는 방법은 운영 가이드에 없다. Alert에는 실패 원인이 들어가지 않는다.

## 목표

- 관리자 서버가 HTTP·HTTPS 주소마다 인증서를 검증하지 않고 확인할지 고른다(사람 확인: 2026-11-25). 주소 감시 대상 목록(HTTP SD)의 `module` label에 `http_2xx_insecure`를 주면 그 주소는 인증서를 검증하지 않고 확인한다. `check_type`은 `HTTPS` 그대로다. `check_type`이 `HTTP`인 주소에도 쓸 수 있고, 그 주소가 HTTPS로 리다이렉트하면 리다이렉트한 곳의 인증서를 검증하지 않는다(사람 확인: 2026-11-25). 모니터링 시스템은 `module`과 `check_type`의 짝을 검사하지 않는다.
- `http_2xx_insecure`의 확인은 인증서를 검증하지 않는 것만 빼고 `http_2xx`와 같다. 확인 제한 시간도 다른 module과 같은 값을 쓴다.
- 다른 module(`http_2xx`, `icmp`, `tcp_connect`)은 지금처럼 인증서를 검증한다. 인증서를 검증하지 않는 설정이 `http_2xx_insecure` 밖에 들어가면 빌드 검사가 실패한다.
- 관리자 서버를 만드는 개발자가 [INTEGRATION.md](../INTEGRATION.md)에서 새 module 값, 이 module이 잡지 못하는 문제, 배포 순서를 찾는다.
- 관리자 서버가 읽는 지표로 `probe_http_status_code`와 `probe_duration_seconds`(job `manager-blackbox`)가 통합 계약에 적혀, 이 둘을 저장 대상에서 빼지 않는다.
- 운영자가 운영 가이드의 문제 해결 절을 보고 주소 확인이 실패한 원인을 Blackbox Exporter의 로그와 확인 결과에서 찾는다.

## 하지 않는 것

- 인증서를 검증하지 않는 확인을 `.env` 옵션으로 켜고 끄는 것. 배포 전체의 HTTPS 확인을 끄는 안은 관리자 서버 쪽 사람이 버렸다(사람 확인: 2026-11-25). 새 module은 늘 있다.
- 주소마다 다른 CA 인증서 파일이나 클라이언트 인증서를 주는 것
- 인증서 만료일을 따로 감시하거나 알리는 것
- HTTP SD label을 더하거나, 지우거나, 이름을 바꾸는 것. `module` label이 받는 값만 하나 는다.
- Blackbox가 내는 지표의 저장 설정(`metric_relabel_configs` 등)을 바꾸는 것. 지금 모두 저장하므로 계약에 적기만 한다.
- 운영 서버에 손으로 넣은 수정을 되돌리는 절차를 따로 두는 것. 새 버전으로 갱신하면 운영 명령이 설정을 다시 펼쳐 수정이 사라진다.
- 관리자 서버 저장소(Testigo_Mng)를 고치는 것

## 사용자 경험

관리자 서버를 만드는 개발자, 모니터링 시스템을 설치하는 운영자, 알림을 받는 담당자 관점이다.

배포는 다음 순서로 한다.

1. 운영자가 새 버전의 묶음을 받아 모든 모니터링 서버를 [새 버전으로 갱신](../OPERATIONS.md#새-버전으로-갱신)한다. 갱신하면 운영 명령이 설정을 다시 펼치므로 손으로 넣은 수정이 사라지고, 유효한 인증서가 없는 주소는 다시 실패한다.
2. 관리자 서버 개발자가 인증서를 검증하지 않을 주소의 `module`을 `http_2xx_insecure`로 보내는 관리자 서버를 배포한다. 이 module이 없는 모니터링 서버에 보내면 Blackbox Exporter가 모르는 module이라 거부하고, 그 주소에 `MonitoringCheckProbeFailed`가 생긴다. 그래서 1을 모두 마친 뒤에 한다.
3. Prometheus가 다음 목록 갱신(15초마다) 때 바뀐 `module`을 받고, 그 뒤 수집 주기 한 번 안에 새 module로 확인한다. 그 주소의 확인이 성공하면 장애가 다음 규칙 평가에서 복구된다. 1과 2 사이가 짧을수록 장애가 생길 가능성이 줄어든다. 반영되기 전에 실패가 장애 기준을 채우면 장애가 한 번 생겼다가 복구된다.

`module`을 바꾸면 그 주소의 기록(series)은 새로 시작하지만, 장애는 확인 방식을 바꿀 때처럼 감시 항목과 주소의 id로 이어진다([0012](0012-check-label-continuity.md), [0024](0024-consecutive-failure-down.md)의 S6).

운영자는 주소 확인이 실패하면 운영 가이드의 문제 해결 절에서 Blackbox Exporter 로그의 오류 줄, 확인 결과를 자세히 보는 요청, Blackbox Exporter 첫 화면의 최근 확인 목록으로 원인을 본다.

## 동작

### 정상 동작

- **S1. 인증서를 검증하지 않는 확인**
  - 상황: 인증서가 만료됐거나, 아직 유효하지 않거나, 믿을 수 있는 기관이 발급하지 않은 HTTPS 주소가 있다.
  - 행동: 관리자 서버가 그 주소를 `module: http_2xx_insecure`로 내준다.
  - 결과: 인증서와 관계없이 응답이 2xx면 성공이다(리다이렉트는 따라간다). 장애가 아니다.
- **S2. 인증서를 검증하는 확인**
  - 상황: S1과 같은 주소가 있다.
  - 행동: 관리자 서버가 그 주소를 `module: http_2xx`로 내준다.
  - 결과: 지금처럼 인증서 검증에 실패해 확인이 실패한다.
- **S3. 확인 제한 시간**
  - 상황: 운영자가 `.env`의 확인 제한 시간을 1~9초 가운데 하나로 두거나 비워 둔다.
  - 행동: 운영 명령이 설정을 펼친다.
  - 결과: `http_2xx_insecure`도 다른 module과 같은 제한 시간을 쓴다.
- **S4. module을 바꿈**
  - 상황: 인증서 검증에 실패해 장애가 이어지는 주소가 있다.
  - 행동: 관리자 서버가 그 주소의 `module`을 `http_2xx`에서 `http_2xx_insecure`로 바꿔 내준다.
  - 결과: 새 module의 확인이 성공한 뒤 첫 규칙 평가에서 장애가 복구된다. 반대로 `http_2xx`로 되돌려 인증서 검증에 실패하면, 확인 방식을 바꿀 때처럼 실패를 이어서 세어 장애가 된다.
- **S5. 실패 원인 보기**
  - 상황: 주소 확인이 실패한다.
  - 행동: 운영자가 운영 가이드의 문제 해결 절대로 Blackbox Exporter 로그, 확인 결과를 자세히 보는 요청, 첫 화면의 최근 확인 목록을 본다.
  - 결과: 인증서 오류, 연결 실패, 응답 코드 같은 실패 원인이 보인다. 리다이렉트를 따라갔다는 경고 줄은 실패가 아니라는 것도 안다.

### 예외 동작

- **S6. module이 없는 모니터링 서버**
  - 상황: 새 버전으로 갱신하지 않은 모니터링 서버가 있다.
  - 행동: 관리자 서버가 `module: http_2xx_insecure`를 내준다.
  - 결과: Blackbox Exporter가 모르는 module이라 거부하고, 그 주소에 `MonitoringCheckProbeFailed`가 생긴다. 지금과 같은 동작이고, 배포 순서로 막는다.
- **S7. 다른 module에 인증서 검증을 끄는 설정**
  - 상황: 개발자가 `http_2xx_insecure` 밖의 module에 인증서 검증을 끄는 설정을 넣는다.
  - 행동: 빌드 검사를 실행한다.
  - 결과: 검사가 이미지 묶음을 만들기 전에 실패하고, 어느 module에 설정이 있는지 알려 준다.
- **S8. 인증서를 검증하지 않는 module이 검증함**
  - 상황: 개발자가 `http_2xx_insecure`에서 인증서 검증을 끄는 설정을 지우거나 `false`로 바꾼다.
  - 행동: 빌드 검사를 실행한다.
  - 결과: 검사가 실패한다.

### 보안

- 접근 권한: 바뀌지 않는다. 어느 주소의 인증서를 검증하지 않을지는 감시 대상을 정하는 관리자 서버가 정한다.
- 외부 입력: 관리자 서버의 HTTP SD 응답에 든 `module` 값. Blackbox Exporter가 설정에 없는 이름을 거부한다(지금과 같음).
- 민감한 정보: 없음
- 남길 보안 이벤트: 없음
- 받아들이는 위험: `http_2xx_insecure`로 확인하는 주소는 인증서 만료, 위조한 인증서, 엉뚱한 서버로의 연결(중간자)을 잡지 못한다(CWE-295). 관리자 서버 쪽 사람이 주소별로 고르는 조건으로 받아들였다(사람 확인: 2026-11-25). 다른 module은 검증을 유지한다.

### 바뀌지 않는 것

- `icmp`, `tcp_connect`, `http_2xx`의 설정과 확인 결과
- HTTP SD 응답의 label 이름과 개수, `check_type` 값(`ICMP`, `TCP`, `HTTP`, `HTTPS`)
- Alert 규칙, Alert label과 annotation, 장애 판정 기준
- 확인 제한 시간과 `.env`의 설정 값 목록
- Blackbox가 내는 지표를 모두 저장하는 것

## 완료 기준

- [ ] C1. `infrastructure/blackbox/blackbox.yml`에 `http_2xx_insecure`가 있고, `http_2xx`와 다른 점은 인증서 검증을 끄는 설정뿐이다. 값 세 벌로 펼친 설정이 Blackbox 설정 검사를 통과하고, 네 module의 `timeout`이 모두 확인 제한 시간이다. (S1, S3) — 확인: `node scripts/build-monitoring-bundle.mjs --check`, diff 리뷰
- [ ] C2. 빌드 검사는 인증서 검증을 끄는 설정이 `http_2xx_insecure` 밖의 module에 있거나, `http_2xx_insecure`에 없으면 실패한다. module 하나라도 `timeout`이 확인 제한 시간이 아니면 실패한다. (S7, S8) — 확인: 펼친 설정을 고친 사본 세 개(`http_2xx`에 설정 넣기, `http_2xx_insecure`에서 설정 빼기, `http_2xx_insecure`의 `timeout` 바꾸기)로 Blackbox 설정 검사 함수를 실행해 실패를 본다
- [ ] C3. 개발 환경에서 자체 서명 인증서를 쓰는 HTTPS 주소를 확인하면 `http_2xx`는 `probe_success` 0, `http_2xx_insecure`는 1이다. 유효하지 않은 module 이름은 지금처럼 거부된다. (S1, S2, S6) — 확인: 개발 환경 수동 확인(Blackbox Exporter의 `/probe`)
- [ ] C4. [INTEGRATION.md](../INTEGRATION.md)의 `module` 값 목록과 확인 방식 표에 `http_2xx_insecure`가 있고, `HTTP`와 `HTTPS` 주소에 쓸 수 있다는 것, 이 module이 잡지 못하는 것, 배포 순서(모니터링 시스템 갱신 → 관리자 서버가 새 값을 보냄), 갱신하지 않은 서버에 보내면 `MonitoringCheckProbeFailed`가 생긴다는 것이 적혀 있다. (목표, S6) — 확인: 문서 리뷰
- [ ] C5. [INTEGRATION.md](../INTEGRATION.md)의 읽을 수 있는 series에 `probe_http_status_code`와 `probe_duration_seconds`(job `manager-blackbox`)가 있다. (목표) — 확인: 문서 리뷰
- [ ] C6. [OPERATIONS.md](../OPERATIONS.md)에 `http_2xx_insecure`로 확인하는 주소는 인증서 만료, 위조, 엉뚱한 서버로의 연결을 잡지 못한다는 것이 있다. 문제 해결 절에 로그의 오류 줄을 찾는 명령, 확인 결과를 자세히 보는 요청, 첫 화면의 최근 확인 목록, 리다이렉트 경고가 실패가 아니라는 것이 있다. 적은 명령은 v0.28.0에서 실행해 결과를 확인했다. (S5) — 확인: 문서 리뷰, 개발 환경에서 명령 실행
- [ ] C7. [DEVELOPMENT.md](../DEVELOPMENT.md)의 Blackbox 설정 검사 설명이 C2의 검사와 같다. 인증서를 검증하지 않는 module을 두는 결정과 이유가 ADR에 있다. (목표) — 확인: 문서 리뷰
- [ ] C8. `node scripts/build-monitoring-bundle.mjs --check`와 `node scripts/check-docs.mjs`가 통과한다. — 확인: 명령 실행

## 확인 필요

없음
