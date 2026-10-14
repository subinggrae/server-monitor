package kr.co.unidocs.monitoring.snmp;

import kr.co.unidocs.monitoring.common.security.SecretEncryptionService;
import kr.co.unidocs.monitoring.server.ServerNotFoundException;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * exporter 반영은 DB commit이 끝난 뒤에 해야 합니다.
 * 그래서 transaction 경계를 가진 저장만 여기에 두고 반영은 호출하는 쪽에서 합니다.
 */
@Component
class ServerSnmpConfigStore {

	private final ServerTargetRepository serverRepository;

	private final ServerSnmpConfigRepository snmpConfigRepository;

	private final SecretEncryptionService secretEncryptionService;

	ServerSnmpConfigStore(ServerTargetRepository serverRepository, ServerSnmpConfigRepository snmpConfigRepository,
			SecretEncryptionService secretEncryptionService) {
		this.serverRepository = serverRepository;
		this.snmpConfigRepository = snmpConfigRepository;
		this.secretEncryptionService = secretEncryptionService;
	}

	@Transactional
	ServerSnmpConfig save(Long serverId, ServerSnmpRequest request) {
		requireServer(serverId);

		String communityCipher = request.hasNewCommunity()
				? secretEncryptionService.encrypt(request.community().trim()) : null;

		Optional<ServerSnmpConfig> existing = snmpConfigRepository.findByServerId(serverId);
		if (existing.isEmpty() && communityCipher == null) {
			throw new SnmpConfigurationException("Community를 입력해 주세요.");
		}

		if (existing.isPresent()) {
			ServerSnmpConfig config = existing.get();
			config.update(request.version(), request.port(), communityCipher, request.enabled(),
					request.scrapeIntervalSeconds());
			return snmpConfigRepository.save(config);
		}

		return snmpConfigRepository.save(new ServerSnmpConfig(serverId, request.version(), request.port(),
				communityCipher, request.enabled(), request.scrapeIntervalSeconds()));
	}

	@Transactional
	void delete(Long serverId) {
		requireServer(serverId);
		snmpConfigRepository.deleteByServerId(serverId);
	}

	private void requireServer(Long serverId) {
		if (!serverRepository.existsById(serverId)) {
			throw new ServerNotFoundException(serverId);
		}
	}

}
