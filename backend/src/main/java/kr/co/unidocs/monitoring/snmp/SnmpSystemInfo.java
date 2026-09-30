package kr.co.unidocs.monitoring.snmp;

/** {@code reachable}이 false면 장비가 응답하지 않았거나 community가 맞지 않은 경우입니다. */
public record SnmpSystemInfo(boolean reachable, String systemName, String systemDescription, Long uptimeSeconds,
		long responseTimeMs) {

	public static SnmpSystemInfo unreachable() {
		return new SnmpSystemInfo(false, null, null, null, 0);
	}

}
