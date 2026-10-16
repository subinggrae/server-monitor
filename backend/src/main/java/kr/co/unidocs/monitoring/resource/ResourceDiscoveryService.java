package kr.co.unidocs.monitoring.resource;

import kr.co.unidocs.monitoring.exporter.ExporterClient;
import kr.co.unidocs.monitoring.exporter.ExporterFilesystem;
import kr.co.unidocs.monitoring.exporter.ExporterMetrics;
import kr.co.unidocs.monitoring.exporter.ExporterOutcome;
import kr.co.unidocs.monitoring.exporter.ExporterReadResult;
import kr.co.unidocs.monitoring.exporter.ExporterTarget;
import kr.co.unidocs.monitoring.exporter.FilesystemFilter;
import kr.co.unidocs.monitoring.exporter.ServerExporterService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 리소스 찾기는 Manager가 저장하고 켠 수집 설정의 exporter를 직접 한 번 읽습니다.
 * Prometheus의 첫 수집을 기다리지 않아도 목록이 나옵니다.
 *
 * 찾은 결과는 저장하지 않습니다. 무엇을 감시할지는 사용자가 고른 뒤에 저장합니다.
 */
@Service
public class ResourceDiscoveryService {

	/** CPU와 Memory는 서버마다 하나뿐이라 key를 고정합니다. recording rule의 resource_key와 같습니다. */
	public static final String CPU_RESOURCE_KEY = "cpu";

	public static final String MEMORY_RESOURCE_KEY = "memory";

	private final ServerExporterService serverExporterService;

	private final ExporterClient exporterClient;

	private final MonitoredResourceRepository monitoredResourceRepository;

	ResourceDiscoveryService(ServerExporterService serverExporterService, ExporterClient exporterClient,
			MonitoredResourceRepository monitoredResourceRepository) {
		this.serverExporterService = serverExporterService;
		this.exporterClient = exporterClient;
		this.monitoredResourceRepository = monitoredResourceRepository;
	}

	@Transactional(readOnly = true)
	public ResourceDiscoveryResult discover(Long serverId) {
		Optional<ExporterTarget> target = serverExporterService.enabledTarget(serverId);
		if (target.isEmpty()) {
			return ResourceDiscoveryResult.failed("OS exporter 수집이 꺼져 있습니다. 수집 설정에서 먼저 수집을 켜고 저장해 주세요.");
		}

		ExporterReadResult read = exporterClient.read(target.get().host(), target.get().port());
		if (read.outcome() != ExporterOutcome.OK) {
			return ResourceDiscoveryResult.failed(read.outcome().failureMessage());
		}

		ExporterMetrics metrics = read.metrics();
		Set<String> alreadySelected = selectedKeys(serverId);
		List<DiscoveredResource> resources = new ArrayList<>();

		if (metrics.cpuCount() > 0) {
			resources.add(new DiscoveredResource(ResourceType.CPU, CPU_RESOURCE_KEY, "CPU",
					metrics.cpuCount() + "개 코어", null,
					alreadySelected.contains(key(ResourceType.CPU, CPU_RESOURCE_KEY))));
		}
		if (metrics.memoryTotalBytes() != null) {
			resources.add(new DiscoveredResource(ResourceType.MEMORY, MEMORY_RESOURCE_KEY, "Memory", null,
					metrics.memoryTotalBytes(), alreadySelected.contains(key(ResourceType.MEMORY, MEMORY_RESOURCE_KEY))));
		}
		for (ExporterFilesystem filesystem : metrics.filesystems()) {
			if (!FilesystemFilter.includes(metrics.exporterType(), filesystem)) {
				continue;
			}
			resources.add(new DiscoveredResource(ResourceType.STORAGE, filesystem.key(), filesystem.key(),
					filesystem.fstype(), filesystem.sizeBytes(),
					alreadySelected.contains(key(ResourceType.STORAGE, filesystem.key()))));
		}

		String message = resources.isEmpty() ? "exporter는 읽었지만 CPU, Memory, Storage 정보를 찾지 못했습니다."
				: "리소스를 찾았습니다.";
		return new ResourceDiscoveryResult(true, message, metrics.exporterType(), metrics.version(),
				List.copyOf(resources));
	}

	private Set<String> selectedKeys(Long serverId) {
		Set<String> keys = new HashSet<>();
		for (MonitoredResource resource : monitoredResourceRepository
				.findByServerIdOrderByResourceTypeAscResourceKeyAsc(serverId)) {
			keys.add(key(resource.getResourceType(), resource.getResourceKey()));
		}
		return keys;
	}

	private String key(ResourceType type, String resourceKey) {
		return type.name() + '\u0000' + resourceKey;
	}

}
