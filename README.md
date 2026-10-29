# Monitoring Manager

Prometheus 기반 감시 설정을 화면에서 관리하는 도구다. 사내 운영 담당자가 서버와 서비스의 가용성 Check, 서버 자원 수집(OS exporter), Telegram 알림을 등록하고, 지금 문제인 Check를 확인한다.

서버의 CPU, Memory, Storage는 대상 서버에 설치한 OS exporter에서 수집한다. 설치 방법은 [운영 가이드의 OS exporter 설치](docs/OPERATIONS.md#os-exporter-설치)에 있다.

시스템은 모니터링 코어(Prometheus, Blackbox Exporter, Alertmanager)와 앱(이 저장소의 Manager API, 화면, DB)으로 나뉜다. 다른 앱도 [통합 계약](docs/INTEGRATION.md)대로 코어에 붙을 수 있다.

## 시작하기

[개발 가이드의 준비](docs/DEVELOPMENT.md#준비)대로 `.env`, `infrastructure/.env`, `infrastructure/secrets/`의 비밀값 파일을 만든다. 그다음 저장소 폴더에서 코어를 먼저 띄우고 앱을 띄운 뒤, 브라우저로 http://localhost:8081 을 연다. 두 번째 명령은 개발용 MariaDB까지 함께 띄운다. Windows가 아니면 첫 명령 대신 `docker compose -f infrastructure/compose.yml up -d --build`를 쓴다.

```sh
core-dev.bat start
docker compose --profile local-db up -d --build
```

준비 과정과 전체 명령은 [개발 가이드](docs/DEVELOPMENT.md)에 있다.

## 운영

코어, 앱, OS exporter를 서버에 설치하는 방법과 포트·방화벽·비밀값 같은 보안 설정은 [운영 가이드](docs/OPERATIONS.md)에 있다.

## 문서

알고 싶은 것에 따라 다음 문서를 읽는다.

| 알고 싶은 것 | 문서 |
|---|---|
| 시스템 구조와 코드 위치 | [아키텍처](docs/ARCHITECTURE.md) |
| 서버 설치, 포트·방화벽·비밀값 설정 | [운영 가이드](docs/OPERATIONS.md) |
| 개발 환경, 명령, 코드 작성 기준, 코드 안의 글, 커밋·리뷰와 반영 절차 | [개발 가이드](docs/DEVELOPMENT.md) |
| 테스트 종류와 작성 기준 | [테스트](docs/TESTING.md) |
| 제품의 대상, 사용 환경, 말투 | [제품](docs/PRODUCT.md) |
| 이 제품의 시각 체계 | [디자인](docs/design/DESIGN.md) |
| 화면 규칙, 점검, 디자인 방향을 정하는 방법 | [디자인 규칙](docs/design/RULES.md), [디자인 점검](docs/design/REVIEW.md), [디자인 방향 정하기](docs/design/DIRECTION.md) |
| AI와 함께 작업하는 방식 | [AI 작업 지침](AGENTS.md) |
| 문서 작성 규칙과 한국어 규칙 | [문서 작성 가이드](docs/WRITING.md) |
| 정한 용어와 표기 | [용어](docs/TERMS.md) |
| 바깥 코드와 에셋의 라이선스 | [라이선스](docs/LICENSES.md) |
| 그림 작성 규칙 | [그림 작성 가이드](docs/DIAGRAMS.md) |
| 기능별 요구사항 | `docs/requirements/` |
| 주요 결정과 이유 | `docs/decisions/` |
