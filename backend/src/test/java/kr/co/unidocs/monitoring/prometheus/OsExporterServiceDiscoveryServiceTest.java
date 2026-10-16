package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.exporter.ServerExporterConfig;
import kr.co.unidocs.monitoring.exporter.ServerExporterConfigRepository;
import kr.co.unidocs.monitoring.server.ServerOs;
import kr.co.unidocs.monitoring.server.ServerTarget;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OS exporter HTTP SD 목록")
class OsExporterServiceDiscoveryServiceTest {

	private final ServerTargetRepository serverRepository = mock(ServerTargetRepository.class);

	private final ServerExporterConfigRepository configRepository = mock(ServerExporterConfigRepository.class);

	private final OsExporterServiceDiscoveryService service = new OsExporterServiceDiscoveryService(serverRepository,
			configRepository);

	@Test
	@DisplayName("수집을 켠 서버를 host:port와 정한 label 다섯 개로 넣는다")
	void listsEnabledServer() {
		givenServer(5L, "web01", ServerOs.WINDOWS, true);
		when(configRepository.findByEnabledTrue()).thenReturn(List.of(new ServerExporterConfig(5L, true, 9182, 30)));

		List<ServiceDiscoveryTarget> targets = service.osExporterTargets();

		assertThat(targets).hasSize(1);
		assertThat(targets.get(0).targets()).containsExactly("10.0.0.5:9182");
		assertThat(targets.get(0).labels()).containsExactly(
				entry("server_id", "5"),
				entry("server_name", "web01"),
				entry("environment", "Production"),
				entry("os", "WINDOWS"),
				entry("__scrape_interval__", "30s"));
	}

	@Test
	@DisplayName("서버 감시가 꺼져 있거나 서버가 없으면 넣지 않는다")
	void skipsDisabledOrMissingServer() {
		givenServer(5L, "web01", ServerOs.LINUX, false);
		when(serverRepository.findById(6L)).thenReturn(Optional.empty());
		when(configRepository.findByEnabledTrue()).thenReturn(
				List.of(new ServerExporterConfig(5L, true, 9100, 15), new ServerExporterConfig(6L, true, 9100, 15)));

		assertThat(service.osExporterTargets()).isEmpty();
	}

	@Test
	@DisplayName("켠 설정이 없으면 빈 목록이다")
	void emptyWithoutEnabledConfig() {
		when(configRepository.findByEnabledTrue()).thenReturn(List.of());

		assertThat(service.osExporterTargets()).isEmpty();
	}

	private void givenServer(Long serverId, String name, ServerOs os, boolean enabled) {
		ServerTarget server = new ServerTarget(name, "10.0.0.5", "Production", os, null, enabled);
		ReflectionTestUtils.setField(server, "id", serverId);
		when(serverRepository.findById(serverId)).thenReturn(Optional.of(server));
	}

}
