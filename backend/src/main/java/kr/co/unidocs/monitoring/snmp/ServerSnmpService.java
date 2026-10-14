package kr.co.unidocs.monitoring.snmp;

import kr.co.unidocs.monitoring.common.security.SecretEncryptionService;
import kr.co.unidocs.monitoring.server.ServerNotFoundException;
import kr.co.unidocs.monitoring.server.ServerTarget;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * SNMP 설정의 Source of Truth는 Manager Database입니다.
 * snmp_exporter의 auth config는 저장한 뒤에 여기에서 만들어 내보냅니다.
 */
@Service
public class ServerSnmpService {

	private final ServerTargetRepository serverRepository;

	private final ServerSnmpConfigRepository snmpConfigRepository;

	private final ServerSnmpConfigStore configStore;

	private final SecretEncryptionService secretEncryptionService;

	private final SnmpAuthConfigSyncService authConfigSyncService;

	private final SnmpClient snmpClient;

	ServerSnmpService(ServerTargetRepository serverRepository, ServerSnmpConfigRepository snmpConfigRepository,
			ServerSnmpConfigStore configStore, SecretEncryptionService secretEncryptionService,
			SnmpAuthConfigSyncService authConfigSyncService, SnmpClient snmpClient) {
		this.serverRepository = serverRepository;
		this.snmpConfigRepository = snmpConfigRepository;
		this.configStore = configStore;
		this.secretEncryptionService = secretEncryptionService;
		this.authConfigSyncService = authConfigSyncService;
		this.snmpClient = snmpClient;
	}

	@Transactional(readOnly = true)
	public ServerSnmpResponse find(Long serverId) {
		requireServer(serverId);
		return snmpConfigRepository.findByServerId(serverId)
				.map(config -> ServerSnmpResponse.of(config, authConfigSyncService.lastStatus()))
				.orElseGet(ServerSnmpResponse::notConfigured);
	}

	public ServerSnmpResponse save(Long serverId, ServerSnmpRequest request) {
		ServerSnmpConfig saved = configStore.save(serverId, request);

		// DB는 이미 커밋됐습니다. exporter 반영이 실패해도 저장을 되돌리지 않고 상태로 알립니다.
		return ServerSnmpResponse.of(saved, authConfigSyncService.sync());
	}

	public void delete(Long serverId) {
		configStore.delete(serverId);
		authConfigSyncService.sync();
	}

	/** Prometheus를 거치지 않고 Manager가 직접 장비에 묻습니다. */
	@Transactional(readOnly = true)
	public SnmpTestResult test(Long serverId) {
		Optional<SnmpTarget> target = snmpTarget(serverId);
		if (target.isEmpty()) {
			return SnmpTestResult.failed("SNMP 설정이 없습니다. 먼저 SNMP를 설정해 주세요.");
		}
		return SnmpTestResult.of(snmpClient.probe(target.get()));
	}

	@Transactional(readOnly = true)
	public Optional<SnmpTarget> snmpTarget(Long serverId) {
		ServerTarget server = requireServer(serverId);
		return snmpConfigRepository.findByServerId(serverId)
				.map(config -> new SnmpTarget(server.getHost(), config.getPort(), config.getVersion(),
						secretEncryptionService.decrypt(config.getCommunityCipher())));
	}

	private ServerTarget requireServer(Long serverId) {
		return serverRepository.findById(serverId).orElseThrow(() -> new ServerNotFoundException(serverId));
	}

}
