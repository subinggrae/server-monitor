# 0010. SNMP Credential을 Manager가 만든 snmp_exporter auth config로 전달한다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: [ARCHITECTURE.md의 지켜야 할 경계](../ARCHITECTURE.md#지켜야-할-경계)(옛 CLAUDE.md 규칙 11, 14), [snmp-resources.md의 Credential 전달](../architecture/snmp-resources.md#credential-전달), [0009](0009-agentless-snmp-resource-monitoring.md), [0012](0012-secrets-via-docker-secret-config-tree.md)

## 배경과 문제

`snmp_exporter`는 scrape 요청에서 `target`, `module`, `auth`만 받는다. community는 받지 않고, 자기 config의 `auths`에 미리 있는 이름만 고를 수 있다.

community는 운영자가 화면에서 입력하고 Manager Database가 기준이다. community가 Prometheus label이나 SD 응답에 남으면 안 된다.

## 검토한 선택지

- **사용자 설정에 맞춰 `snmp.yml` 전체를 만든다**: exporter 설정이 한 파일에 있다 / image에 들어 있는 module 정의까지 관리하게 된다. 사용자 설정이 배포용 설정에 섞인다. 설정 파일 하나와 관리 범위를 맞바꾼다.
- **auth만 담긴 파일을 Manager가 만들고 exporter가 기본 `snmp.yml`과 합쳐 읽게 한다**: module 정의는 image 기본값 그대로다. Prometheus에는 auth 이름만 넘긴다 / 파일 쓰기와 exporter reload라는 두 번째 반영 단계가 생긴다. DB 저장과 exporter 반영이 따로 실패할 수 있다.

## 결정

auth만 담긴 파일을 Manager가 만들고 exporter가 기본 `snmp.yml`과 합쳐 읽게 한다.

```text
Manager Database (community 암호문)
→ 복호화 → runtime auth config (manager_server_<id>)
→ POST /-/reload → snmp_exporter
Prometheus → HTTP SD (auth 이름만)
```

- community는 `APP_SECRET_KEY`로 암호화해 저장한다.
- auth config는 `snmp-runtime` volume에만 둔다. Git에 없고 host에 bind하지 않는다.
- 파일은 임시 이름으로 쓴 뒤 옮긴다. exporter가 쓰다 만 파일을 읽으면 안 된다.
- `snmp_auth`, `snmp_modules`는 relabel 단계에서 지워 metric label로 남기지 않는다.

## 결과

- 좋아지는 점: community가 Prometheus, SD 응답, API 응답에 나타나지 않는다.
- 감수할 점: 저장은 됐는데 반영이 실패할 수 있다. 그래서 `exporterSynced`를 응답에 넣고 화면에서 알린다.
- 향후 고려사항: `APP_SECRET_KEY`를 바꾸면 저장된 community를 읽을 수 없다.
- 확신도: 높음

## 확인 방법

원래 기록에 없다.
