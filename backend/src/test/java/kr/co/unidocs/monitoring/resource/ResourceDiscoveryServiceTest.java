package kr.co.unidocs.monitoring.resource;

import kr.co.unidocs.monitoring.exporter.ExporterClient;
import kr.co.unidocs.monitoring.exporter.ExporterFilesystem;
import kr.co.unidocs.monitoring.exporter.ExporterMetrics;
import kr.co.unidocs.monitoring.exporter.ExporterOutcome;
import kr.co.unidocs.monitoring.exporter.ExporterReadResult;
import kr.co.unidocs.monitoring.exporter.ExporterTarget;
import kr.co.unidocs.monitoring.exporter.ExporterType;
import kr.co.unidocs.monitoring.exporter.ServerExporterService;
import kr.co.unidocs.monitoring.server.ServerOs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OS exporter로 리소스 찾기")
class ResourceDiscoveryServiceTest {

	private final ServerExporterService serverExporterService = mock(ServerExporterService.class);

	private final ExporterClient exporterClient = mock(ExporterClient.class);

	private final MonitoredResourceRepository monitoredResourceRepository = mock(MonitoredResourceRepository.class);

	private final ResourceDiscoveryService service = new ResourceDiscoveryService(serverExporterService,
			exporterClient, monitoredResourceRepository);

	@Test
	@DisplayName("Linux는 CPU, Memory와 임시 파일 시스템을 뺀 mountpoint를 보인다")
	void discoversLinuxResources() {
		givenTarget(ServerOs.LINUX, 9100);
		givenRead(9100, new ExporterMetrics(ExporterType.NODE_EXPORTER, "1.12.1", 2, 8_000_000_000L, List.of(
				new ExporterFilesystem("/data", "ext4", 1000), new ExporterFilesystem("/run", "tmpfs", 10),
				new ExporterFilesystem("/", "overlay", 1000), new ExporterFilesystem("/" + "a".repeat(255), "ext4", 1))));

		ResourceDiscoveryResult result = service.discover(5L);

		assertThat(result.success()).isTrue();
		assertThat(result.exporterType()).isEqualTo(ExporterType.NODE_EXPORTER);
		assertThat(result.version()).isEqualTo("1.12.1");
		assertThat(result.resources()).extracting(DiscoveredResource::resourceType, DiscoveredResource::resourceKey)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(ResourceType.CPU, "cpu"),
					org.assertj.core.groups.Tuple.tuple(ResourceType.MEMORY, "memory"),
					org.assertj.core.groups.Tuple.tuple(ResourceType.STORAGE, "/data"));
		assertThat(result.resources().get(1).totalBytes()).isEqualTo(8_000_000_000L);
		assertThat(result.resources().get(2).totalBytes()).isEqualTo(1000L);
	}

	@Test
	@DisplayName("Windows는 드라이브 이름이 있는 volume만 보인다")
	void discoversWindowsResources() {
		givenTarget(ServerOs.WINDOWS, 9182);
		givenRead(9182, new ExporterMetrics(ExporterType.WINDOWS_EXPORTER, "0.31.9", 4, 16L, List.of(
				new ExporterFilesystem("C:", null, 100), new ExporterFilesystem("HarddiskVolume2", null, 5))));

		assertThat(service.discover(5L).resources()).extracting(DiscoveredResource::resourceKey)
			.containsExactly("cpu", "memory", "C:");
	}

	@Test
	@DisplayName("이미 고른 리소스는 selected로 표시한다")
	void marksSelected() {
		givenTarget(ServerOs.LINUX, 9100);
		givenRead(9100, new ExporterMetrics(ExporterType.NODE_EXPORTER, "1.12.1", 2, 8L,
				List.of(new ExporterFilesystem("/data", "ext4", 1000))));
		when(monitoredResourceRepository.findByServerIdOrderByResourceTypeAscResourceKeyAsc(5L))
			.thenReturn(List.of(new MonitoredResource(5L, ResourceType.STORAGE, "/data", "/data", null)));

		assertThat(service.discover(5L).resources()).extracting(DiscoveredResource::resourceKey,
				DiscoveredResource::selected)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("cpu", false),
					org.assertj.core.groups.Tuple.tuple("memory", false),
					org.assertj.core.groups.Tuple.tuple("/data", true));
	}

	@Test
	@DisplayName("수집이 꺼져 있으면 exporter를 읽지 않고 실패로 끝낸다")
	void requiresEnabledCollection() {
		when(serverExporterService.enabledTarget(5L)).thenReturn(Optional.empty());

		ResourceDiscoveryResult result = service.discover(5L);

		assertThat(result.success()).isFalse();
		assertThat(result.resources()).isEmpty();
		verify(exporterClient, never()).read(any(), anyInt());
	}

	@Test
	@DisplayName("exporter를 읽지 못하면 분류의 정한 문구로 실패한다")
	void reportsReadFailure() {
		givenTarget(ServerOs.LINUX, 9100);
		when(exporterClient.read("10.0.0.5", 9100))
			.thenReturn(new ExporterReadResult(ExporterOutcome.UNREADABLE, 200, null, 5L));

		ResourceDiscoveryResult result = service.discover(5L);

		assertThat(result.success()).isFalse();
		assertThat(result.message()).isEqualTo(ExporterOutcome.UNREADABLE.failureMessage());
	}

	private void givenTarget(ServerOs os, int port) {
		when(serverExporterService.enabledTarget(5L)).thenReturn(Optional.of(new ExporterTarget("10.0.0.5", port, os)));
	}

	private void givenRead(int port, ExporterMetrics metrics) {
		when(exporterClient.read("10.0.0.5", port)).thenReturn(new ExporterReadResult(ExporterOutcome.OK, 200, metrics, 5L));
	}

}
