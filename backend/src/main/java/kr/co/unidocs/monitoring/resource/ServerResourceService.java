package kr.co.unidocs.monitoring.resource;

import kr.co.unidocs.monitoring.prometheus.ResourceMetricQueryService;
import kr.co.unidocs.monitoring.prometheus.ResourceValue;
import kr.co.unidocs.monitoring.prometheus.ResourceValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 고른 Resource에 지금 값을 붙여 줍니다. 값은 OS 공통 recording rule에서 읽습니다.
 *
 * 사용률로 OK나 CRITICAL을 계산하지 않습니다. 사용률 알림은 Alert 규칙이 맡습니다.
 */
@Service
public class ServerResourceService {

	private final MonitoredResourceService monitoredResourceService;

	private final ResourceMetricQueryService metricQueryService;

	ServerResourceService(MonitoredResourceService monitoredResourceService,
			ResourceMetricQueryService metricQueryService) {
		this.monitoredResourceService = monitoredResourceService;
		this.metricQueryService = metricQueryService;
	}

	@Transactional(readOnly = true)
	public List<ResourceUsageResponse> currentUsage(Long serverId) {
		List<MonitoredResource> resources = monitoredResourceService.findByServer(serverId);
		if (resources.isEmpty()) {
			return List.of();
		}

		ResourceValues values = metricQueryService.resourceValues(serverId);

		List<ResourceUsageResponse> usages = new ArrayList<>(resources.size());
		for (MonitoredResource resource : resources) {
			usages.add(toUsage(resource, values));
		}
		return usages;
	}

	private ResourceUsageResponse toUsage(MonitoredResource resource, ResourceValues values) {
		ResourceValue value = values.of(resource.getResourceType(), resource.getResourceKey());
		if (value == null) {
			return empty(resource, ResourceStatus.UNAVAILABLE);
		}
		return new ResourceUsageResponse(resource.getId(), resource.getResourceType(), resource.getResourceKey(),
				resource.getDisplayName(), resource.getDescription(), ResourceStatus.AVAILABLE, value.usagePercent(),
				value.usedBytes(), value.sizeBytes(), value.sampledAt());
	}

	private ResourceUsageResponse empty(MonitoredResource resource, ResourceStatus status) {
		return new ResourceUsageResponse(resource.getId(), resource.getResourceType(), resource.getResourceKey(),
				resource.getDisplayName(), resource.getDescription(), status, null, null, null, null);
	}

}
