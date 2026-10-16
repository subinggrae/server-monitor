# 0004. 서버 자원은 OS exporter로 수집하고, 서버 생존은 감시 항목이 맡는다

- 상태: 승인
- 날짜: 2026-10-16
- 관련: [요구사항 0006](../requirements/0006-os-exporter.md), [#12](https://github.com/subinggrae/server-monitor/issues/12), [#15](https://github.com/subinggrae/server-monitor/issues/15), [ADR 0002](0002-scrape-interval-via-http-sd.md), 지운 계획은 [PR #17](https://github.com/subinggrae/server-monitor/pull/17)에서 볼 수 있다

## 배경과 문제

서버의 CPU, Memory, Storage를 SNMP로 수집했다. 대상 서버에 snmpd를 켜야 하고, Manager가 community를 암호화해 저장한 뒤 `snmp-runtime` volume에 평문 auth 파일로 써서 snmp_exporter에 넘겼다. Linux Memory에는 cache와 buffer가 들어가 실제보다 높게 보였다. 서버 상태도 "SNMP 값을 읽고 있는가"로 정해, 자원 수집 상태가 서버 생존처럼 보였다.

수집 방식, 수집 설정을 두는 곳, Manager가 exporter를 직접 읽을지를 함께 정해야 했다. 이 결정은 DB 스키마, HTTP SD 계약, Prometheus job, 비밀값 관리에 걸친다.

## 검토한 선택지

- **SNMP를 유지하고 OID를 늘림**: 지금 구조를 그대로 쓴다 / community 저장·전달과 평문 auth 파일이 남고, Linux Memory 계산을 고칠 수 없다.
- **OS exporter(node_exporter, windows_exporter)를 Prometheus가 직접 수집**: 비밀값과 Manager가 쓰는 파일이 없어지고, Memory를 available 기준으로 계산할 수 있다 / 대상 서버마다 exporter를 설치해야 하고, 기본 상태에서 인증 없이 열린다.
- **수집 설정을 `server_targets` 열로 둠**: 테이블이 하나 줄어든다 / 서버 자산 API가 수집 설정을 떠안고, exporter 인증(#15)을 더할 때마다 열이 는다.
- **리소스 찾기를 Prometheus 조회로 함**: Manager가 대상 서버에 요청하지 않는다 / 수집을 켜고 첫 수집이 끝나야 목록이 나온다.

## 결정

서버 자원은 OS exporter로 수집하고 SNMP는 완전히 걷어낸다.

- 수집 설정(사용 여부, 포트, 수집 주기)은 서버당 0~1행의 `server_exporter_configs`에 둔다. 서버에는 OS(`LINUX`, `WINDOWS`)만 더한다.
- Prometheus는 `GET /api/prometheus/sd/os-exporter`로 대상을 받아 `manager-os` job으로 `/metrics`를 수집한다. SD label은 `server_id`, `server_name`, `environment`, `os`, `__scrape_interval__`이다(수집 주기 전달은 ADR 0002와 같다).
- `manager-os` job은 `metric_relabel_configs`의 keep 규칙으로 CPU, Memory, Storage 계산에 쓰는 series만 저장한다. 다른 지표가 필요해지면 그때 keep 목록과 필요한 테이블을 더한다.
- 연결 시험과 리소스 찾기는 Manager가 `http://{host}:{port}/metrics`를 한 번 직접 읽는다. 경로를 고정하고, `GET`만 보내며, 리다이렉트를 따르지 않고, 크기와 시간에 한도를 둔다.
- 서버가 살아 있는지는 Blackbox 감시 항목이 판단한다. 서버 화면은 `up{job="manager-os"}`로 정한 수집 상태만 보인다.
- community 암호화가 없어지므로 암호화 서비스와 `APP_SECRET_KEY`를 지운다.

## 결과

- 좋아지는 점: 비밀값 저장과 Manager가 쓰는 파일이 없어진다. 수집할 수 있는 값이 늘고 Linux Memory가 실제 사용량으로 보인다. 자원 수집 실패가 서버 장애처럼 보이지 않는다.
- 감수할 점:
  - exporter가 인증 없이 평문 HTTP로 열린다. 대상 서버 방화벽으로 막으라고 안내하고, 인증은 #15에서 다룬다.
  - 기존 SNMP 설정과 time series를 잇지 않는다. 배포 뒤 모든 서버가 "설정 안 됨"이 된다.
  - 연결 시험으로 내부 주소의 포트가 열렸는지 알 수 있다. 화면용 API는 `127.0.0.1`에만 묶여 있다.
- 다시 볼 신호: exporter 인증 정보를 저장하게 될 때(#15), Linux·Windows 밖의 OS를 지원할 때

## 확인 방법

- `OsExporterServiceDiscoveryServiceTest`, `ExporterClientTest`, `ServerTargetServiceTest`(수집 상태)
- 코드 검색: SNMP, community, `APP_SECRET_KEY`가 기록 문서 밖에 남지 않는다
- 리뷰에서 확인: `manager-os` job의 `honor_labels`가 기본값(false)이다
