# 0019. 코어가 미리 만든 monitoring 네트워크를 쓰게 함 구현 계획

- 요구사항: [0019. 코어가 미리 만든 monitoring 네트워크를 쓰게 함](0019-core-external-network.md)
- 상태: 승인

## 접근 방법

코어 compose 파일의 네트워크를 `external: true`로 바꾸고, 운영 명령 `start`가 띄우기 전에 `docker network inspect monitoring`으로 네트워크가 있는지 본다. 없으면 만드는 명령을 알리고 멈춘다. 빌드 PC의 `core-dev.bat start`는 같은 확인 뒤 없으면 `docker network create monitoring`을 실행한다. 운영 가이드의 처음 설치 절차에 네트워크를 만드는 단계를 더한다.

검토했지만 고르지 않은 방법은 다음과 같다.

- 운영 명령이 네트워크를 만듦: 운영자 할 일이 하나 줄지만, 서버를 바꾸는 일은 가이드로 안내하고 운영 명령은 최소로 둔다는 방침과 어긋난다(사람 확인: 2026-11-16).
- 네트워크 확인을 `status`, `render`에도 넣음: 컨테이너를 띄우는 것은 `start`뿐이라 그곳에서만 막으면 된다. 운영 명령 없이 Compose로 띄우면 Compose가 같은 오류로 멈춘다(S7).

## 바뀌는 부분

| 위치 | 변경 |
|---|---|
| `infrastructure/compose.yml` | `networks.monitoring`에 `external: true`를 더하고, 머리 주석을 운영자가 만든 네트워크에 붙는다고 고친다 |
| `infrastructure/core-server.sh` | `check_network`를 더해 `cmd_start`에서 `check_docker` 다음에 부른다 |
| `infrastructure/tests/core-server.test.sh` | 가짜 docker가 `network inspect`에 답하게 하고, 네트워크가 없을 때 `start`가 멈추는 시험과 있을 때 띄우는 시험을 더한다 |
| `core-dev.bat` | `start`에서 네트워크가 없으면 만든다 |
| `docs/OPERATIONS.md` | 처음 설치에 네트워크 만들기 단계, 저장소에서 실행, 문제 해결 표, 바꾸지 않는 값 |
| `docs/INTEGRATION.md`, `docs/ADMIN-SERVER.md`, `docs/ARCHITECTURE.md` | 운영자가 네트워크를 만들고 코어와 관리자 서버가 모두 붙는다는 설명, 띄우는 순서 |
| `docs/DEVELOPMENT.md` | 개발용 코어 실행 설명 |

### 데이터 모델 변경

해당 없음

### 계약 변경

코어와 관리자 서버의 배포 약속이 바뀐다. 네트워크 이름과 API 계약은 그대로이고, 네트워크를 만드는 쪽이 코어에서 운영자로 바뀐다. 관리자 서버는 지금도 `external: true`라 고칠 것이 없다.

## 위험과 되돌리기

- 이미 설치한 서버에서 갱신 뒤 코어가 뜨지 않음: 이 변경 전 코어가 만든 네트워크는 그대로 남아 있어 그대로 쓴다(S4). 그 네트워크를 지운 서버는 `start`가 만드는 명령을 알린다.
- Compose가 이전에 자기가 만든 네트워크(프로젝트 label이 붙음)를 external로 쓸 때 경고나 오류를 냄: 개발 환경에서 이 변경 전 코어가 만든 네트워크로 확인한다(C4).
- 되돌리기: 커밋을 되돌리고 묶음을 다시 만든다. 네트워크는 남아 있어도 이전 코어가 그대로 쓴다.

### 보안 위협

해당 없음. 네트워크에 붙을 수 있는 주체(그 서버의 Docker를 쓸 수 있는 사람)와 네트워크 옵션은 바뀌지 않는다. 운영 명령은 고정된 이름만 확인하고 외부 입력을 받지 않는다.

## 테스트 계획

| 완료 기준 | 테스트 | 실행 명령과 기대 결과 |
|---|---|---|
| C1 | 설정 확인과 수동 확인 | `docker compose config`에 `external: true`가 있고, 코어를 띄우고 내린 뒤에도 네트워크가 남음 |
| C2 | 운영 명령 시험(네트워크가 없으면 compose를 부르지 않고 안내, 있으면 띄움) | `core-dev.bat check` 통과(구현 전에는 새 시험이 실패) |
| C3 | 수동 확인 | 네트워크를 지운 빌드 PC에서 `core-dev.bat start` 뒤 네트워크가 생기고 코어가 뜸 |
| C4 | 수동 확인 | 이 변경 전 코어가 만든 네트워크에 먼저 붙인 컨테이너와 새 코어가 서로 이름으로 닿음 |
| C5 | 리뷰, 문서 검사 | `node scripts/check-docs.mjs` 오류 0 |
| C6 | 코어 검사 | `core-dev.bat check` 통과 |

## 작업 순서

- [ ] 운영 명령 시험을 더하고 지금 운영 명령에서 실패하는지 확인한다
- [ ] compose 파일, 운영 명령, `core-dev.bat`을 고치고 `core-dev.bat check`로 확인한다
- [ ] 문서를 고친다
- [ ] C1, C3, C4를 수동 확인하고 진행 중 발견에 적는다
- [ ] 전체 검증을 실행하고 새 하위 에이전트에게 리뷰를 맡긴다
- [ ] 요구사항을 구현됨으로 바꾸고 이 계획을 지운다

## 진행 중 발견

없음

## 남길 결정

없음. 네트워크를 만드는 쪽은 운영 가이드와 통합 계약에 적고, 커밋을 되돌리면 이전 동작으로 돌아간다.
