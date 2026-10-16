# 0005. 서버 자원 사용률은 OS 공통 recording rule로 만들고 화면과 알림이 함께 읽는다

- 상태: 승인
- 날짜: 2026-10-16
- 관련: [요구사항 0006](../requirements/0006-os-exporter.md), [ADR 0004](0004-os-exporter-collection.md), [#14](https://github.com/subinggrae/server-monitor/issues/14), 지운 계획은 [PR #17](https://github.com/subinggrae/server-monitor/pull/17)에서 볼 수 있다

## 배경과 문제

node_exporter와 windows_exporter는 같은 값도 metric 이름, label, 계산식이 다르다. 예를 들어 CPU는 `node_cpu_seconds_total{cpu, mode}`와 `windows_cpu_time_total{core, mode}`이고, 디스크 식별자는 Linux의 mountpoint와 Windows의 드라이브 이름이다. 서버 화면과 이후의 사용률 알림(#14)이 같은 값을 써야 하는데, OS별 계산식을 여러 곳에 두면 서로 어긋날 수 있다.

## 검토한 선택지

- **화면용 PromQL을 Java에 두고 알림 규칙에 따로 씀**: 규칙 파일을 읽지 않아도 된다 / OS별 계산식이 Java와 규칙 파일 두 곳에 생겨 화면 값과 알림 기준이 어긋날 수 있다.
- **OS별 원본 metric을 화면과 알림이 각자 OS에 맞춰 조회**: 규칙이 필요 없다 / 사용처마다 OS별로 나눠 조회해야 한다.
- **recording rule 한 파일에서 공통 이름을 만듦**: OS별 계산이 한 곳에만 있고 `promtool`로 시험할 수 있다 / 규칙 평가 주기만큼 값이 늦을 수 있고, 제외할 파일 시스템 목록이 규칙과 Java(리소스 찾기) 두 곳에 생긴다.

## 결정

`infrastructure/prometheus/rules/server-resources.yml`에서 다음 세 이름을 만든다. 화면과 알림은 이 세 이름과 `up{job="manager-os"}`만 읽는다.

| 이름 | 단위 |
|---|---|
| `manager:resource_usage_percent` | 0~100 |
| `manager:resource_used_bytes` | byte |
| `manager:resource_size_bytes` | byte |

- label은 `server_id`, `resource_type`(`CPU`, `MEMORY`, `STORAGE`), `resource_key`뿐이다. `max by`로 끝내 SD label(`server_name`, `os` 등)을 남기지 않는다.
- CPU는 idle 카운터의 `irate(...[10m])`로 계산한다. 서버마다 수집 주기(10~240초)가 달라도 직전 두 수집 사이의 평균이 된다.
- Memory 사용량은 Total − Available이다(Linux는 cache와 buffer를 뺀 값).
- Storage 사용량은 size − avail이다. Linux는 임시 파일 시스템(tmpfs, overlay 등)을 빼고, Windows는 드라이브 이름(`C:` 꼴)만 받는다.
- 제외 목록은 Java 리소스 찾기에도 있으므로, Java 테스트가 규칙 파일을 읽어 두 목록이 같은지 확인한다.

사용률 알림(#14)의 ADR 0003 초안(미병합)이 정한 "계산식은 `SnmpMetricQueryService`와 같다"를 이 결정이 대신한다.

## 결과

- 좋아지는 점: OS별 계산이 한 파일에만 있고, 화면 값과 알림 기준이 같다. 이름과 계산을 `promtool test rules`로 고정한다.
- 감수할 점:
  - exporter 버전이 metric 이름을 바꾸면 규칙을 고쳐야 한다. 확인한 최소 버전을 문서에 적는다.
  - ext4 예약 블록 때문에 Storage 사용률이 `df`보다 조금 높을 수 있고, Windows CPU는 작업 관리자 값과 조금 다를 수 있다.
- 다시 볼 신호: CPU, Memory, Storage 밖의 지표를 화면에 보일 때, 제외 목록을 자주 바꾸게 될 때

## 확인 방법

- `infrastructure/prometheus/rules/server-resources.test.yml`(`promtool test rules`)
- `FilesystemFilterRuleSyncTest`: Java 제외 목록과 규칙 파일이 같다
- 리뷰에서 확인: 화면과 Alert 규칙이 OS별 원본 metric을 직접 읽지 않는다
