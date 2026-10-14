package kr.co.unidocs.monitoring.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Check의 수집 주기")
class MonitoringCheckTest {

	@Test
	@DisplayName("수집 주기 없이 만들면 15초다")
	void defaultsToFifteenSeconds() {
		MonitoringCheck check = new MonitoringCheck("전자결재 Health", CheckType.ICMP, "Production", null, true, null);

		assertThat(check.getScrapeIntervalSeconds()).isEqualTo(15);
	}

	@Test
	@DisplayName("정한 수집 주기로 만든다")
	void createsWithGivenInterval() {
		MonitoringCheck check = new MonitoringCheck("전자결재 Health", CheckType.ICMP, "Production", null, true, 60);

		assertThat(check.getScrapeIntervalSeconds()).isEqualTo(60);
	}

	@Test
	@DisplayName("수집 주기 없이 고치면 기존 주기를 그대로 둔다")
	void keepsIntervalWhenUpdatedWithout() {
		MonitoringCheck check = new MonitoringCheck("전자결재 Health", CheckType.ICMP, "Production", null, true, 60);

		check.update("전자결재 Health", CheckType.ICMP, "Production", null, true, null);

		assertThat(check.getScrapeIntervalSeconds()).isEqualTo(60);
	}

	@Test
	@DisplayName("새 수집 주기로 고치면 그 주기가 된다")
	void updatesInterval() {
		MonitoringCheck check = new MonitoringCheck("전자결재 Health", CheckType.ICMP, "Production", null, true, 60);

		check.update("전자결재 Health", CheckType.ICMP, "Production", null, true, 30);

		assertThat(check.getScrapeIntervalSeconds()).isEqualTo(30);
	}

}
