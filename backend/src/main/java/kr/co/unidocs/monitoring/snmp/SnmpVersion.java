package kr.co.unidocs.monitoring.snmp;

/**
 * 지금은 v2c만 지원합니다. v3는 Credential 모델이 달라서 다음 단계에서 추가합니다.
 *
 * {@code exporterVersion}은 snmp_exporter auth config의 version 값입니다.
 */
public enum SnmpVersion {

	V2C(2);

	private final int exporterVersion;

	SnmpVersion(int exporterVersion) {
		this.exporterVersion = exporterVersion;
	}

	public int exporterVersion() {
		return exporterVersion;
	}

}
