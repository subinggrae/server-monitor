package kr.co.unidocs.monitoring.snmp;

/** community 평문이 들어 있습니다. 로그나 응답에 그대로 넣지 않습니다. */
public record SnmpTarget(String host, int port, SnmpVersion version, String community) {

	@Override
	public String toString() {
		return "SnmpTarget[host=" + host + ", port=" + port + ", version=" + version + "]";
	}

}
