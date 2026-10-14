package kr.co.unidocs.monitoring.snmp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SNMP 설정의 수집 주기")
class ServerSnmpConfigTest {

	@Test
	@DisplayName("수집 주기 없이 만들면 15초다")
	void defaultsToFifteenSeconds() {
		ServerSnmpConfig config = new ServerSnmpConfig(1L, SnmpVersion.V2C, 161, "cipher", true, null);

		assertThat(config.getScrapeIntervalSeconds()).isEqualTo(15);
	}

	@Test
	@DisplayName("정한 수집 주기로 만든다")
	void createsWithGivenInterval() {
		ServerSnmpConfig config = new ServerSnmpConfig(1L, SnmpVersion.V2C, 161, "cipher", true, 120);

		assertThat(config.getScrapeIntervalSeconds()).isEqualTo(120);
	}

	@Test
	@DisplayName("수집 주기 없이 고치면 기존 주기를 그대로 둔다")
	void keepsIntervalWhenUpdatedWithout() {
		ServerSnmpConfig config = new ServerSnmpConfig(1L, SnmpVersion.V2C, 161, "cipher", true, 120);

		config.update(SnmpVersion.V2C, 161, null, true, null);

		assertThat(config.getScrapeIntervalSeconds()).isEqualTo(120);
	}

	@Test
	@DisplayName("새 수집 주기로 고치면 그 주기가 된다")
	void updatesInterval() {
		ServerSnmpConfig config = new ServerSnmpConfig(1L, SnmpVersion.V2C, 161, "cipher", true, 120);

		config.update(SnmpVersion.V2C, 161, null, true, 30);

		assertThat(config.getScrapeIntervalSeconds()).isEqualTo(30);
	}

}
