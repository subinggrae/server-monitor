# 0016. 인증서를 검증하지 않는 확인은 module 하나로 두고 주소마다 고른다

- 상태: 승인
- 날짜: 2026-11-25
- 관련: [요구사항 0025](../requirements/0025-http-insecure-module.md), 지운 계획은 [PR #70](https://github.com/subinggrae/server-monitor/pull/70)에서 볼 수 있다, [INTEGRATION.md](../INTEGRATION.md)

## 배경과 문제

관리자 서버에 등록한 HTTPS 주소 가운데 일부는 유효한 인증서가 없는 감시 대상 서버의 Docker nginx로 바로 가고, 관리자 서버 쪽은 그 nginx에 인증서를 두지 않기로 했다(2026-11-25). 그런 주소는 Blackbox Exporter의 HTTPS 확인이 인증서 검증에서 늘 실패해 장애로 기록된다.

지금까지 빌드 검사는 펼친 Blackbox 설정 어디에든 `insecure_skip_verify`가 있으면 실패했다. 모든 HTTPS 확인이 서버 인증서를 검증해야 한다는 규칙이다. 이 규칙으로는 그런 주소를 감시할 수 없다.

## 검토한 선택지

- **`.env` 옵션으로 `http_2xx`의 검증을 끔**: 설정 값 하나로 끝난다 / 그 모니터링 서버의 모든 HTTPS 주소가 검증 없이 확인된다. 관리자 서버 쪽 사람이 버렸다(2026-11-25).
- **주소마다 CA 인증서 파일을 줌**: 검증을 유지한다 / 대상 nginx에 유효한 인증서가 없어 쓸 수 없다.
- **인증서를 검증하지 않는 module을 하나 더 두고 관리자 서버가 주소마다 고름**: 검증을 끄는 범위가 관리자 서버가 고른 주소로 좁다. 다른 module은 검증을 유지한다 / 관리자 서버가 위험을 알고 골라야 한다.

## 결정

세 번째 선택지를 고른다(사람 확인: 2026-11-25). `http_2xx`와 같고 `http.tls_config.insecure_skip_verify: true`만 다른 `http_2xx_insecure` module을 늘 둔다. `.env`로 켜고 끄지 않는다. 관리자 서버가 HTTP SD의 `module` label로 주소마다 고르고, `check_type`은 바꾸지 않는다. `check_type`이 `HTTP`인 주소에도 쓸 수 있다.

빌드 검사의 보안 규칙은 "`insecure_skip_verify`가 `http_2xx_insecure`에만 `true`로 있고 다른 module에는 없음"으로 바꾼다. 기본 HTTPS 확인(`http_2xx`)과 다른 module은 서버 인증서를 계속 검증한다.

## 결과

- 좋아지는 점: 인증서를 둘 수 없는 주소도 감시할 수 있고, 손으로 고친 운영 서버 설정이 필요 없다. 검증을 끄는 범위를 빌드 검사가 지킨다.
- 감수할 점:
  - `http_2xx_insecure`로 확인하는 주소는 인증서 만료, 위조한 인증서, 엉뚱한 서버로의 연결(중간자)을 잡지 못한다(CWE-295). 그 주소가 가리키는 서버가 바뀌어도 응답이 2xx면 성공이다.
  - 관리자 서버는 이 module이 있는 모니터링 시스템에만 새 값을 보내야 한다. 갱신하지 않은 모니터링 서버는 모르는 module이라 거부하고 `MonitoringCheckProbeFailed`가 생긴다.
- 다시 볼 신호: 대상 서버에 사내 CA 인증서를 둘 수 있게 되면, 검증을 끄는 대신 CA 인증서 파일을 주는 방법을 다시 본다.

## 확인 방법

- `node scripts/build-monitoring-bundle.mjs --check`의 Blackbox 설정 검사(`checkBlackboxConfig`). 펼친 파일의 글자가 아니라 Blackbox Exporter가 읽어 들인 설정(`/config`)을 검사한다. 거부해야 하는 사본(`http_2xx`에 설정을 넣거나 `http_2xx_insecure`에서 빼거나 `false`로 바꾸거나 `timeout`을 바꿈)을 이 검사가 거부하는지도 `--check`마다 시험한다
- 리뷰에서 확인: `http_2xx_insecure`와 `http_2xx`가 인증서 설정 밖에서 같은지
