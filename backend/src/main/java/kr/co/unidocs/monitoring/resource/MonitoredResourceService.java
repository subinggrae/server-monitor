package kr.co.unidocs.monitoring.resource;

import kr.co.unidocs.monitoring.server.ServerNotFoundException;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자가 고른 Resource만 저장합니다. 다시 검색했다고 기존 선택을 지우지 않습니다.
 *
 * 선택에서 빠진 Resource는 모니터링을 그만두는 것이므로 삭제합니다.
 * 장비에서 사라진 Resource는 선택이 남아 있어도 값이 없어 UNAVAILABLE로 보입니다.
 */
@Service
public class MonitoredResourceService {

	private final ServerTargetRepository serverRepository;

	private final MonitoredResourceRepository monitoredResourceRepository;

	MonitoredResourceService(ServerTargetRepository serverRepository,
			MonitoredResourceRepository monitoredResourceRepository) {
		this.serverRepository = serverRepository;
		this.monitoredResourceRepository = monitoredResourceRepository;
	}

	@Transactional(readOnly = true)
	public List<MonitoredResource> findByServer(Long serverId) {
		requireServer(serverId);
		return monitoredResourceRepository.findByServerIdOrderByResourceTypeAscResourceKeyAsc(serverId);
	}

	@Transactional
	public List<MonitoredResource> replace(Long serverId, MonitoredResourceRequest request) {
		requireServer(serverId);

		Map<String, MonitoredResource> existing = new LinkedHashMap<>();
		for (MonitoredResource resource : monitoredResourceRepository
				.findByServerIdOrderByResourceTypeAscResourceKeyAsc(serverId)) {
			existing.put(key(resource.getResourceType(), resource.getResourceKey()), resource);
		}

		List<MonitoredResource> saved = new ArrayList<>();
		for (MonitoredResourceRequest.Item item : request.resources()) {
			String key = key(item.resourceType(), item.resourceKey());
			String displayName = (item.displayName() != null && !item.displayName().isBlank()) ? item.displayName()
					: item.resourceKey();

			MonitoredResource resource = existing.remove(key);
			if (resource == null) {
				resource = new MonitoredResource(serverId, item.resourceType(), item.resourceKey(), displayName,
						item.description(), item.snmpIndex());
			}
			else {
				// 다시 검색했을 때 index나 이름이 바뀌었을 수 있어 최신 값으로 맞춥니다.
				resource.refreshMetadata(displayName, item.description(), item.snmpIndex());
			}
			saved.add(monitoredResourceRepository.save(resource));
		}

		monitoredResourceRepository.deleteAll(existing.values());
		return saved;
	}

	private void requireServer(Long serverId) {
		if (!serverRepository.existsById(serverId)) {
			throw new ServerNotFoundException(serverId);
		}
	}

	private String key(ResourceType type, String resourceKey) {
		return type.name() + '\u0000' + resourceKey;
	}

}
