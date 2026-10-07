# 0004. HTTP 성공 조건과 Timeout을 Check마다 받지 않는다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md "외부 시스템의 제약을 코드로 숨기지 않는다"), [checks.md의 Blackbox module 제약](../architecture/checks.md#blackbox-module-제약)

## 배경과 문제

운영자는 Check마다 기대 응답 코드나 Timeout을 다르게 주고 싶을 수 있다.

Blackbox Exporter v0.28.0의 `/probe`는 `target`, `module`, `debug`, `hostname`만 받는다. `valid_status_codes`나 `timeout`을 query parameter로 보내도 무시하는 것을 직접 확인했다. 응답 코드 조건과 Timeout은 `blackbox.yml`의 module 정의에만 있다.

## 검토한 선택지

- **사용자 설정에 맞춰 `blackbox.yml`을 만들어 낸다**: Check마다 조건을 다르게 줄 수 있다 / 사용자 설정 때문에 배포용 설정 파일을 바꾸게 된다. 기능 범위와 설정 파일 관리 원칙을 맞바꾼다.
- **지원 범위를 줄이고 고정 module을 쓴다**: 배포용 설정을 건드리지 않는다. 동작하지 않는 입력 칸이 없다 / Check마다 조건을 바꿀 수 없다. 유연성을 포기한다.

## 결정

지원 범위를 줄이고 고정 module을 쓴다.

- HTTP·HTTPS는 2xx 응답만 성공으로 본다(`http_2xx`).
- Timeout은 module에 적힌 5초를 모든 Check가 함께 쓴다.
- Expected Status와 Timeout은 API와 화면에서 뺀다.

## 결과

- 좋아지는 점: 사용자가 입력한 값이 무시되는 일이 없다.
- 감수할 점: 3xx나 4xx를 정상으로 봐야 하는 대상은 지원하지 않는다.
- 향후 고려사항: 필요해지면 `http_2xx_or_3xx`, `http_any` 같은 module을 `blackbox.yml`에 미리 두고 Check가 고르게 할 수 있다. 임의의 숫자를 넣는 방식은 이 구조에서 지원할 수 없다.
- 확신도: 높음. Blackbox Exporter 버전을 올리면 query parameter 동작을 다시 확인해야 한다.

## 확인 방법

원래 기록에 없다.
