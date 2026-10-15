# 0006. 서버 자원 수집을 SNMP에서 OS exporter로 바꾸기

- 상태: 승인
- 작성일: 2026-10-15
- 관련: [#12](https://github.com/subinggrae/server-monitor/issues/12), [0001](0001-per-target-scrape-interval.md), [#6](https://github.com/subinggrae/server-monitor/issues/6), [#14](https://github.com/subinggrae/server-monitor/issues/14), [#13](https://github.com/subinggrae/server-monitor/issues/13), [#15](https://github.com/subinggrae/server-monitor/issues/15)

## 배경

지금은 서버의 CPU, Memory, Storage를 SNMP로 수집한다. 대상 서버에 snmpd를 켜야 하고, Manager는 community를 암호화해 저장한 뒤 `snmp-runtime` volume에 평문 auth 파일로 써서 snmp_exporter에 넘긴다. 수집할 수 있는 값도 적다. Linux에서는 RAM 사용량에 cache와 buffer가 들어가 실제보다 높게 보이고, CPU는 1분 평균 정수만 받는다.

서버 상태, 리소스 수집, 수집 주기(0001)가 모두 SNMP 설정에 매달려 있다. SNMP 설정이 없으면 서버 상태를 알 수 없고 수집 주기도 정할 수 없다.

## 목표

- 서버의 CPU, Memory, Storage를 OS exporter로 수집한다. OS exporter는 Linux의 [node_exporter](https://github.com/prometheus/node_exporter)(기본 포트 9100)와 Windows의 [windows_exporter](https://github.com/prometheus-community/windows_exporter)(기본 포트 9182)를 말한다.
- 두 exporter의 값을 OS와 관계없는 공통 이름(사용률 %, 크기, 사용량)으로 맞춘다. 화면과 이후의 알림 규칙(#14)은 공통 이름만 쓴다.
- 서버마다 exporter 수집을 켜고 끄고, 포트와 수집 주기(10~240초)를 화면에서 정한다. 다시 띄우지 않아도 반영된다.
- SNMP 수집과 그 설정, community 저장, snmp_exporter와 테스트 장비를 없앤다.
- Linux의 Memory 사용률은 cache와 buffer를 뺀 실제 사용량(available 기준)으로 보인다.

## 하지 않는 것

- exporter를 대상 서버에 설치하거나 업데이트하는 것. 설치 안내 링크만 보인다.
- CPU, Memory, Storage 밖의 지표(네트워크, 프로세스, 서비스 상태 등)를 화면에 보이는 것. exporter가 내보내는 다른 값은 Prometheus와 Grafana에서 볼 수 있다.
- 사용률 알림. #14(요구사항 0004)에서 이 작업의 공통 이름을 써서 만든다.
- 수집 실패 알림. #6에서 이 작업의 수집 대상을 기준으로 다시 정한다.
- 기존 SNMP time series를 새 이름으로 이어 붙이는 것. 과거 값은 Prometheus 보존 기간(15일) 동안만 남는다.
- 대상 서버의 snmpd를 끄거나 방화벽을 정리하는 것. 운영 절차로 안내한다.
- exporter 접근에 인증(TLS, basic auth)을 거는 것. 별도 작업으로 넘겼다([#15](https://github.com/subinggrae/server-monitor/issues/15), 사람 확인: 2026-10-15).
- 기존 SNMP 설정, 수집 주기, 리소스 선택을 새 수집으로 옮기는 것. 모두 지우고 새로 설정한다(사람 확인: 2026-10-15).

## 사용자 경험

1. 사용자가 서버를 등록할 때 OS(Linux, Windows)를 고른다. 포트 기본값과 리소스 찾기 방식이 OS에 따라 다르기 때문이다(사람 확인: 2026-10-15).
2. 서버 상세의 수집 절에서 OS exporter 수집을 켜고, 포트(OS에 따라 9100이나 9182가 미리 들어 있음)와 수집 주기를 정한 뒤 저장한다. 절에는 대상 서버 방화벽에서 Prometheus와 Monitoring Manager만 그 포트에 접근하게 하라는 안내와 exporter 설치 안내 링크가 보인다. [연결 시험]을 누르면 Manager가 그 주소의 exporter `/metrics`를 직접 읽어 닿는지와 exporter 종류·버전을 보인다(사람 확인: 2026-10-15).
3. 수집을 켜면 리소스 절의 [리소스 찾기]가 exporter가 내보내는 CPU, Memory, 디스크(Linux는 mountpoint, Windows는 드라이브) 목록을 보이고, 사용자가 감시할 것을 고른다.
4. 고른 리소스의 사용률, 사용량, 크기, 수집 시각이 표에 보인다.
5. exporter가 없거나 닿지 않는 서버는 서버 목록과 상세에서 "응답 없음"으로 보이고, 자원 감시를 하지 않는다. 가용성 감시 항목(Check)은 지금처럼 동작한다.
6. 이 기능을 배포하면 모든 서버가 수집 꺼짐, 고른 리소스 없음 상태가 된다. 서버 목록 위 알림 띠에 "SNMP 수집을 끝냈습니다. 서버마다 OS exporter를 설치하고 수집을 켜 주세요."가 보인다.

## 동작

### 정상 동작

- **S1. 수집 켜기**
  - 상황: 서버가 등록돼 있고 exporter가 설치돼 있다.
  - 행동: 사용자가 수집을 켜고 포트와 수집 주기를 저장한다.
  - 결과: 설정이 Manager Database에 남고, Prometheus가 다시 띄우지 않고 그 서버의 exporter를 정한 주기로 수집한다.
- **S2. 연결 시험**
  - 상황: 수집 설정을 입력했다.
  - 행동: 사용자가 [연결 시험]을 누른다.
  - 결과: Manager가 exporter의 `/metrics`를 한 번 읽고, 닿았는지, exporter 종류(node_exporter, windows_exporter)와 버전, 고른 OS와 맞는지를 보인다.
- **S3. 리소스 찾기와 고르기**
  - 상황: exporter에 닿는다.
  - 행동: 사용자가 [리소스 찾기]를 누르고 감시할 리소스를 골라 저장한다.
  - 결과: CPU, Memory, 디스크 목록이 보이고, 고른 것만 감시 대상으로 남는다. 디스크의 식별자는 Linux는 mountpoint, Windows는 드라이브 이름이다. 임시 파일 시스템(tmpfs, overlay 등)은 목록에서 뺀다.
- **S4. 사용률 보기**
  - 상황: 고른 리소스가 수집되고 있다.
  - 행동: 사용자가 서버 상세를 연다.
  - 결과: OS와 관계없이 같은 열(사용률, 사용량, 크기, 수집 시각)로 보인다. Linux Memory는 available 기준이다.
- **S5. 수집 주기와 포트 바꾸기**
  - 상황: 수집 중이다.
  - 행동: 사용자가 수집 주기나 포트를 바꾼다.
  - 결과: 다시 띄우지 않고 다음 SD 갱신부터 반영된다(0001과 같은 방식).
- **S6. 기존 SNMP 설정**
  - 상황: 배포 전에 SNMP 설정과 리소스 선택이 있던 서버가 있다.
  - 행동: 기능을 배포한다.
  - 결과: SNMP 설정, community, 수집 주기, 리소스 선택을 모두 지운다. 서버 자체(이름, 호스트, 환경, 설명)는 남고 OS는 Linux로 둔다. 사용자는 OS를 확인하고 수집을 새로 설정한다(사람 확인: 2026-10-15).

### 예외 동작

- **S7. exporter에 닿지 않음**
  - 상황: 수집을 켰는데 exporter가 없거나 포트가 막혀 있다.
  - 행동: 연결 시험을 하거나 수집 주기가 지난다.
  - 결과: 연결 시험은 닿지 못한 이유(연결 거부, 시간 초과, HTTP 오류)와 확인할 것을 보인다. 서버 상태는 "응답 없음"이다. 설정은 저장된 채로 둔다.
- **S8. OS가 다름**
  - 상황: Windows로 등록한 서버의 포트에서 node_exporter가 응답한다.
  - 행동: 연결 시험을 한다.
  - 결과: 고른 OS와 exporter가 다르다고 알리고, 서버의 OS를 바꾸라고 안내한다. 저장은 막지 않는다.
- **S9. 허용하지 않는 값**
  - 상황: 포트가 1~65535 밖이거나 수집 주기가 10~240초 정수가 아니다.
  - 행동: 저장한다.
  - 결과: 400과 칸별 오류. 기존 값은 바뀌지 않는다.
- **S10. 응답이 너무 크거나 이상함**
  - 상황: exporter 주소에서 아주 큰 응답이나 Prometheus 형식이 아닌 응답이 온다.
  - 행동: 연결 시험이나 리소스 찾기를 한다.
  - 결과: 정해 둔 크기와 시간을 넘으면 읽기를 멈추고 "exporter 응답을 읽지 못했습니다"를 보인다.

### 보안

- 접근 권한: 지금과 같다. 화면용 API에는 인증이 없고 포트를 `127.0.0.1`에만 묶는다.
- 외부 입력: 화면용 API의 OS, 포트, 수집 주기, 리소스 선택. exporter의 `/metrics` 응답(신뢰할 수 없는 입력, 지금의 "장비의 SNMP 응답"을 대신함)
- 민감한 정보: SNMP community를 더는 저장하지 않는다. 배포할 때 DB의 `community_cipher`와 `snmp-runtime` volume의 평문 auth 파일을 지운다(volume 삭제는 배포 절차에 쓴다). exporter는 인증 없이 열리므로 대상 서버의 방화벽에서 Prometheus와 Monitoring Manager만 접근하게 하라고 화면과 문서에 안내한다. 인증은 [#15](https://github.com/subinggrae/server-monitor/issues/15)에서 다룬다.
- 서버 쪽 요청 위조: 연결 시험과 리소스 찾기는 Manager가 사용자가 입력한 호스트와 포트로 HTTP 요청을 보낸다. 지금 SNMP 연결 시험과 같은 성격이다. 경로는 `/metrics`로 고정하고, 응답 크기와 시간에 한도를 두며, 응답 본문을 화면에 그대로 보이지 않는다.
- 남길 보안 이벤트: 없음

### 바뀌지 않는 것

- 서버 등록·수정·삭제 화면과 API(OS 선택을 더하는 것 밖에는)
- 가용성 감시 항목(Check), Blackbox 수집, `manager-blackbox` job, 감시 항목의 HTTP SD와 그 label
- 알림 경로(Alertmanager, Telegram)와 Alert 규칙
- 수집 주기의 범위(10~240초)와 전달 방식(`__scrape_interval__`)

## 완료 기준

- [ ] C1. 서버의 OS와 수집 설정(사용 여부, 포트, 수집 주기)을 저장하면 조회 API와 화면에 보이고, 허용하지 않는 값은 400과 칸별 오류를 돌려준다. (S1, S5, S9) — 확인: 단위 테스트, 컴포넌트 테스트
- [ ] C2. 새 HTTP SD 응답에 수집을 켠 서버만 `host:port`와 `server_id`, `server_name`, `environment`, `os`, `__scrape_interval__` label로 들어간다. (S1, S5) — 확인: 단위 테스트
- [ ] C3. node_exporter와 windows_exporter의 `/metrics` 표본으로 연결 시험이 exporter 종류와 버전을 알아내고, OS가 다르면 알린다. 크기·시간 한도를 넘거나 형식이 아닌 응답은 실패로 끝낸다. (S2, S7, S8, S10) — 확인: 단위 테스트(표본 파일)
- [ ] C4. 두 exporter의 표본으로 리소스 찾기가 CPU, Memory, 디스크 목록을 만들고 임시 파일 시스템을 뺀다. (S3) — 확인: 단위 테스트(표본 파일)
- [ ] C5. 공통 recording rule이 두 exporter의 값을 같은 이름과 단위로 만든다. Linux Memory는 available 기준이다. (S4) — 확인: `promtool test rules`
- [ ] C6. migration 뒤 SNMP 설정, community, 리소스 선택이 사라지고, 서버는 남아 OS가 Linux이며 수집이 꺼져 있다. MariaDB와 PostgreSQL에서 같다. (S6) — 확인: 두 DB에 migration 적용 후 수동 조회
- [ ] C7. `docker compose`로 띄운 환경에서 node_exporter 테스트 컨테이너를 서버로 등록하고 수집을 켜면, 다시 띄우지 않고 사용률이 화면에 보인다. (S1, S3, S4) — 확인: 수동 확인
- [ ] C8. 저장소에 SNMP 수집 코드, 설정, 컨테이너, 의존성(snmp4j)이 남지 않고, 문서가 새 구조를 설명한다. — 확인: 코드 검색, 문서 검사

## 확인 필요

없음

정한 것(사람 확인: 2026-10-15):

- 개발 환경은 `snmp-test-agent` 대신 `os-test` profile의 node_exporter 테스트 컨테이너를 둔다. windows_exporter는 공식 저장소의 `/metrics` 표본으로 단위 테스트와 규칙 테스트를 하고, 실제 Windows 서버 확인은 사람이 할 수 있을 때 한다.
- 화면 재정비(0005)를 브랜드 모양 없이 먼저 병합하고, 이 작업은 그 화면 틀 위에서 만든다. 브랜드 모양은 [#13](https://github.com/subinggrae/server-monitor/issues/13)에 남는다.
