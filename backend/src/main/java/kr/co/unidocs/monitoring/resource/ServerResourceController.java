package kr.co.unidocs.monitoring.resource;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/servers/{serverId}/resources")
class ServerResourceController {

	private final ResourceDiscoveryService resourceDiscoveryService;

	private final MonitoredResourceService monitoredResourceService;

	private final ServerResourceService serverResourceService;

	ServerResourceController(ResourceDiscoveryService resourceDiscoveryService,
			MonitoredResourceService monitoredResourceService, ServerResourceService serverResourceService) {
		this.resourceDiscoveryService = resourceDiscoveryService;
		this.monitoredResourceService = monitoredResourceService;
		this.serverResourceService = serverResourceService;
	}

	/** 고른 Resource와 지금 값입니다. 장비를 조회하지 않습니다. */
	@GetMapping
	List<ResourceUsageResponse> current(@PathVariable Long serverId) {
		return serverResourceService.currentUsage(serverId);
	}

	@PutMapping
	List<ResourceUsageResponse> replace(@PathVariable Long serverId,
			@Valid @RequestBody MonitoredResourceRequest request) {
		monitoredResourceService.replace(serverId, request);
		return serverResourceService.currentUsage(serverId);
	}

	/** 사용자가 누를 때만 장비에 SNMP로 묻습니다. 화면을 열 때마다 walk하지 않습니다. */
	@PostMapping("/discover")
	ResourceDiscoveryResult discover(@PathVariable Long serverId) {
		return resourceDiscoveryService.discover(serverId);
	}

}
