package kr.co.unidocs.monitoring.server;

import kr.co.unidocs.monitoring.common.exception.DuplicateNameException;
import kr.co.unidocs.monitoring.exporter.CollectionState;
import kr.co.unidocs.monitoring.exporter.ServerExporterConfig;
import kr.co.unidocs.monitoring.exporter.ServerExporterConfigRepository;
import kr.co.unidocs.monitoring.prometheus.CollectionSnapshot;
import kr.co.unidocs.monitoring.prometheus.ResourceMetricQueryService;
import kr.co.unidocs.monitoring.resource.MonitoredResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ServerTargetService {

	private final ServerTargetRepository serverRepository;

	private final ServerExporterConfigRepository exporterConfigRepository;

	private final MonitoredResourceRepository monitoredResourceRepository;

	private final ResourceMetricQueryService metricQueryService;

	ServerTargetService(ServerTargetRepository serverRepository,
			ServerExporterConfigRepository exporterConfigRepository,
			MonitoredResourceRepository monitoredResourceRepository, ResourceMetricQueryService metricQueryService) {
		this.serverRepository = serverRepository;
		this.exporterConfigRepository = exporterConfigRepository;
		this.monitoredResourceRepository = monitoredResourceRepository;
		this.metricQueryService = metricQueryService;
	}

	@Transactional(readOnly = true)
	public List<ServerTargetResponse> findAll() {
		// Server가 몇 대든 Prometheus는 한 번만 조회합니다.
		CollectionSnapshot snapshot = metricQueryService.collectionSnapshot();

		return serverRepository.findAllByOrderByNameAsc().stream().map(server -> describe(server, snapshot)).toList();
	}

	@Transactional(readOnly = true)
	public ServerTargetResponse findById(Long serverId) {
		return describe(getServer(serverId), metricQueryService.collectionSnapshot());
	}

	@Transactional
	public ServerTargetResponse create(ServerTargetRequest request) {
		String name = request.name().trim();
		if (serverRepository.existsByName(name)) {
			throw new DuplicateNameException("이미 같은 이름의 서버가 있습니다. 다른 이름을 입력해 주세요.");
		}

		ServerTarget server = new ServerTarget(name, request.host().trim(), request.environment().trim(),
				request.serverOs(), trimToNull(request.description()), request.enabled());

		return describe(serverRepository.save(server), null);
	}

	@Transactional
	public ServerTargetResponse update(Long serverId, ServerTargetRequest request) {
		ServerTarget server = getServer(serverId);

		String name = request.name().trim();
		if (!server.getName().equals(name) && serverRepository.existsByName(name)) {
			throw new DuplicateNameException("이미 같은 이름의 서버가 있습니다. 다른 이름을 입력해 주세요.");
		}

		server.update(name, request.host().trim(), request.environment().trim(), request.serverOs(),
				trimToNull(request.description()), request.enabled());

		return describe(server, null);
	}

	/**
	 * 서버만 지웁니다.
	 * Blackbox Check는 여러 서버의 주소를 확인할 수 있어서 서버 한 대에 딸린 자원이 아닙니다.
	 *
	 * 수집 설정과 고른 Resource는 서버에 딸린 값이라 함께 지웁니다.
	 * 실제 삭제는 foreign key의 ON DELETE CASCADE가 합니다.
	 */
	@Transactional
	public void delete(Long serverId) {
		serverRepository.delete(getServer(serverId));
	}

	/** 쓰기 요청에서는 snapshot 없이 부릅니다. Prometheus를 조회하지 않고, 목록을 다시 불러오면 값이 채워집니다. */
	private ServerTargetResponse describe(ServerTarget server, CollectionSnapshot snapshot) {
		Optional<ServerExporterConfig> config = exporterConfigRepository.findByServerId(server.getId());
		int resourceCount = monitoredResourceRepository
				.findByServerIdOrderByResourceTypeAscResourceKeyAsc(server.getId()).size();

		return ServerTargetResponse.of(server, collectionStateOf(server, config, snapshot), resourceCount);
	}

	private CollectionState collectionStateOf(ServerTarget server, Optional<ServerExporterConfig> config,
			CollectionSnapshot snapshot) {
		if (config.isEmpty()) {
			return CollectionState.NOT_CONFIGURED;
		}
		if (!config.get().isEnabled() || !server.isEnabled()) {
			return CollectionState.DISABLED;
		}
		if (snapshot == null || !snapshot.available()) {
			return CollectionState.UNKNOWN;
		}
		return snapshot.isUp(server.getId()) ? CollectionState.COLLECTING : CollectionState.FAILED;
	}

	private ServerTarget getServer(Long serverId) {
		return serverRepository.findById(serverId).orElseThrow(() -> new ServerNotFoundException(serverId));
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

}
