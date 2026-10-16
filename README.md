# Monitoring Manager

Prometheus 기반 감시 설정을 화면에서 관리하는 도구다. 사내 운영 담당자가 서버와 서비스의 가용성 Check, 서버 자원 수집(OS exporter), Telegram 알림을 등록하고, 지금 문제인 Check를 확인한다.

서버의 CPU, Memory, Storage는 대상 서버에 설치한 OS exporter에서 수집한다. Linux는 [node_exporter](https://github.com/prometheus/node_exporter)(기본 포트 9100, v1.12.1에서 확인), Windows는 [windows_exporter](https://github.com/prometheus-community/windows_exporter)(기본 포트 9182, 공식 문서의 metric 이름 기준)를 설치한다. exporter는 인증 없이 열리므로 대상 서버의 방화벽에서 그 포트를 Prometheus와 Monitoring Manager에만 열어 둔다.

## 시작하기

[개발 가이드의 준비](docs/DEVELOPMENT.md#준비)대로 `.env`와 `infrastructure/secrets/`의 비밀값 파일을 만든 뒤, 저장소 폴더에서 다음을 실행하고 브라우저로 http://localhost:8081 을 연다. 이 명령은 개발용 MariaDB까지 함께 띄운다.

```sh
docker compose --profile local-db up -d --build
```

준비 과정과 전체 명령은 [개발 가이드](docs/DEVELOPMENT.md)에 있다.

## 문서

알고 싶은 것에 따라 다음 문서를 읽는다.

| 알고 싶은 것 | 문서 |
|---|---|
| 시스템 구조와 코드 위치 | [아키텍처](docs/ARCHITECTURE.md) |
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
