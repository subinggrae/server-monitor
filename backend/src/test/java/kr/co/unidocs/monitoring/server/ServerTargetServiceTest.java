package kr.co.unidocs.monitoring.server;

import kr.co.unidocs.monitoring.exporter.CollectionState;
import kr.co.unidocs.monitoring.exporter.ServerExporterConfig;
import kr.co.unidocs.monitoring.exporter.ServerExporterConfigRepository;
import kr.co.unidocs.monitoring.prometheus.CollectionSnapshot;
import kr.co.unidocs.monitoring.prometheus.ResourceMetricQueryService;
import kr.co.unidocs.monitoring.resource.MonitoredResourceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("서버의 수집 상태")
class ServerTargetServiceTest {

	private final ServerTargetRepository serverRepository = mock(ServerTargetRepository.class);

	private final ServerExporterConfigRepository configRepository = mock(ServerExporterConfigRepository.class);

	private final MonitoredResourceRepository monitoredResourceRepository = mock(MonitoredResourceRepository.class);

	private final ResourceMetricQueryService metricQueryService = mock(ResourceMetricQueryService.class);

	private final ServerTargetService service = new ServerTargetService(serverRepository, configRepository,
			monitoredResourceRepository, metricQueryService);

	@Test
	@DisplayName("수집 설정이 없으면 설정 안 함이다")
	void notConfigured() {
		givenServer(true, null);
		givenSnapshot(new CollectionSnapshot(true, Map.of()));

		assertThat(service.findById(5L).collectionState()).isEqualTo(CollectionState.NOT_CONFIGURED);
	}

	@Test
	@DisplayName("수집 설정이나 서버 감시가 꺼져 있으면 수집 꺼짐이다")
	void disabled() {
		givenSnapshot(new CollectionSnapshot(true, Map.of(5L, true)));

		givenServer(true, new ServerExporterConfig(5L, false, 9100, 15));
		assertThat(service.findById(5L).collectionState()).isEqualTo(CollectionState.DISABLED);

		givenServer(false, new ServerExporterConfig(5L, true, 9100, 15));
		assertThat(service.findById(5L).collectionState()).isEqualTo(CollectionState.DISABLED);
	}

	@Test
	@DisplayName("켜져 있고 up이 1이면 수집 중이다")
	void collecting() {
		givenServer(true, new ServerExporterConfig(5L, true, 9100, 15));
		givenSnapshot(new CollectionSnapshot(true, Map.of(5L, true)));

		assertThat(service.findById(5L).collectionState()).isEqualTo(CollectionState.COLLECTING);
	}

	@Test
	@DisplayName("켜져 있는데 up이 0이거나 series가 없으면 수집 실패다")
	void failed() {
		givenServer(true, new ServerExporterConfig(5L, true, 9100, 15));

		givenSnapshot(new CollectionSnapshot(true, Map.of(5L, false)));
		assertThat(service.findById(5L).collectionState()).isEqualTo(CollectionState.FAILED);

		givenSnapshot(new CollectionSnapshot(true, Map.of()));
		assertThat(service.findById(5L).collectionState()).isEqualTo(CollectionState.FAILED);
	}

	@Test
	@DisplayName("Prometheus를 조회하지 못하면 알 수 없음이다")
	void unknown() {
		givenServer(true, new ServerExporterConfig(5L, true, 9100, 15));
		givenSnapshot(CollectionSnapshot.unavailable());

		assertThat(service.findById(5L).collectionState()).isEqualTo(CollectionState.UNKNOWN);
	}

	@Test
	@DisplayName("서버 응답에 OS가 들어간다")
	void includesOs() {
		givenServer(true, null);
		givenSnapshot(new CollectionSnapshot(true, Map.of()));

		assertThat(service.findById(5L).os()).isEqualTo(ServerOs.WINDOWS);
	}

	private void givenServer(boolean enabled, ServerExporterConfig config) {
		ServerTarget server = new ServerTarget("web01", "10.0.0.5", "Production", ServerOs.WINDOWS, null, enabled);
		ReflectionTestUtils.setField(server, "id", 5L);
		when(serverRepository.findById(5L)).thenReturn(Optional.of(server));
		when(configRepository.findByServerId(5L)).thenReturn(Optional.ofNullable(config));
		when(monitoredResourceRepository.findByServerIdOrderByResourceTypeAscResourceKeyAsc(5L)).thenReturn(List.of());
	}

	private void givenSnapshot(CollectionSnapshot snapshot) {
		when(metricQueryService.collectionSnapshot()).thenReturn(snapshot);
	}

}
