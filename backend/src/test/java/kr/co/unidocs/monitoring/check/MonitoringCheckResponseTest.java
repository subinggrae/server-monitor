package kr.co.unidocs.monitoring.check;

import kr.co.unidocs.monitoring.check.CheckStatusService.ProbeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Check 응답")
class MonitoringCheckResponseTest {

	@Test
	@DisplayName("Check의 수집 주기를 담는다")
	void includesScrapeInterval() {
		MonitoringCheck check = new MonitoringCheck("전자결재 Health", CheckType.ICMP, "Production", null, true, 45);

		MonitoringCheckResponse response = MonitoringCheckResponse.of(check, new ProbeSnapshot(true, Map.of()));

		assertThat(response.scrapeIntervalSeconds()).isEqualTo(45);
	}

}
