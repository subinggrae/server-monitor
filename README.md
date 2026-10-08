# Monitoring Manager

Prometheus 기반 모니터링을 웹 화면에서 관리하는 도구다. 운영자가 YAML이나 PromQL을 직접 고치지 않고 가용성 Check, 서버 SNMP Resource, Telegram 알림을 화면에서 설정한다.

## 시작하기

Docker와 Docker Compose v2가 필요하다. 아래 명령은 Bash 기준이다.

```sh
cp .env.example .env
cd infrastructure/secrets
for name in manager_db_password manager_db_root_password app_secret_key \
            telegram_bot_token telegram_webhook_secret alertmanager_webhook_token; do
  cp "$name.example" "$name"
done
cd ../..
docker compose --profile local-db up -d --build
```

복사한 secret 파일에는 실제 값을 넣는다. 각 파일의 용도는 [개발 가이드의 비밀 파일](docs/DEVELOPMENT.md#비밀-파일)에 있다. 실행이 끝나면 http://localhost:8081 에서 화면을 연다.

준비 과정과 전체 명령은 [개발 가이드](docs/DEVELOPMENT.md)에 있다.

## 문서

알고 싶은 것에 따라 다음 문서를 읽는다.

| 알고 싶은 것 | 문서 |
|---|---|
| 시스템 구조와 코드 위치 | [아키텍처](docs/ARCHITECTURE.md) |
| Check, SNMP, 알림의 상세 동작과 운영 절차 | [Check](docs/architecture/checks.md), [SNMP Resource](docs/architecture/snmp-resources.md), [알림](docs/architecture/notifications.md) |
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

담당 팀과 문의 위치는 아직 정하지 않았다. 운영 문의를 받을 팀을 정해야 한다.
