# SNMP Resource 수집

서버의 CPU·Memory·Storage를 Agent 설치 없이 SNMP로 수집하는 구조, Credential 전달 방식, 상태 계산, 서버 등록 절차를 설명한다. 전체 구조와 경계는 [ARCHITECTURE.md](../ARCHITECTURE.md)에 있다.

## Agent 없이 수집하는 이유

고객사 서버나 다른 팀 서버에 Node Exporter 같은 Agent를 설치하려면 승인 절차가 필요하고, 설치 자체가 도입 장벽이 된다. 그래서 모든 수집을 모니터링 서버 쪽에서 대상에 접근해 한다. 대상 서버에는 아무것도 설치하지 않는다.

| 수집 대상 | 방법 |
|---|---|
| 가용성(ICMP, HTTP, HTTPS, TCP) | Blackbox Exporter |
| CPU, Memory, Storage | SNMP Exporter |
| Database | 중앙 DB Exporter 또는 원격 연결. 아직 구현하지 않았다 |

- SNMP는 대상 서버에서 snmpd를 켜고 방화벽을 열어야 한다.
- SNMP로 얻는 정보는 Node Exporter보다 적다. 이 점은 감수한다.
- SNMP v2c만 지원한다. v3는 Credential 모델이 달라 `server_snmp_configs`에 field를 더 넣어야 한다.

결정의 배경과 대안은 [ADR 0009](../decisions/0009-agentless-snmp-resource-monitoring.md)에 있다.

## 네 갈래의 역할

같은 SNMP를 쓰지만 하는 일이 다르다. 섞으면 구조가 무너진다.

| 역할 | 누가 | 무엇을 |
|---|---|---|
| Resource Discovery | `manager-api` | 장비에 직접 SNMP로 물어 무엇이 있는지 확인 |
| 지속적인 metric 수집 | Prometheus → `snmp_exporter` | 주기적으로 CPU·Memory·Storage 수집 |
| Configuration | Manager Database → runtime auth config | 무엇을 어떤 Credential로 수집할지 결정 |
| Target Discovery | Prometheus → Manager HTTP SD | 어떤 장비를 수집할지 결정 |

`snmp_exporter`를 Discovery API처럼 쓰지 않는다. exporter는 Prometheus가 가져갈 metric만 내준다.

## SD 응답

`GET /api/prometheus/sd/snmp`는 SNMP를 켠 Server 가운데 고른 Resource가 하나 이상인 것만 내보낸다. Prometheus는 30초마다 조회한다. community는 응답에 없고 exporter가 찾을 `snmp_auth` 이름만 넘긴다.

```json
[
  {
    "targets": ["10.0.0.11:161"],
    "labels": {
      "server_id": "15",
      "server_name": "WAS-01",
      "environment": "Production",
      "snmp_auth": "manager_server_15",
      "snmp_modules": "system,hrDevice,hrStorage"
    }
  }
]
```

`snmp_auth`와 `snmp_modules`는 exporter 요청 parameter로만 쓰고 relabel 단계에서 지운다. metric label로 남기지 않는다.

## exporter module

module 정의는 저장소에 두지 않는다. `snmp_exporter` image에 들어 있는 기본 `/etc/snmp_exporter/snmp.yml`을 그대로 쓴다. 2MB 크기의 파일을 저장소에 복사해 두면 image 버전과 어긋나기만 한다.

쓰는 module은 세 가지다. 어떤 module을 넘길지는 사용자가 고른 Resource 종류에 따라 Manager가 정한다.

```text
system      sysName, sysDescr, sysUpTime
hrDevice    hrProcessorLoad (CPU)
hrStorage   hrStorageSize / hrStorageUsed / hrStorageAllocationUnits (Memory, Storage)
```

## Credential 전달

