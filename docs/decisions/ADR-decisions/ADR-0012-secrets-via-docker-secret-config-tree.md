# ADR-0012: Secret을 Docker Secret과 Spring config tree로 넣는다

- 상태: Accepted
- 날짜: 2026-09-28
- 대체 대상: 없음
- 대체됨: 없음

## Context

Manager는 DB 비밀번호, Telegram Bot Token, `APP_SECRET_KEY` 같은 secret이 필요합니다.
secret이 `.env`나 container 환경변수에 평문으로 남으면 `docker inspect` 등으로 노출됩니다.
개발 환경에서는 Docker 없이 실행할 수도 있어야 합니다.

## Options Considered

### Option 1: 모든 값을 환경변수로 넣는다

- 장점: 단순합니다.
- 단점: secret이 container 환경변수에 그대로 보입니다.
- 주요 trade-off: 단순함과 노출 위험을 맞바꿉니다.

### Option 2: secret 파일을 읽는 코드를 직접 만든다

- 장점: 읽는 방식을 마음대로 정할 수 있습니다.
- 단점: Spring Boot가 이미 제공하는 기능을 다시 만듭니다.
- 주요 trade-off: 불필요한 코드가 늘어납니다.

### Option 3: Docker Secret 파일을 Spring Boot config tree로 읽는다

- 장점: 파일 이름이 곧 property 이름입니다. 읽는 코드가 필요 없습니다.
- 단점: 환경변수 우선순위를 알고 있어야 합니다.
- 주요 trade-off: 설정 우선순위에 대한 주의가 필요합니다.

## Decision

Option 3을 선택합니다.

```text
운영   Docker Secret     /run/secrets/<property 이름> 파일
개발   환경변수 fallback   파일이 없을 때만 씁니다
```

`spring.config.import: optional:configtree:/run/secrets/`로 읽습니다.
Config tree가 `application.yml`보다 우선하는 것을 Docker에서 직접 확인했습니다.

## Consequences

- 긍정적인 영향: container 환경변수에 secret 값이 없습니다. 경로만 있습니다.
- 부정적인 영향 / 감수하는 trade-off: OS 환경변수는 config tree보다 우선합니다.
  그래서 compose는 `SPRING_DATASOURCE_PASSWORD`를 넘기지 않습니다. 빈 값이라도 넘기면 파일을 덮어씁니다.
- 향후 고려사항: 새 secret은 `infrastructure/secrets/`에 `.example`과 함께 추가하고 compose의 `secrets`에 target 이름을 property 이름으로 적습니다.

## Confidence

High

## References

- 관련 요구사항: [CLAUDE.md](../../../CLAUDE.md) 규칙 11, 11-1
- 관련 설계 문서: [infrastructure/README.md](../../../infrastructure/README.md)
- 관련 ADR: [ADR-0010](ADR-0010-snmp-credentials-via-exporter-auth-config.md)
