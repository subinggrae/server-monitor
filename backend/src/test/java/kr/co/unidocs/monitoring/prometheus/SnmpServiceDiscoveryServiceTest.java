package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.resource.MonitoredResource;
import kr.co.unidocs.monitoring.resource.MonitoredResourceRepository;
import kr.co.unidocs.monitoring.resource.ResourceType;
import kr.co.unidocs.monitoring.server.ServerTarget;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import kr.co.unidocs.monitoring.snmp.ServerSnmpConfig;
import kr.co.unidocs.monitoring.snmp.ServerSnmpConfigRepository;
import kr.co.unidocs.monitoring.snmp.SnmpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SNMP HTTP SD 목록")
class SnmpServiceDiscoveryServiceTest {

	private final ServerTargetRepository serverRepository = mock(ServerTargetRepository.class);

	private final ServerSnmpConfigRepository snmpConfigRepository = mock(ServerSnmpConfigRepository.class);

	private final MonitoredResourceRepository monitoredResourceRepository = mock(MonitoredResourceRepository.class);

	private final SnmpServiceDiscoveryService service = new SnmpServiceDiscoveryService(serverRepository,
			snmpConfigRepository, monitoredResourceRepository);

	@Test
	@DisplayName("entry의 기존 label과 주소에 서버의 수집 주기 __scrape_interval__만 더한다")
	void addsScrapeInterval() {
		givenServer(5L, "web01", "10.0.0.5", new ServerSnmpConfig(5L, SnmpVersion.V2C, 161, "cipher", true, 120));

		List<ServiceDiscoveryTarget> targets = service.snmpTargets();

		assertThat(targets).hasSize(1);
		assertThat(targets.get(0).targets()).containsExactly("10.0.0.5:161");
		assertThat(targets.get(0).labels()).containsExactly(
				entry("server_id", "5"),
				entry("server_name", "web01"),
				entry("environment", "Production"),
				entry("snmp_auth", "manager_server_5"),
				entry("snmp_modules", "system,hrDevice"),
				entry("__scrape_interval__", "120s"));
	}

	@Test
	@DisplayName("기존 SNMP 설정처럼 기본 주기면 15s를 넣는다")
	void usesDefaultInterval() {
		givenServer(5L, "web01", "10.0.0.5", new ServerSnmpConfig(5L, SnmpVersion.V2C, 161, "cipher", true, null));

		assertThat(service.snmpTargets().get(0).labels()).containsEntry("__scrape_interval__", "15s");
	}

	private void givenServer(Long serverId, String name, String host, ServerSnmpConfig config) {
		ServerTarget server = new ServerTarget(name, host, "Production", kr.co.unidocs.monitoring.server.ServerOs.LINUX, null, true);
		ReflectionTestUtils.setField(server, "id", serverId);

		when(snmpConfigRepository.findByEnabledTrue()).thenReturn(List.of(config));
		when(serverRepository.findById(serverId)).thenReturn(Optional.of(server));
		when(monitoredResourceRepository.findByServerIdOrderByResourceTypeAscResourceKeyAsc(serverId))
				.thenReturn(List.of(new MonitoredResource(serverId, ResourceType.CPU, "cpu", "CPU", null)));
	}

}
