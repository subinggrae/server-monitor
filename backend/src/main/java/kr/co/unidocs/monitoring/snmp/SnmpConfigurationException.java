package kr.co.unidocs.monitoring.snmp;

/** 메시지가 사용자 화면에 그대로 나갑니다. community나 내부 오류를 넣지 않습니다. */
public class SnmpConfigurationException extends RuntimeException {

	public SnmpConfigurationException(String message) {
		super(message);
	}

}
