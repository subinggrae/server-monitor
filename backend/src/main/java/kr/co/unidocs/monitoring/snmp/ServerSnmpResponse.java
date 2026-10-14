package kr.co.unidocs.monitoring.snmp;

/**
 * community는 마스킹한 값도 넣지 않습니다. 설정되어 있는지만 알려 줍니다. (CLAUDE.md 11번)
 *
 * exporterSynced가 false면 저장은 됐지만 snmp_exporter에 반영되지 않은 상태입니다.
 */
public record ServerSnmpResponse(boolean configured, SnmpVersion version, Integer port, boolean communityConfigured,
		boolean enabled, Integer scrapeIntervalSeconds, boolean exporterSynced, String exporterMessage) {

	public static ServerSnmpResponse notConfigured() {
		return new ServerSnmpResponse(false, null, null, false, false, null, true, null);
	}

	static ServerSnmpResponse of(ServerSnmpConfig config, SnmpSyncStatus syncStatus) {
		return new ServerSnmpResponse(true, config.getVersion(), config.getPort(), true, config.isEnabled(),
				config.getScrapeIntervalSeconds(), syncStatus.succeeded(),
				syncStatus.succeeded() ? null : syncStatus.message());
	}

}