`snmp_exporter`는 scrape 요청에서 `target`, `module`, `auth`만 받는다. community는 받지 않고, 자기 config의 `auths`에 미리 있는 이름만 `auth` parameter로 고를 수 있다. 그래서 Manager가 auth만 담긴 파일을 따로 만든다.

```text
Manager Database (community 암호문)
        ↓  복호화
runtime auth config          manager_server_<id>: { community, version }
        ↓  POST /-/reload
snmp_exporter
        ↑  auth=manager_server_<id>
Prometheus  ←  HTTP SD (auth 이름만, community 없음)
```

auth config 파일은 `snmp-runtime` volume의 `/runtime/snmp-auth.yml`이다.

```yaml
auths:
  manager_server_15:
    community: "..."
    version: 2
```

- 내용은 Manager Database의 SNMP 설정에서 만든다. 설정을 저장할 때마다 다시 만들고 `POST /-/reload`로 exporter에 알린다.
- 이 파일을 직접 고치지 않는다. 다음 저장에서 덮어쓴다.
- auth만 담긴 파일을 `--config.file`로 하나 더 주면 exporter가 기본 `snmp.yml`과 합쳐 읽는다.
- 파일은 임시 이름으로 쓴 뒤 옮긴다. exporter가 쓰다 만 파일을 읽으면 안 된다.
- community는 `APP_SECRET_KEY`로 암호화해 저장한다. `APP_SECRET_KEY`를 바꾸면 저장된 community를 읽을 수 없다.

결정의 배경과 대안은 [ADR 0010](../decisions/0010-snmp-credentials-via-exporter-auth-config.md)에 있다.

### auth config 파일 취급 규칙

community 평문이 들어 있어 일반 설정 파일과 다르게 다룬다.

| 규칙 | 내용 |
|---|---|
| Git 제외 | 저장소에 실제 값이 없다 |
| host bind 안 함 | named volume으로만 쓴다 |
| mount 최소화 | `manager-api`(쓰기)와 `snmp-exporter`(읽기)만 본다. Prometheus와 Frontend는 볼 필요가 없다 |
| 파일 권한 600 | 소유자만 읽는다 |
| 로그 출력 금지 | 내용을 로그에 남기지 않는다 |

exporter의 `/config` endpoint는 community를 `<secret>`으로 가리지만, 그래도 host에 port를 열지 않는다.

### 반영 실패 알림

DB 저장과 exporter 반영은 별개다. 저장은 됐는데 반영이 실패하면 수집이 시작되지 않는다. 그래서 `GET /api/servers/{id}/snmp` 응답에 `exporterSynced`를 함께 돌려주고, 화면에서도 "snmp_exporter에 반영되지 않았습니다"라고 알린다.

## Resource 분류 기준

| Resource | 근거 |
|---|---|
| CPU | `hrProcessorLoad` 행 개수. 사용률은 Prometheus에서 processor 평균 |
| Memory | `hrStorageType`이 `hrStorageRam`(`1.3.6.1.2.1.25.2.1.2`)인 행 |
| Storage | `hrStorageType`이 `hrStorageFixedDisk` 또는 `hrStorageNetworkDisk`인 행 |

- `hrStorageTable`에는 memory, swap, buffer, cache, disk가 함께 들어온다. 첫 행을 memory로 보면 안 된다. 반드시 `hrStorageType`으로 가린다.
- 크기는 byte가 아니라 `hrStorageAllocationUnits` 단위다. `hrStorageSize`와 `hrStorageUsed`에 이 값을 곱해야 byte가 된다.
- 장비가 `hrProcessorTable`을 주지 않으면 CPU를 발견하지 못한 것으로 둔다. 값을 만들어 내지 않는다.

## Discovery와 선택

```text
Discover    지금 장비에서 보이는 것. 저장하지 않는다.
Configured  사용자가 모니터링하기로 고른 것. monitored_resources에 저장한다.
```

