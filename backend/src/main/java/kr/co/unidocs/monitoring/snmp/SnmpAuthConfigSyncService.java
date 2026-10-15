package kr.co.unidocs.monitoring.snmp;

import kr.co.unidocs.monitoring.common.security.SecretEncryptionService;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * snmp_exporter는 scrape 요청으로 community를 받지 않습니다.
 * exporter config의 auths에 미리 있는 이름만 auth parameter로 고를 수 있습니다.
 *
 * 그래서 Manager Database를 기준으로 auth만 담긴 config 파일을 만들고 exporter에 다시 읽으라고 알립니다.
 * module 정의는 exporter image의 기본 config에 그대로 둡니다. exporter가 두 파일을 합쳐서 읽습니다.
 *
 * 이 파일에는 community 평문이 들어갑니다. 내용은 로그에 남기지 않습니다. (CLAUDE.md 11번)
 */
@Service
public class SnmpAuthConfigSyncService {

	private static final Logger log = LoggerFactory.getLogger(SnmpAuthConfigSyncService.class);

	private final ServerSnmpConfigRepository snmpConfigRepository;

	private final SecretEncryptionService secretEncryptionService;

	private final SnmpExporterReloader reloader;

	private final Path authConfigPath;

	private final AtomicReference<SnmpSyncStatus> lastStatus = new AtomicReference<>(SnmpSyncStatus.notSyncedYet());

	SnmpAuthConfigSyncService(ServerSnmpConfigRepository snmpConfigRepository,
			SecretEncryptionService secretEncryptionService, SnmpExporterReloader reloader,
			MonitoringManagerProperties properties) {
		this.snmpConfigRepository = snmpConfigRepository;
		this.secretEncryptionService = secretEncryptionService;
		this.reloader = reloader;
		this.authConfigPath = Path.of(properties.snmp().authConfigPath());
	}

	public SnmpSyncStatus lastStatus() {
		return lastStatus.get();
	}

	/**
	 * Manager가 꺼져 있는 동안 runtime volume이 새로 만들어졌을 수 있습니다.
	 * 기준은 Manager Database이므로 기동할 때 파일을 다시 만듭니다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	void syncOnStartup() {
		sync();
	}

	/**
	 * DB는 이미 저장된 상태에서 호출합니다. 여기에서 실패해도 저장을 되돌리지 않습니다.
	 * 대신 실패를 기록해서 화면과 로그에서 확인할 수 있게 합니다.
	 */
	@Transactional(readOnly = true)
	public SnmpSyncStatus sync() {
		List<ServerSnmpConfig> configs = snmpConfigRepository.findByEnabledTrue();

		try {
			writeAtomically(render(configs));
		}
		catch (IOException ex) {
			return record(SnmpSyncStatus.failed("수집 설정을 적용하지 못했습니다. 계속되면 Monitoring Manager 로그에서 SNMP 설정 파일 경로와 권한을 확인해 주세요."), configs.size(), ex);
		}

		try {
			reloader.reload();
		}
		catch (Exception ex) {
			return record(SnmpSyncStatus.failed("수집 설정을 적용하지 못했습니다. 계속되면 snmp-exporter가 실행 중인지 확인해 주세요."),
					configs.size(), ex);
		}

		log.info("snmp_exporter auth config를 반영했습니다. authCount={}", configs.size());
		return record(SnmpSyncStatus.succeeded(configs.size()), configs.size(), null);
	}

	/**
	 * exporter가 쓰는 도중의 파일을 읽지 않도록 임시 파일에 먼저 쓰고 한 번에 옮깁니다.
	 * community가 들어 있어 소유자만 읽을 수 있게 둡니다.
	 */
	private void writeAtomically(String content) throws IOException {
		Path directory = authConfigPath.toAbsolutePath().getParent();
		if (directory != null) {
			Files.createDirectories(directory);
		}

		Path temporary = authConfigPath.resolveSibling(authConfigPath.getFileName() + ".tmp");
		Files.writeString(temporary, content, StandardCharsets.UTF_8);
		restrictPermissions(temporary);

		try {
			Files.move(temporary, authConfigPath, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ex) {
			// Windows 개발 환경처럼 atomic move를 못 쓰는 파일 시스템이 있습니다.
			Files.move(temporary, authConfigPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void restrictPermissions(Path path) {
		try {
			Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		}
		catch (IOException | UnsupportedOperationException ex) {
			// Windows에는 POSIX 권한이 없습니다. 운영은 Linux container이므로 여기에서 실패해도 넘어갑니다.
		}
	}

	private String render(List<ServerSnmpConfig> configs) {
		StringBuilder yaml = new StringBuilder();
		yaml.append("# Monitoring Manager가 만드는 파일입니다. 직접 고치지 마세요.\n");
		yaml.append("# Manager Database의 SNMP 설정을 저장할 때마다 다시 만들어집니다.\n");
		yaml.append("auths:\n");

		if (configs.isEmpty()) {
			// auths가 비어 있어도 exporter는 정적 module config의 기본 auth로 뜹니다.
			yaml.append("  {}\n");
			return yaml.toString();
		}

		for (ServerSnmpConfig config : configs) {
			String community = secretEncryptionService.decrypt(config.getCommunityCipher());
			yaml.append("  ").append(config.authName()).append(":\n");
			yaml.append("    community: ").append(quote(community)).append('\n');
			yaml.append("    version: ").append(config.getVersion().exporterVersion()).append('\n');
		}
		return yaml.toString();
	}

	/** community에 어떤 문자가 들어와도 YAML 구조가 깨지지 않게 합니다. */
	private String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private SnmpSyncStatus record(SnmpSyncStatus status, int authCount, Exception ex) {
		lastStatus.set(status);
		if (!status.succeeded()) {
			log.warn("snmp_exporter auth config를 반영하지 못했습니다. authCount={} reason={}", authCount,
					(ex != null) ? ex.getClass().getSimpleName() : status.message());
		}
		return status;
	}

}
