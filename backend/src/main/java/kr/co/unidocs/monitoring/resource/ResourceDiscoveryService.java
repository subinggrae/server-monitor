package kr.co.unidocs.monitoring.resource;

import kr.co.unidocs.monitoring.snmp.ServerSnmpService;
import kr.co.unidocs.monitoring.snmp.SnmpClient;
import kr.co.unidocs.monitoring.snmp.SnmpStorageEntry;
import kr.co.unidocs.monitoring.snmp.SnmpTarget;
import kr.co.unidocs.monitoring.snmp.SnmpWalkResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resource Discovery는 Manager가 장비에 직접 SNMP로 묻습니다.
 * snmp_exporter는 Prometheus가 가져갈 metric만 내줍니다. Discovery API가 아닙니다.
 *
 * 발견 결과는 저장하지 않습니다. 무엇을 모니터링할지는 사용자가 고른 뒤에 저장합니다.
 */
@Service
public class ResourceDiscoveryService {

	/** CPU와 Memory는 서버마다 하나뿐이라 key를 고정합니다. Storage만 장비가 준 이름을 씁니다. */
	public static final String CPU_RESOURCE_KEY = "cpu";

	public static final String MEMORY_RESOURCE_KEY = "memory";

	private final ServerSnmpService serverSnmpService;

	private final SnmpClient snmpClient;

	private final MonitoredResourceRepository monitoredResourceRepository;

	ResourceDiscoveryService(ServerSnmpService serverSnmpService, SnmpClient snmpClient,
			MonitoredResourceRepository monitoredResourceRepository) {
		this.serverSnmpService = serverSnmpService;
		this.snmpClient = snmpClient;
		this.monitoredResourceRepository = monitoredResourceRepository;
	}

	@Transactional(readOnly = true)
	public ResourceDiscoveryResult discover(Long serverId) {
		Optional<SnmpTarget> target = serverSnmpService.snmpTarget(serverId);
		if (target.isEmpty()) {
			return ResourceDiscoveryResult.failed("SNMP 설정이 없습니다. 먼저 SNMP를 설정해 주세요.");
		}

		SnmpWalkResult walk = snmpClient.discover(target.get());
		if (!walk.reachable()) {
			return ResourceDiscoveryResult.failed("SNMP 응답을 받지 못했습니다. Host, Port, Community와 네트워크 접근을 확인해 주세요.");
		}

		Set<String> alreadySelected = selectedKeys(serverId);
		List<DiscoveredResource> resources = new ArrayList<>();

		if (walk.processorCount() > 0) {
			resources.add(new DiscoveredResource(ResourceType.CPU, CPU_RESOURCE_KEY, "CPU",
					walk.processorCount() + "개 processor", null, null,
					alreadySelected.contains(key(ResourceType.CPU, CPU_RESOURCE_KEY))));
		}

		findPhysicalMemory(walk.storageEntries()).ifPresent(memory -> resources
				.add(new DiscoveredResource(ResourceType.MEMORY, MEMORY_RESOURCE_KEY, "Memory", memory.description(),
						memory.index(), memory.totalBytes(),
						alreadySelected.contains(key(ResourceType.MEMORY, MEMORY_RESOURCE_KEY)))));

		for (SnmpStorageEntry entry : walk.storageEntries()) {
			if (!entry.isDisk()) {
				continue;
			}
			resources.add(new DiscoveredResource(ResourceType.STORAGE, entry.description(), entry.description(),
					entry.description(), entry.index(), entry.totalBytes(),
					alreadySelected.contains(key(ResourceType.STORAGE, entry.description()))));
		}

		String message = resources.isEmpty()
				? "SNMP 응답은 받았지만 CPU, Memory, Storage 정보를 찾지 못했습니다. 장비가 HOST-RESOURCES-MIB를 제공하는지 확인해 주세요."
				: "Resource를 찾았습니다.";
		return new ResourceDiscoveryResult(true, message, walk.systemInfo().systemName(),
				walk.systemInfo().systemDescription(), List.copyOf(resources));
	}

	/**
	 * hrStorageTable에는 buffer, cache, swap도 함께 들어옵니다.
	 * 첫 행을 memory로 보면 안 되고 hrStorageType이 RAM인 행만 골라야 합니다.
	 */
	private Optional<SnmpStorageEntry> findPhysicalMemory(List<SnmpStorageEntry> entries) {
		return entries.stream().filter(SnmpStorageEntry::isPhysicalMemory).findFirst();
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
