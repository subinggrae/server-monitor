package kr.co.unidocs.monitoring.snmp;

public enum SnmpState {

	NOT_CONFIGURED,

	/** 설정은 있지만 꺼 두었습니다. */
	DISABLED,

	/** Prometheus가 이 서버에서 metric을 가져오고 있습니다. */
	CONNECTED,

	/** 설정은 켜져 있는데 값이 오지 않습니다. */
	NO_RESPONSE

}