- 다시 검색해도 기존 선택은 그대로 둔다.
- 장비에서 사라진 Resource는 지우지 않고 값이 없는 `UNAVAILABLE`로 보여 준다.
- Storage의 식별자는 SNMP index가 아니라 장비가 준 `hrStorageDescr`다. index는 재부팅이나 Agent 설정 변경으로 바뀔 수 있다. index는 참고 값으로만 저장한다. ([ADR 0011](../decisions/0011-storage-resource-identity.md))

## 상태 계산

Threshold 기능이 아직 없어 사용률로 위험을 판단하지 않는다.

| 상황 | Server 상태 |
|---|---|
| 서버를 꺼 둠 | `DISABLED` |
| SNMP 미설정 | `UNKNOWN` |
| SNMP는 켰는데 값이 없음 | `UNKNOWN` |
| 값을 읽고 있음 | `OK` |

사용률이 아무리 높아도 `CRITICAL`이 되지 않는다. Threshold를 만들 때 상태 모델을 넓힌다.

## 대상 서버에서 준비할 것

```text
snmpd 실행
read community 설정
UDP 161 방화벽 허용 (snmp-exporter와 manager-api가 있는 호스트에서)
HOST-RESOURCES-MIB 제공
```

`HOST-RESOURCES-MIB`를 제공하지 않는 장비에서는 CPU나 Memory를 찾지 못한다. 그때는 값을 만들어 내지 않고 발견하지 못한 것으로 둔다.

개발 환경에서는 실제 장비 대신 테스트용 SNMP 장비를 쓴다. 실행 명령은 [DEVELOPMENT.md의 명령](../DEVELOPMENT.md#명령)에 있다. `infrastructure/snmp/test-agent/snmpd.conf`의 community는 이 container 전용이다. 실제 장비의 community는 화면에서 입력하고 Manager Database가 암호화해 보관한다.

## 서버 SNMP 수집 등록

서버의 CPU·Memory·Storage를 Prometheus가 수집하고 화면에 현재 값이 보이게 하는 운영 절차다.

- 도구: Monitoring Manager 화면, 대상 서버 shell
- 권한: 대상 서버의 snmpd 설정과 방화벽 변경 권한
- 담당자와 에스컬레이션 경로: 아직 정하지 않았다

1. 대상 서버를 준비한다. 항목은 [대상 서버에서 준비할 것](#대상-서버에서-준비할-것)에 있다.
2. **인프라 > 서버**에서 서버를 등록하거나 고른다.
3. **SNMP** tab에서 버전(v2c), Port(161), read community를 입력하고 **연결 테스트**를 누른다. 장비 이름과 응답 시간이 나오면 저장한다.
4. **Resource** tab에서 **리소스 검색**을 누르고, 모니터링할 CPU·Memory·Storage를 골라 **선택 저장**을 누른다.
5. 30초 정도 지난 뒤 Resource tab에 현재 값이 나오는지 확인한다. SNMP SD 조회 주기가 30초다.

| 증상 | 확인할 것 |
|---|---|
| 연결 테스트 실패 | 대상 서버의 snmpd 실행, community, UDP 161 방화벽을 확인한다 |
| CPU나 Memory가 검색되지 않음 | 장비가 `HOST-RESOURCES-MIB`를 제공하는지 확인한다. 제공하지 않으면 찾을 수 없다 |
| SNMP tab에 "snmp_exporter에 반영되지 않았습니다"가 보임 | 설정은 저장됐지만 exporter에 전달되지 않았다. `snmp-exporter` container가 떠 있는지 확인한 뒤 SNMP 설정을 다시 저장한다 |
| 저장한 Storage가 값 없이 `UNAVAILABLE`로 보임 | 장비에서 해당 Storage가 사라졌거나 설명(`hrStorageDescr`)이 바뀌었다. 다시 검색해서 새 항목을 고른다 |

해결할 수 없을 때 연락할 곳은 아직 정하지 않았다. 담당 팀을 정해야 한다.
