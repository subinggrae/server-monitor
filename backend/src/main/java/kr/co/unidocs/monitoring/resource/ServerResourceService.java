package kr.co.unidocs.monitoring.resource;

import kr.co.unidocs.monitoring.prometheus.ServerSnmpMetrics;
import kr.co.unidocs.monitoring.prometheus.SnmpMetricQueryService;
import kr.co.unidocs.monitoring.prometheus.StorageSample;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 고른 Resource에 지금 값을 붙여 줍니다.
 *
 * Threshold가 아직 없어서 사용률로 OK나 CRITICAL을 계산하지 않습니다.
 */
@Service
public class ServerResourceService {

	private final MonitoredResourceService monitoredResourceService;

	private final SnmpMetricQueryService snmpMetricQueryService;

	ServerResourceService(MonitoredResourceService monitoredResourceService,
			SnmpMetricQueryService snmpMetricQueryService) {
		this.monitoredResourceService = monitoredResourceService;
		this.snmpMetricQueryService = snmpMetricQueryService;
	}

	@Transactional(readOnly = true)
	public List<ResourceUsageResponse> currentUsage(Long serverId) {
		List<MonitoredResource> resources = monitoredResourceService.findByServer(serverId);
		if (resources.isEmpty()) {
			return List.of();
		}

		ServerSnmpMetrics metrics = snmpMetricQueryService.snapshot().of(serverId);

		List<ResourceUsageResponse> usages = new ArrayList<>(resources.size());
		for (MonitoredResource resource : resources) {
			usages.add(toUsage(resource, metrics));
		}
		return usages;
	}

	private ResourceUsageResponse toUsage(MonitoredResource resource, ServerSnmpMetrics metrics) {
		if (!resource.isEnabled()) {
			return empty(resource, ResourceStatus.DISABLED);
		}
		if (metrics == null) {
			return empty(resource, ResourceStatus.UNAVAILABLE);
		}

		return switch (resource.getResourceType()) {
			case CPU -> cpuUsage(resource, metrics);
			case MEMORY -> storageUsage(resource, metrics.getMemory());
			case STORAGE -> storageUsage(resource, metrics.disk(resource.getResourceKey()));
		};
	}

	private ResourceUsageResponse cpuUsage(MonitoredResource resource, ServerSnmpMetrics metrics) {
		if (metrics.getCpuPercent() == null) {
			return empty(resource, ResourceStatus.UNAVAILABLE);
		}
		return new ResourceUsageResponse(resource.getId(), resource.getResourceType(), resource.getResourceKey(),
				resource.getDisplayName(), resource.getDescription(), ResourceStatus.AVAILABLE,
				metrics.getCpuPercent(), null, null, metrics.getCpuSampledAt());
	}

	private ResourceUsageResponse storageUsage(MonitoredResource resource, StorageSample sample) {
		if (sample == null) {
			return empty(resource, ResourceStatus.UNAVAILABLE);
		}
		return new ResourceUsageResponse(resource.getId(), resource.getResourceType(), resource.getResourceKey(),
				resource.getDisplayName(), resource.getDescription(), ResourceStatus.AVAILABLE, sample.usagePercent(),
				sample.usedBytes(), sample.totalBytes(), sample.sampledAt());
	}

	private ResourceUsageResponse empty(MonitoredResource resource, ResourceStatus status) {
		return new ResourceUsageResponse(resource.getId(), resource.getResourceType(), resource.getResourceKey(),
				resource.getDisplayName(), resource.getDescription(), status, null, null, null, null);
	}

}
