# 0007. 모니터링 코어와 앱을 통합 계약으로 나눈다

- 상태: 승인
- 날짜: 2026-10-19
- 관련: [요구사항 0008](../requirements/0008-embeddable-core.md), [ADR 0002](0002-scrape-interval-via-http-sd.md), [ADR 0006](0006-baseline-migration.md), [#15](https://github.com/subinggrae/server-monitor/issues/15)

## 배경과 문제

Prometheus, Blackbox Exporter, Alertmanager(코어)를 따로 띄우고, 다른 앱이 이 저장소의 백엔드·화면·DB 대신 자기 앱을 붙일 수 있어야 한다. 지금은 코어와 앱이 `compose.yml` 하나에 있고, 앱 주소 `manager-api:8080`이 Prometheus와 Alertmanager 설정에 박혀 있다. 코어와 앱 사이의 약속은 코드와 ARCHITECTURE.md에 흩어져 있다. 도입하는 앱은 테이블과 열 이름도 자기 용어로 바꿔야 한다.

## 검토한 선택지

앱 주소를 코어에 넘기는 방법:

- **앱이 정해진 Docker 네트워크 별칭으로 붙음**: 코어 설정이 바뀌지 않는다 / 앱 주소와 포트를 바꾸려면 코어 설정 파일을 고쳐야 한다.
- **Compose `configs.content`에 설정을 넣고 Compose 변수로 치환**: 별도 스크립트가 없다 / 설정이 compose 파일 안으로 들어가 `promtool`로 파일을 검사하기 어렵다.
- **환경 변수 하나를 시작 스크립트가 설정 템플릿에 펼침**: 주소를 한곳에서 바꾸고, 펼친 설정을 그대로 검사할 수 있다 / 시작 스크립트가 하나 생긴다.

테이블·열 이름을 두는 방법:

- **Java 상수와 Flyway placeholder**: 이름의 원본이 파일 하나다 / migration이 placeholder로 가득 차 테이블 모양을 읽기 어렵다.
- **Entity 어노테이션과 기준 migration**: 이름이 두 곳에 있지만 둘 다 읽기 쉽다 / 둘이 어긋날 수 있다. `ddl-auto: validate`와 통합 테스트가 잡는다.

## 결정

- 코어는 `infrastructure/compose.yml`로 따로 띄우고 Docker 네트워크 `monitoring`을 만든다. 앱은 그 네트워크에 붙는다. 코어와 앱은 같은 호스트에 둔다.
- 코어가 부르는 앱 주소는 코어 설정의 `MONITORING_APP_ADDRESS`(`host:port`) 하나로 정한다. Prometheus와 Alertmanager가 시작할 때 `infrastructure/render-config.sh`가 설정 템플릿에 펼친다. 공격자가 이 값을 넣을 경로가 없으므로 형식을 검사하지 않는다. webhook token은 비밀값 파일로 받고 펼치지 않는다.
- 코어와 앱 사이의 약속(HTTP SD 두 곳, Alertmanager webhook, 앱이 읽는 series)은 `docs/INTEGRATION.md` 하나에 둔다. 코어는 앱의 metric을 수집하지 않는다.
- 테이블과 열 이름은 JPA Entity 어노테이션과 기준 migration에만 둔다. 앱은 네이티브 SQL을 쓰지 않는다.

## 결과

- 좋아지는 점: 코어만 띄울 수 있고, 다른 앱은 통합 계약 문서만 보고 붙는다. 앱 주소를 바꿀 때 코어 설정 한 값만 고친다.
- 감수할 점: 코어를 먼저 띄워야 앱을 띄울 수 있다. 코어와 앱을 다른 호스트에 두려면 SD와 webhook에 TLS가 필요하다(#15).
- 다시 볼 신호: 코어와 앱을 다른 호스트에 둘 때, 앱이 네이티브 SQL이 필요해질 때

## 확인 방법

- `promtool check config`, `amtool check-config`로 펼친 설정을 검사한다(DEVELOPMENT.md의 명령).
- `DatabaseNameLocationTest`: 네이티브 SQL이 없고 테이블 이름이 Entity 밖에 없다.
- `SchemaMigrationIntegrationTest`: Entity와 기준 migration이 두 DB에서 맞는다.
- 리뷰에서 확인: 코어 설정 파일에 앱 주소가 박혀 있지 않다.
