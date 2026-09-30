# SNMP Exporter

서버의 CPU, Memory, Storage를 Agent 설치 없이 수집합니다.

## module 정의는 이 디렉터리에 없습니다

`snmp_exporter` image에 들어 있는 기본 `/etc/snmp_exporter/snmp.yml`을 그대로 씁니다.
2MB짜리 파일을 저장소에 복사해 두면 image 버전과 어긋나기만 합니다.

쓰는 module은 다음 세 가지입니다.

```text
system      sysName, sysDescr, sysUpTime
hrDevice    hrProcessorLoad (CPU)
hrStorage   hrStorageSize / hrStorageUsed / hrStorageAllocationUnits (Memory, Storage)
```

어떤 module을 넘길지는 사용자가 고른 Resource 종류에 따라 Manager가 정합니다.

## auth config는 Manager가 만듭니다

`snmp_exporter`는 scrape 요청으로 community를 받지 않습니다.
자기 config의 `auths`에 미리 있는 이름만 `auth` parameter로 고를 수 있습니다.

그래서 Manager가 auth만 담긴 파일을 따로 만들어 줍니다.

```text
/runtime/snmp-auth.yml     snmp-runtime volume
```

```yaml
auths:
  manager_server_15:
    community: "..."
    version: 2
```

* 내용은 Manager Database의 SNMP 설정에서 만들어집니다.
* 설정을 저장할 때마다 다시 만들고 `POST /-/reload`로 알립니다.
* 이 파일을 직접 고치지 마세요. 다음 저장에서 덮어씁니다.

## 이 파일을 다루는 규칙

community 평문이 들어 있어 일반 설정 파일과 다르게 취급합니다.

```text
Git 제외                저장소에 실제 값이 없습니다
host bind 안 함          named volume으로만 씁니다
mount 최소화             manager-api(쓰기)와 snmp-exporter(읽기)만 봅니다
파일 권한 600            소유자만 읽습니다
로그 출력 금지            내용을 로그에 남기지 않습니다
```

Prometheus와 Frontend는 이 파일을 볼 필요가 없습니다.

exporter의 `/config` endpoint는 community를 `<secret>`으로 가리지만,
그래도 host에 port를 열지 않습니다.

## 테스트용 SNMP 장비

개발 환경에서 흐름을 확인할 때만 씁니다.

```bash
docker compose --profile snmp-test up -d snmp-test-agent
```

`test-agent/snmpd.conf`의 community는 이 container 전용입니다.
실제 장비의 community는 화면에서 입력하고 Manager Database가 암호화해서 보관합니다.

## 대상 서버에서 준비할 것

```text
snmpd 실행
read community 설정
UDP 161 방화벽 허용 (snmp-exporter가 있는 호스트에서)
HOST-RESOURCES-MIB 제공
```

`HOST-RESOURCES-MIB`를 제공하지 않는 장비에서는 CPU나 Memory를 찾지 못합니다.
그때는 값을 만들어 내지 않고 발견하지 못한 것으로 둡니다.
