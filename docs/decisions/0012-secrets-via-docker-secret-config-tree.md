# 0012. Secret을 Docker Secret과 Spring config tree로 넣는다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md 규칙 11, 11-1), [DEVELOPMENT.md의 비밀 파일](../DEVELOPMENT.md#비밀-파일), [0010](0010-snmp-credentials-via-exporter-auth-config.md)

## 배경과 문제

Manager는 DB 비밀번호, Telegram Bot Token, `APP_SECRET_KEY` 같은 secret이 필요하다. secret이 `.env`나 container 환경 변수에 평문으로 남으면 `docker inspect` 등으로 노출된다. 개발 환경에서는 Docker 없이 실행할 수도 있어야 한다.

## 검토한 선택지

- **모든 값을 환경 변수로 넣는다**: 단순하다 / secret이 container 환경 변수에 그대로 보인다. 단순함과 노출 위험을 맞바꾼다.
- **secret 파일을 읽는 코드를 직접 만든다**: 읽는 방식을 마음대로 정할 수 있다 / Spring Boot가 이미 제공하는 기능을 다시 만든다. 불필요한 코드가 늘어난다.
- **Docker Secret 파일을 Spring Boot config tree로 읽는다**: 파일 이름이 곧 property 이름이다. 읽는 코드가 필요 없다 / 환경 변수 우선순위를 알고 있어야 한다.

## 결정

Docker Secret 파일을 Spring Boot config tree로 읽는다.

```text
운영   Docker Secret       /run/secrets/<property 이름> 파일
개발   환경 변수 fallback   파일이 없을 때만 쓴다
```

`spring.config.import: optional:configtree:/run/secrets/`로 읽는다. config tree가 `application.yml`보다 우선하는 것을 Docker에서 직접 확인했다.

## 결과

- 좋아지는 점: container 환경 변수에 secret 값이 없다. 경로만 있다.
- 감수할 점: OS 환경 변수는 config tree보다 우선한다. 그래서 compose는 `SPRING_DATASOURCE_PASSWORD`를 넘기지 않는다. 빈 값이라도 넘기면 파일을 덮어쓴다.
- 향후 고려사항: 새 secret은 `infrastructure/secrets/`에 `.example`과 함께 추가하고 compose의 `secrets`에 target 이름을 property 이름으로 적는다.
- 확신도: 높음
