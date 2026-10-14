package kr.co.unidocs.monitoring.snmp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SNMP 설정 응답")
class ServerSnmpResponseTest {

	@Test
	@DisplayName("SNMP 설정의 수집 주기를 담는다")
	void includesScrapeInterval() {
		ServerSnmpConfig config = new ServerSnmpConfig(1L, SnmpVersion.V2C, 161, "cipher", true, 90);

		ServerSnmpResponse response = ServerSnmpResponse.of(config, SnmpSyncStatus.succeeded(1));

		assertThat(response.scrapeIntervalSeconds()).isEqualTo(90);
	}

	@Test
	@DisplayName("SNMP 설정이 없으면 수집 주기도 없다")
	void hasNoIntervalWhenNotConfigured() {
		assertThat(ServerSnmpResponse.notConfigured().scrapeIntervalSeconds()).isNull();
	}

}
