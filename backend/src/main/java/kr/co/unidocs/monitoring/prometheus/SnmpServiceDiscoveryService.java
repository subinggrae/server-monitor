package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.resource.MonitoredResource;
import kr.co.unidocs.monitoring.resource.MonitoredResourceRepository;
import kr.co.unidocs.monitoring.resource.ResourceType;
import kr.co.unidocs.monitoring.server.ServerTarget;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import kr.co.unidocs.monitoring.snmp.ServerSnmpConfig;
import kr.co.unidocs.monitoring.snmp.ServerSnmpConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * snmp_exporter로 수집할 Server 목록입니다. 기준은 Manager Database입니다. (CLAUDE.md 1번, 2번)
 *
 * community는 label에 넣지 않습니다. exporter가 auth 이름으로 자기 config에서 찾습니다.
 * Prometheus에 secret이 남으면 지울 방법이 없습니다.
 */
@Service
public class SnmpServiceDiscoveryService {

	private static final String SYSTEM_MODULE = "system";

	private static final String PROCESSOR_MODULE = "hrDevice";

	private static final String STORAGE_MODULE = "hrStorage";

	private final ServerTargetRepository serverRepository;

	private final ServerSnmpConfigRepository snmpConfigRepository;

	private final MonitoredResourceRepository monitoredResourceRepository;

	SnmpServiceDiscoveryService(ServerTargetRepository serverRepository,
			ServerSnmpConfigRepository snmpConfigRepository,
			MonitoredResourceRepository monitoredResourceRepository) {
		this.serverRepository = serverRepository;
		this.snmpConfigRepository = snmpConfigRepository;
		this.monitoredResourceRepository = monitoredResourceRepository;
	}

	@Transactional(readOnly = true)
	public List<ServiceDiscoveryTarget> snmpTargets() {
		List<ServiceDiscoveryTarget> discovered = new ArrayList<>();

		for (ServerSnmpConfig config : snmpConfigRepository.findByEnabledTrue()) {
			ServerTarget server = serverRepository.findById(config.getServerId()).orElse(null);
			if (server == null || !server.isEnabled()) {
				continue;
			}

			Set<ResourceType> types = monitoredTypes(config.getServerId());
			if (types.isEmpty()) {
				// 고른 Resource가 없으면 수집할 것도 없습니다.
				continue;
			}

			discovered.add(toTarget(server, config, types));
		}

		return discovered;
	}

	private Set<ResourceType> monitoredTypes(Long serverId) {
		Set<ResourceType> types = EnumSet.noneOf(ResourceType.class);
		for (MonitoredResource resource : monitoredResourceRepository
				.findByServerIdOrderByResourceTypeAscResourceKeyAsc(serverId)) {
			if (resource.isEnabled()) {
				types.add(resource.getResourceType());
			}
		}
		return types;
	}

	private ServiceDiscoveryTarget toTarget(ServerTarget server, ServerSnmpConfig config, Set<ResourceType> types) {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("server_id", String.valueOf(server.getId()));
		labels.put("server_name", server.getName());
		labels.put("environment", server.getEnvironment());
		labels.put("snmp_auth", config.authName());
		labels.put("snmp_modules", modulesFor(types));

		return new ServiceDiscoveryTarget(List.of(server.getHost() + ":" + config.getPort()), labels);
	}

	/** 고른 Resource에 필요한 module만 넘깁니다. 쓰지 않는 table까지 walk하면 장비 부담만 커집니다. */
	private String modulesFor(Set<ResourceType> types) {
		List<String> modules = new ArrayList<>();
		modules.add(SYSTEM_MODULE);
		if (types.contains(ResourceType.CPU)) {
			modules.add(PROCESSOR_MODULE);
		}
		if (types.contains(ResourceType.MEMORY) || types.contains(ResourceType.STORAGE)) {
			modules.add(STORAGE_MODULE);
		}
		return String.join(",", modules);
	}

}
