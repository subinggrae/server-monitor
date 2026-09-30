package kr.co.unidocs.monitoring.snmp;

/**
 * OID는 추측하지 않고 표준 MIB 정의를 그대로 씁니다.
 *
 * HOST-RESOURCES-MIB(RFC 2790)를 기준으로 합니다.
 * Linux 전용 UCD-SNMP-MIB는 쓰지 않습니다. 특정 구현에 종속되면 안 됩니다.
 */
final class SnmpOids {

	static final String SYS_DESCR = "1.3.6.1.2.1.1.1.0";

	static final String SYS_UPTIME = "1.3.6.1.2.1.1.3.0";

	static final String SYS_NAME = "1.3.6.1.2.1.1.5.0";

	/** hrProcessorLoad. 최근 1분 평균 사용률(%)입니다. processor마다 한 행입니다. */
	static final String HR_PROCESSOR_LOAD = "1.3.6.1.2.1.25.3.3.1.2";

	static final String HR_STORAGE_TYPE = "1.3.6.1.2.1.25.2.3.1.2";

	static final String HR_STORAGE_DESCR = "1.3.6.1.2.1.25.2.3.1.3";

	static final String HR_STORAGE_ALLOCATION_UNITS = "1.3.6.1.2.1.25.2.3.1.4";

	static final String HR_STORAGE_SIZE = "1.3.6.1.2.1.25.2.3.1.5";

	static final String HR_STORAGE_USED = "1.3.6.1.2.1.25.2.3.1.6";

	/**
	 * hrStorageTable에는 memory, swap, buffer, disk가 함께 들어옵니다.
	 * 첫 행을 memory라고 가정하면 안 되고 이 type으로 갈라야 합니다.
	 */
	static final String STORAGE_TYPE_RAM = "1.3.6.1.2.1.25.2.1.2";

	static final String STORAGE_TYPE_FIXED_DISK = "1.3.6.1.2.1.25.2.1.4";

	static final String STORAGE_TYPE_NETWORK_DISK = "1.3.6.1.2.1.25.2.1.10";

	private SnmpOids() {
	}

}
