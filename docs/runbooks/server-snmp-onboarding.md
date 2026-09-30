# 서버 SNMP 수집 등록

## Runbook 정보

| 항목 | 내용 |
|---|---|
| Runbook ID | `RUN-002` |
| 목적 / 기대 결과 | 서버의 CPU / Memory / Storage를 Prometheus가 수집하고 화면에 현재 값이 보이는 상태 |
| 사용 도구 | Monitoring Manager 화면, 대상 서버 shell |
| 필요한 권한 | 대상 서버의 snmpd 설정과 방화벽 변경 권한 |
| 담당자 | 미정 |
| 마지막 수정일 | 2026-09-28 |
| 에스컬레이션 | 미정 |

## 절차

1. 대상 서버를 준비합니다.
   필요한 항목은 [infrastructure/snmp/README.md](../../infrastructure/snmp/README.md)의 "대상 서버에서 준비할 것"에 있습니다.
2. **인프라 > 서버**에서 서버를 등록하거나 고릅니다.
3. **SNMP** tab에서 버전(v2c), Port(161), read community를 입력하고 **연결 테스트**를 누릅니다.
   장비 이름과 응답 시간이 나오면 저장합니다.
4. **Resource** tab에서 **리소스 검색**을 누르고, 모니터링할 CPU / Memory / Storage를 골라 **선택 저장**을 누릅니다.
5. 30초 정도 지난 뒤 Resource tab에 현재 값이 나오는지 확인합니다.
   SNMP Service Discovery 주기가 30초입니다.

## 오류 처리

- 연결 테스트 실패 → 대상 서버의 snmpd 실행, community, UDP 161 방화벽을 확인합니다.
- CPU나 Memory가 검색되지 않음 → 장비가 `HOST-RESOURCES-MIB`를 제공하는지 확인합니다. 제공하지 않으면 찾을 수 없습니다.
- SNMP tab에 "snmp_exporter에 반영되지 않았습니다"가 보임 → 설정은 저장됐지만 exporter에 전달되지 않았습니다.
  `snmp-exporter` container가 떠 있는지 확인한 뒤 SNMP 설정을 다시 저장합니다.
- 저장한 Storage가 값 없이 `UNAVAILABLE`로 보임 → 장비에서 해당 Storage가 사라졌거나 설명(`hrStorageDescr`)이 바뀌었습니다. 다시 검색해서 새 항목을 고릅니다.
- 해결할 수 없는 경우 → 미정
