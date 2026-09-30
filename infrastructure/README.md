# infrastructure

모니터링 구성요소의 배포용 설정을 모아 둔 디렉터리입니다.

## 중요한 규칙

이 디렉터리의 파일은 **시스템 초기 배포용 설정**입니다.

사용자가 UI에서 서버, DB, Threshold, Telegram 그룹을 바꿔도
Monitoring Manager는 이 파일들을 수정하지 않습니다.

사용자 설정의 기준은 Manager Database입니다.
자세한 내용은 [CLAUDE.md](../CLAUDE.md)와 [docs/architecture/architecture.md](../docs/architecture/architecture.md)를 참고해 주세요.

## 디렉터리

| 경로 | 설명 | 현재 상태 |
| --- | --- | --- |
| `prometheus/` | Prometheus 설정과 Alert 규칙 | 사용 중 |
| `alertmanager/` | Alertmanager 설정 | 사용 중 |
| `blackbox/` | Blackbox Exporter module 정의 | 사용 중 |
| `secrets/` | Docker Secret 파일 | 사용 중 |
| `snmp/` | SNMP Exporter 설정 | 다음 단계에서 추가 |
| `grafana/` | Grafana provisioning (datasource, dashboard) | 다음 단계에서 추가 |

## secret 파일

### 무엇이 secret인가

노출되면 안 되는 값만 secret으로 둡니다.
비밀번호, Token, 암호화 키가 여기에 해당합니다.

주소, 포트, timezone, DB 이름, 계정 이름은 노출돼도 문제가 없습니다.
그런 값은 `.env`에 둡니다.

### 어떻게 넣는가

```text
운영   Docker Secret     container 안 /run/secrets/<target> 파일로 들어갑니다.
개발   환경변수 fallback   파일이 없으면 .env나 shell 환경변수 값을 씁니다.
```

Spring Boot가 `/run/secrets` 아래 파일을 설정값으로 읽습니다.
파일 이름이 곧 property 이름입니다.
이 동작은 `backend/src/main/resources/application.yml`의 `spring.config.import`가 켭니다.

Config tree는 `application.yml`보다 우선합니다.
그래서 파일이 있으면 파일이 이기고, 없으면 환경변수가 남습니다.

### 파일 목록

`secrets/` 아래 실제 값 파일은 Git에 올리지 않습니다.
처음 설정할 때는 `.example` 파일을 복사해서 값을 넣어 주세요.

```bash
cd infrastructure/secrets
for name in manager_db_password manager_db_root_password app_secret_key \
            telegram_bot_token telegram_webhook_secret alertmanager_webhook_token; do
  cp "$name.example" "$name"
done
```

| 파일 | 쓰는 곳 | container 안 이름 | 설명 |
| --- | --- | --- | --- |
| `manager_db_password` | manager-api, manager-db | `spring.datasource.password` / `manager_db_password` | Manager Database 비밀번호입니다. 두 container가 같은 파일을 씁니다. |
| `manager_db_root_password` | manager-db | `manager_db_root_password` | local-db profile의 DB 관리자 비밀번호입니다. |
| `app_secret_key` | manager-api | `monitoring-manager.secret-key` | 인증 정보를 암호화할 때 쓰는 마스터 키입니다. 바꾸면 기존 값을 읽을 수 없습니다. |
| `telegram_bot_token` | manager-api | `monitoring-manager.telegram.bot-token` | Telegram Bot API 호출에 씁니다. |
| `telegram_webhook_secret` | manager-api | `monitoring-manager.telegram.webhook-secret` | Telegram webhook 요청을 검증합니다. |
| `alertmanager_webhook_token` | manager-api, alertmanager | `monitoring-manager.notification.alertmanager-webhook-token` / `alertmanager_webhook_token` | Alertmanager가 Manager Webhook을 부를 때 쓰는 Bearer token입니다. 두 container가 같은 파일을 씁니다. |

### 여기에 두지 않는 것

Telegram 그룹의 chat id는 secret 파일에 두지 않습니다.
알림 대상은 Manager Database가 관리합니다. 화면에서 등록해 주세요.

Alertmanager는 Telegram Bot Token을 읽지 않습니다.
Telegram 전송은 Monitoring Manager가 맡습니다.
