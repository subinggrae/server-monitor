package kr.co.unidocs.monitoring.snmp;

import java.util.List;

/**
 * Resource Discovery에 필요한 SNMP 조회 결과입니다.
 *
 * {@code processorCount}가 0이면 장비가 hrProcessorTable을 제공하지 않는 경우입니다.
 * 값을 만들어 내지 않고 CPU를 발견하지 못한 것으로 둡니다.
 */
public record SnmpWalkResult(boolean reachable, SnmpSystemInfo systemInfo, int processorCount,
		List<SnmpStorageEntry> storageEntries) {

	public static SnmpWalkResult unreachable() {
		return new SnmpWalkResult(false, SnmpSystemInfo.unreachable(), 0, List.of());
	}

}
