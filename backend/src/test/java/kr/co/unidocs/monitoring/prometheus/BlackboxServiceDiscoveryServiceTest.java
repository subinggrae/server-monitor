package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.check.CheckType;
import kr.co.unidocs.monitoring.check.MonitoringCheck;
import kr.co.unidocs.monitoring.check.MonitoringCheckRepository;
import kr.co.unidocs.monitoring.check.MonitoringCheckTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Blackbox HTTP SD 목록")
class BlackboxServiceDiscoveryServiceTest {

	@Test
	@DisplayName("entry마다 Check의 수집 주기를 __scrape_interval__로 넣고 기존 label과 주소는 그대로 둔다")
	void addsScrapeIntervalToEachEntry() {
		MonitoringCheck check = new MonitoringCheck("전자결재 Health", CheckType.TCP, "Production", null, true, 30);
		ReflectionTestUtils.setField(check, "id", 3L);
		MonitoringCheckTarget first = check.addTarget("10.0.0.1", 8080, true);
		ReflectionTestUtils.setField(first, "id", 7L);
		MonitoringCheckTarget second = check.addTarget("10.0.0.2", 8080, true);
		ReflectionTestUtils.setField(second, "id", 8L);

		MonitoringCheckRepository repository = mock(MonitoringCheckRepository.class);
		when(repository.findAllActive()).thenReturn(List.of(check));

		List<ServiceDiscoveryTarget> targets = new BlackboxServiceDiscoveryService(repository).blackboxTargets();

		assertThat(targets).hasSize(2);
		assertThat(targets.get(0).targets()).containsExactly("10.0.0.1:8080");
		assertThat(targets.get(0).labels()).containsExactly(
				entry("check_id", "3"),
				entry("check_target_id", "7"),
				entry("check_type", "TCP"),
				entry("module", "tcp_connect"),
				entry("environment", "Production"),
				entry("check_name", "전자결재 Health"),
				entry("__scrape_interval__", "30s"));
		assertThat(targets.get(1).targets()).containsExactly("10.0.0.2:8080");
		assertThat(targets.get(1).labels()).contains(entry("check_target_id", "8"), entry("__scrape_interval__", "30s"));
	}

	@Test
	@DisplayName("Check마다 자기 수집 주기를 넣는다")
	void usesEachChecksOwnInterval() {
		MonitoringCheck fast = new MonitoringCheck("빠른 Check", CheckType.ICMP, "Production", null, true, 10);
		fast.addTarget("10.0.0.1", null, true);
		MonitoringCheck slow = new MonitoringCheck("느린 Check", CheckType.ICMP, "Production", null, true, 240);
		slow.addTarget("10.0.0.2", null, true);

		MonitoringCheckRepository repository = mock(MonitoringCheckRepository.class);
		when(repository.findAllActive()).thenReturn(List.of(fast, slow));

		List<ServiceDiscoveryTarget> targets = new BlackboxServiceDiscoveryService(repository).blackboxTargets();

		assertThat(targets).extracting(target -> target.labels().get("__scrape_interval__"))
				.containsExactly("10s", "240s");
	}

	@Test
	@DisplayName("기존 Check처럼 기본 주기면 15s를 넣는다")
	void usesDefaultInterval() {
		MonitoringCheck check = new MonitoringCheck("전자결재 Health", CheckType.ICMP, "Production", null, true, null);
		check.addTarget("10.0.0.1", null, true);

		MonitoringCheckRepository repository = mock(MonitoringCheckRepository.class);
		when(repository.findAllActive()).thenReturn(List.of(check));

		Map<String, String> labels = new BlackboxServiceDiscoveryService(repository).blackboxTargets().get(0).labels();

		assertThat(labels).containsEntry("__scrape_interval__", "15s");
	}

}
