package kr.co.unidocs.monitoring.snmp;

/** 실패 이유를 그대로 옮기지 않습니다. stacktrace와 community는 사용자에게 보이지 않습니다. */
public record SnmpTestResult(boolean success, String message, String systemName, String systemDescription,
		Long responseTimeMs) {

	static SnmpTestResult of(SnmpSystemInfo info) {
		if (!info.reachable()) {
			return failed("SNMP 응답을 받지 못했습니다. Host, Port, Community와 네트워크 접근을 확인해 주세요.");
		}
		return new SnmpTestResult(true, "SNMP로 장비 정보를 읽었습니다.", info.systemName(), info.systemDescription(),
				info.responseTimeMs());
	}

	static SnmpTestResult failed(String message) {
		return new SnmpTestResult(false, message, null, null, null);
	}

}
