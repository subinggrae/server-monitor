package kr.co.unidocs.monitoring.server;

import kr.co.unidocs.monitoring.check.MonitoringStatus;
import kr.co.unidocs.monitoring.common.exception.DuplicateNameException;
import kr.co.unidocs.monitoring.prometheus.ServerSnmpMetrics;
import kr.co.unidocs.monitoring.prometheus.SnmpMetricQueryService;
import kr.co.unidocs.monitoring.prometheus.SnmpMetricSnapshot;
import kr.co.unidocs.monitoring.resource.MonitoredResourceRepository;
import kr.co.unidocs.monitoring.snmp.ServerSnmpConfig;
import kr.co.unidocs.monitoring.snmp.ServerSnmpConfigRepository;
import kr.co.unidocs.monitoring.snmp.SnmpAuthConfigSyncService;
import kr.co.unidocs.monitoring.snmp.SnmpState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

@Service
public class ServerTargetService {

	private final ServerTargetRepository serverRepository;

	private final ServerSnmpConfigRepository snmpConfigRepository;

	private final MonitoredResourceRepository monitoredResourceRepository;

	private final SnmpMetricQueryService snmpMetricQueryService;

	private final SnmpAuthConfigSyncService authConfigSyncService;

	ServerTargetService(ServerTargetRepository serverRepository, ServerSnmpConfigRepository snmpConfigRepository,
			MonitoredResourceRepository monitoredResourceRepository, SnmpMetricQueryService snmpMetricQueryService,
			SnmpAuthConfigSyncService authConfigSyncService) {
		this.serverRepository = serverRepository;
		this.snmpConfigRepository = snmpConfigRepository;
		this.monitoredResourceRepository = monitoredResourceRepository;
		this.snmpMetricQueryService = snmpMetricQueryService;
		this.authConfigSyncService = authConfigSyncService;
	}

	@Transactional(readOnly = true)
	public List<ServerTargetResponse> findAll() {
		// Server가 몇 대든 Prometheus는 한 번만 조회합니다.
		SnmpMetricSnapshot snapshot = snmpMetricQueryService.snapshot();

		return serverRepository.findAllByOrderByNameAsc().stream().map(server -> describe(server, snapshot)).toList();
	}

	@Transactional(readOnly = true)
	public ServerTargetResponse findById(Long serverId) {
		return describe(getServer(serverId), snmpMetricQueryService.snapshot());
	}

	@Transactional
	public ServerTargetResponse create(ServerTargetRequest request) {
		String name = request.name().trim();
		if (serverRepository.existsByName(name)) {
			throw new DuplicateNameException("이미 같은 이름의 서버가 있습니다. 다른 이름을 입력해 주세요.");
		}

		ServerTarget server = new ServerTarget(name, request.host().trim(), request.environment().trim(),
				trimToNull(request.description()), request.enabled());

		return withoutMetrics(serverRepository.save(server));
	}

	@Transactional
	public ServerTargetResponse update(Long serverId, ServerTargetRequest request) {
		ServerTarget server = getServer(serverId);

		String name = request.name().trim();
		if (!server.getName().equals(name) && serverRepository.existsByName(name)) {
			throw new DuplicateNameException("이미 같은 이름의 서버가 있습니다. 다른 이름을 입력해 주세요.");
		}

		server.update(name, request.host().trim(), request.environment().trim(), trimToNull(request.description()),
				request.enabled());

		return withoutMetrics(server);
	}

	/**
	 * 서버만 지웁니다.
	 * Blackbox Check는 여러 서버의 주소를 확인할 수 있어서 서버 한 대에 딸린 자원이 아닙니다.
	 *
	 * SNMP 설정과 고른 Resource는 서버에 딸린 값이라 함께 지웁니다.
	 * 실제 삭제는 foreign key의 ON DELETE CASCADE가 합니다.
	 */
	@Transactional
	public void delete(Long serverId) {
		serverRepository.delete(getServer(serverId));

		// Cascade로 SNMP 설정이 사라져도 exporter의 auth config에는 그대로 남습니다.
		// commit 뒤에 다시 만들어야 지운 서버의 community가 exporter에 남지 않습니다.
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				authConfigSyncService.sync();
			}
		});
	}

	private ServerTargetResponse describe(ServerTarget server, SnmpMetricSnapshot snapshot) {
		Optional<ServerSnmpConfig> config = snmpConfigRepository.findByServerId(server.getId());
		int resourceCount = monitoredResourceRepository
				.findByServerIdOrderByResourceTypeAscResourceKeyAsc(server.getId()).size();

		return ServerTargetResponse.of(server, statusOf(server, config, snapshot),
				snmpStateOf(server, config, snapshot), resourceCount);
	}

	/** 쓰기 요청에서는 Prometheus를 조회하지 않습니다. 목록을 다시 불러오면 값이 채워집니다. */
	private ServerTargetResponse withoutMetrics(ServerTarget server) {
		Optional<ServerSnmpConfig> config = snmpConfigRepository.findByServerId(server.getId());
		int resourceCount = monitoredResourceRepository
				.findByServerIdOrderByResourceTypeAscResourceKeyAsc(server.getId()).size();

		SnmpState state = config.map(value -> value.isEnabled() ? SnmpState.NO_RESPONSE : SnmpState.DISABLED)
				.orElse(SnmpState.NOT_CONFIGURED);
		MonitoringStatus status = server.isEnabled() ? MonitoringStatus.UNKNOWN : MonitoringStatus.DISABLED;

		return ServerTargetResponse.of(server, status, state, resourceCount);
	}

	/**
	 * Threshold가 아직 없습니다. 사용률이 높다는 이유로 CRITICAL을 만들지 않습니다.
	 * SNMP로 값을 읽고 있으면 OK, 설정이 없거나 값이 오지 않으면 UNKNOWN입니다.
	 */
	private MonitoringStatus statusOf(ServerTarget server, Optional<ServerSnmpConfig> config,
			SnmpMetricSnapshot snapshot) {
		if (!server.isEnabled()) {
			return MonitoringStatus.DISABLED;
		}
		if (config.isEmpty() || !config.get().isEnabled() || !snapshot.available()) {
			return MonitoringStatus.UNKNOWN;
		}

		ServerSnmpMetrics metrics = snapshot.of(server.getId());
		boolean collecting = metrics != null && metrics.isUp() && metrics.hasAnyValue();
		return collecting ? MonitoringStatus.OK : MonitoringStatus.UNKNOWN;
	}

	private SnmpState snmpStateOf(ServerTarget server, Optional<ServerSnmpConfig> config,
			SnmpMetricSnapshot snapshot) {
		if (config.isEmpty()) {
			return SnmpState.NOT_CONFIGURED;
		}
		if (!config.get().isEnabled() || !server.isEnabled()) {
			return SnmpState.DISABLED;
		}

		ServerSnmpMetrics metrics = snapshot.available() ? snapshot.of(server.getId()) : null;
		return (metrics != null && metrics.isUp()) ? SnmpState.CONNECTED : SnmpState.NO_RESPONSE;
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
