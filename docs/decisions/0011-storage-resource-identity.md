# 0011. Storage Resource를 SNMP index가 아니라 hrStorageDescr로 식별한다

- 상태: 승인
- 날짜: 2026-09-28
- 관련: 옛 CLAUDE.md 규칙 14(원문은 저장소에 없다), [snmp-resources.md의 Discovery와 선택](../architecture/snmp-resources.md#discovery와-선택), [0009](0009-agentless-snmp-resource-monitoring.md)

## 배경과 문제

운영자는 장비에서 찾은 Storage 가운데 모니터링할 것을 골라 저장한다. 저장한 선택은 다시 검색하거나 장비가 재부팅된 뒤에도 같은 Storage를 가리켜야 한다.

`hrStorageIndex`는 재부팅이나 SNMP Agent 설정 변경으로 바뀔 수 있다.

## 검토한 선택지

- **`hrStorageIndex`로 식별한다**: SNMP table의 key를 그대로 쓴다 / index가 바뀌면 다른 Storage를 가리키거나 선택이 사라진다. 단순함과 안정성을 맞바꾼다.
- **장비가 준 `hrStorageDescr`로 식별한다**: `/`, `/data` 같은 mount 이름은 재부팅해도 유지된다 / 장비가 설명 문자열을 바꾸면 다른 Resource로 본다. 설명 문자열이 안정적이라는 가정에 기댄다.

## 결정

`hrStorageDescr`로 식별한다. index는 참고 값으로만 저장한다.

다시 검색해도 기존 선택은 그대로 둔다. 장비에서 사라진 Resource는 지우지 않고 `UNAVAILABLE`로 보여 준다.

## 결과

- 좋아지는 점: 재부팅 후에도 선택한 Storage가 유지된다.
- 감수할 점: 설명이 바뀐 Storage는 새 Resource로 보이고, 이전 것은 `UNAVAILABLE`로 남는다.
- 확신도: 중간. 장비 종류별로 `hrStorageDescr`가 안정적인지 확인한 기록은 없다.
