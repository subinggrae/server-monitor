# 0019. 모니터링 시스템이 운영자가 미리 만든 monitoring 네트워크를 쓰게 함 구현 계획

- 요구사항: [0019. 모니터링 시스템이 운영자가 미리 만든 monitoring 네트워크를 쓰게 함](0019-monitoring-external-network.md)
- 상태: 진행 중

## 접근 방법

모니터링 시스템 compose 파일의 네트워크를 `external: true`로 바꾸고, 운영 명령 `start`가 띄우기 전에 `docker network inspect monitoring`으로 네트워크가 있는지 본다. 없으면 만드는 명령을 알리고 멈춘다. 빌드 PC의 `monitoring-dev.bat start`는 같은 확인 뒤 없으면 `docker network create monitoring`을 실행한다. 운영 가이드의 처음 설치 절차에 네트워크를 만드는 단계를 더한다.

검토했지만 고르지 않은 방법은 다음과 같다.

- 운영 명령이 네트워크를 만듦: 운영자 할 일이 하나 줄지만, 서버를 바꾸는 일은 가이드로 안내하고 운영 명령은 최소로 둔다는 방침과 어긋난다(사람 확인: 2026-11-16).
- 네트워크 확인을 `status`, `render`에도 넣음: 컨테이너를 띄우는 것은 `start`뿐이라 그곳에서만 막으면 된다. 운영 명령 없이 Compose로 띄우면 Compose가 같은 오류로 멈춘다(S7).

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/compose.yml` | `networks.monitoring`에 `external: true`를 더하고, 머리 주석을 운영자가 만든 네트워크에 붙는다고 고친다 |
| `infrastructure/monitoring-server.sh` | `check_network`를 더해 `cmd_start`에서 `check_docker` 다음에 부른다 |
| `infrastructure/tests/monitoring-server.test.sh` | 가짜 docker가 `network inspect`에 답하게 하고, 네트워크가 없을 때 `start`가 멈추는 시험과 있을 때 띄우는 시험을 더한다 |
| `monitoring-dev.bat` | `start`에서 네트워크가 없으면 만든다 |
| `docs/OPERATIONS.md` | 처음 설치에 네트워크 만들기 단계, 저장소에서 실행, 문제 해결 표, 바꾸지 않는 값 |
| `docs/INTEGRATION.md`, `docs/ADMIN-SERVER.md`, `docs/ARCHITECTURE.md` | 운영자가 네트워크를 만들고 모니터링 시스템과 관리자 서버가 모두 붙는다는 설명, 띄우는 순서 |
| `docs/DEVELOPMENT.md` | 개발용 모니터링 시스템 실행 설명 |

### 데이터 모델 변경

해당 없음

### 계약 변경

모니터링 시스템과 관리자 서버의 배포 약속이 바뀐다. 네트워크 이름과 API 계약은 그대로이고, 네트워크를 만드는 쪽이 모니터링 시스템에서 운영자로 바뀐다. 관리자 서버는 지금도 `external: true`라 고칠 것이 없다.

## 위험과 되돌리기

- 이미 설치한 서버에서 갱신 뒤 모니터링 시스템이 뜨지 않음: 이 변경 전 모니터링 시스템이 만든 네트워크는 그대로 남아 있어 그대로 쓴다(S4). 그 네트워크를 지운 서버는 `start`가 만드는 명령을 알린다.
- Compose가 이전에 자기가 만든 네트워크(프로젝트 label이 붙음)를 external로 쓸 때 경고나 오류를 냄: 개발 환경에서 이 변경 전 모니터링 시스템이 만든 네트워크로 확인한다(C4).
- 되돌리기: 커밋을 되돌리고 묶음을 다시 만든다. 네트워크는 남아 있어도 이전 모니터링 시스템이 그대로 쓴다.

### 보안 위협

해당 없음. 네트워크에 붙을 수 있는 주체(그 서버의 Docker를 쓸 수 있는 사람)와 네트워크 옵션은 바뀌지 않는다. 운영 명령은 고정된 이름만 확인하고 외부 입력을 받지 않는다.

## 테스트 계획

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | 설정 확인과 수동 확인 | `docker compose config`에 `external: true`가 있고, 모니터링 시스템을 띄우고 내린 뒤에도 네트워크가 남음 |
| C2 | 운영 명령 시험(네트워크가 없으면 compose를 부르지 않고 안내, 있으면 띄움) | `monitoring-dev.bat check` 통과(구현 전에는 새 시험이 실패) |
| C3 | 수동 확인 | 네트워크를 지운 빌드 PC에서 `monitoring-dev.bat start` 뒤 네트워크가 생기고 모니터링 시스템이 뜸 |
| C4 | 수동 확인 | 이 변경 전 모니터링 시스템이 만든 네트워크에 먼저 붙인 컨테이너와 새 모니터링 시스템이 서로 이름으로 닿음 |
| C5 | 리뷰, 문서 검사 | `node scripts/check-docs.mjs` 오류 0 |
| C6 | 모니터링 시스템 검사 | `monitoring-dev.bat check` 통과 |

## 작업 순서

- [x] 운영 명령 시험을 더하고 지금 운영 명령에서 실패하는지 확인한다
- [x] compose 파일, 운영 명령, `monitoring-dev.bat`을 고치고 `monitoring-dev.bat check`로 확인한다
- [x] 문서를 고친다
- [x] C1, C3, C4를 수동 확인하고 진행 중 발견에 적는다
- [ ] 전체 검증을 실행하고 새 하위 에이전트에게 리뷰를 맡긴다
- [ ] 요구사항을 구현됨으로 바꾸고 이 계획을 지운다

## 진행 중 발견

- 요구사항 0020(이름 변경) 병합 뒤 이 브랜치를 리베이스하고, 요구사항과 계획의 파일 이름과 글을 새 이름으로 고쳤다. 요구사항 S3에 적었던 `stop` 작업은 운영 명령에 없어 `docker compose down`으로 바로잡았다.
- C2(`24029bd`): 새 운영 명령 시험 세 개(네트워크가 없으면 멈추고 안내, 떠 있던 모니터링 시스템을 다시 만들지 않음, 있으면 확인 뒤 띄움)는 구현 전 운영 명령에서 모두 실패했고(통과 172, 실패 3), 구현 뒤 통과 175, 실패 0이었다.
- C1(`c1c98d0`): `docker compose config`에 `external: true`가 있다. `monitoring-dev.bat stop` 뒤에도 네트워크와 거기 붙은 다른 컨테이너가 남았다.
- C4(`c1c98d0`): `main`의 compose 파일로 띄워 네트워크를 이 변경 전 방식(compose 프로젝트 label이 붙음)으로 만들고, 가짜 관리자 서버(`manager-api`)를 붙인 뒤 모니터링 시스템만 내렸다. 그 상태에서 새 방식으로 띄우자 네트워크 경고 없이 그 네트워크를 썼고, 가짜 서버가 `http://prometheus:9090/-/ready`에 닿았으며, 모니터링 시스템이 가짜 서버에서 감시 대상 두 개를 받았다.
- C3(`c1c98d0`): 네트워크를 지운 빌드 PC에서 `monitoring-dev.bat start`가 네트워크를 만들고 세 컨테이너를 붙였다. `stop` 뒤에도 네트워크가 남았다.
- S7: 네트워크가 없을 때 Compose의 오류는 `network monitoring declared as external, but could not be found`이고 컨테이너를 만들지 않았다. 운영 가이드 문제 해결 표에 이 문구를 적었다.
- `monitoring-dev.bat`을 편집 도구로 고치자 넣은 줄만 LF가 되어 cmd가 파일을 잘못 읽었다. 파일 전체를 CRLF로 맞춰 고쳤다(`.gitattributes`의 `*.bat eol=crlf`).
- ADR 0007의 결과에 "코어를 먼저 띄워야 앱을 띄울 수 있다"는 감수할 점이 있는데 이 변경으로 맞지 않게 된다. ADR은 승인 뒤 고치지 않으므로 그대로 두고, 이 요구사항이 그 제약을 없앴다고 요구사항에 적는다.

## 남길 결정

[ADR 0014](../decisions/0014-operator-creates-monitoring-network.md)에 남겼다. 리뷰에서 ADR 0007의 결정("코어가 네트워크를 만든다")과 감수할 점("코어를 먼저 띄워야 한다")이 이 변경과 어긋난다는 지적이 있어, ADR 0011처럼 그 문장만 대신하는 새 ADR을 쓰기로 했다(사람 확인: 2026-11-16).
