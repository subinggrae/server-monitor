# 모니터링 시스템

서버와 서비스가 살아 있는지 확인하고, 서버의 CPU, Memory, Storage 사용량을 모아, 장애를 관리자 서버에 알리는 모니터링 시스템이다. Prometheus, Blackbox Exporter, Alertmanager로 이루어지고, 사내 서버에 컨테이너로 설치한다.

무엇을 감시할지와 장애를 누구에게 어떻게 알릴지는 모니터링 시스템에 붙는 관리자 서버가 정한다. 관리자 서버는 이 저장소에 없고, [통합 계약](docs/INTEGRATION.md)대로 따로 만든다. 서버 사용량은 대상 서버에 설치한 OS exporter(node_exporter, windows_exporter)에서 모은다.

## 시작하기

[개발 가이드의 준비](docs/DEVELOPMENT.md#준비)대로 `infrastructure/.env`와 `infrastructure/secrets/alertmanager_webhook_token`을 만든다. 그다음 Windows 빌드 PC의 저장소 폴더에서 모니터링 시스템을 띄운다. Windows가 아니면 [운영 가이드의 저장소에서 실행](docs/OPERATIONS.md#저장소에서-실행)대로 띄운다.

```sh
monitoring-dev.bat start
```

내부망 서버로 옮길 묶음은 `monitoring-dev.bat build`로 만든다. 준비 과정과 전체 명령은 [개발 가이드](docs/DEVELOPMENT.md)에 있다.

## 운영

모니터링 시스템과 OS exporter를 서버에 설치하는 방법과 포트·방화벽·비밀값 같은 보안 설정은 [운영 가이드](docs/OPERATIONS.md)에 있다. 모니터링 시스템에 붙일 관리자 서버를 만들 때는 [관리자 서버 만들기](docs/ADMIN-SERVER.md)를 읽는다.

## 문서

알고 싶은 것에 따라 다음 문서를 읽는다.

| 알고 싶은 것 | 문서 |
|---|---|
| 시스템 구조와 코드 위치 | [아키텍처](docs/ARCHITECTURE.md) |
| 서버 설치, 포트·방화벽·비밀값 설정 | [운영 가이드](docs/OPERATIONS.md) |
| 모니터링 시스템과 관리자 서버 사이의 약속 | [통합 계약](docs/INTEGRATION.md) |
| 관리자 서버가 만들 기능과 DB에 둘 데이터 | [관리자 서버 만들기](docs/ADMIN-SERVER.md) |
| 개발 환경, 명령, 코드 작성 기준, 코드 안의 글, 커밋·리뷰와 반영 절차 | [개발 가이드](docs/DEVELOPMENT.md) |
| 테스트 종류와 작성 기준 | [테스트](docs/TESTING.md) |
| 제품의 대상, 사용 환경, 말투 | [제품](docs/PRODUCT.md) |
| AI와 함께 작업하는 방식 | [AI 작업 지침](AGENTS.md) |
| 문서 작성 규칙과 한국어 규칙 | [문서 작성 가이드](docs/WRITING.md) |
| 정한 용어와 표기 | [용어](docs/TERMS.md) |
| 바깥 코드와 에셋의 라이선스 | [라이선스](docs/LICENSES.md) |
| 그림 작성 규칙 | [그림 작성 가이드](docs/DIAGRAMS.md) |
| 기능별 요구사항 | `docs/requirements/` |
| 주요 결정과 이유 | `docs/decisions/` |
