package kr.co.unidocs.monitoring.exporter;

import kr.co.unidocs.monitoring.server.ServerNotFoundException;
import kr.co.unidocs.monitoring.server.ServerTarget;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 수집 설정의 기준은 Manager Database입니다. Prometheus는 HTTP SD로 이 값을 받아 갑니다. */
@Service
public class ServerExporterService {

	private final ServerTargetRepository serverRepository;

	private final ServerExporterConfigRepository configRepository;

	private final ExporterClient exporterClient;

	ServerExporterService(ServerTargetRepository serverRepository, ServerExporterConfigRepository configRepository,
			ExporterClient exporterClient) {
		this.serverRepository = serverRepository;
		this.configRepository = configRepository;
		this.exporterClient = exporterClient;
	}

	@Transactional(readOnly = true)
	public ServerExporterResponse find(Long serverId) {
		ServerTarget server = requireServer(serverId);
		int defaultPort = server.getOs().defaultExporterPort();
		return configRepository.findByServerId(serverId)
			.map(config -> toResponse(config, defaultPort))
			.orElseGet(() -> new ServerExporterResponse(false, false, defaultPort,
					ServerExporterConfig.DEFAULT_SCRAPE_INTERVAL_SECONDS, defaultPort));
	}

	@Transactional
	public ServerExporterResponse save(Long serverId, ServerExporterRequest request) {
		ServerTarget server = requireServer(serverId);
		ServerExporterConfig config = configRepository.findByServerId(serverId).orElse(null);
		if (config == null) {
			config = new ServerExporterConfig(serverId, request.enabled(), request.port(),
					request.scrapeIntervalSecondsAsInt());
		}
		else {
			config.update(request.enabled(), request.port(), request.scrapeIntervalSecondsAsInt());
		}
		return toResponse(configRepository.save(config), server.getOs().defaultExporterPort());
	}

	/** Prometheus를 거치지 않고 Manager가 exporter를 직접 한 번 읽습니다. 호스트는 저장된 서버의 값입니다. */
	@Transactional(readOnly = true)
	public ExporterTestResult test(Long serverId, ExporterTestRequest request) {
		ServerTarget server = requireServer(serverId);
		return ExporterTestResult.of(exporterClient.read(server.getHost(), request.port()), server.getOs());
	}

	@Transactional(readOnly = true)
	public Optional<ExporterTarget> enabledTarget(Long serverId) {
		ServerTarget server = requireServer(serverId);
		return configRepository.findByServerId(serverId)
			.filter(ServerExporterConfig::isEnabled)
			.map(config -> new ExporterTarget(server.getHost(), config.getPort()));
	}

	private static ServerExporterResponse toResponse(ServerExporterConfig config, int defaultPort) {
		return new ServerExporterResponse(true, config.isEnabled(), config.getPort(), config.getScrapeIntervalSeconds(),
				defaultPort);
	}

	private ServerTarget requireServer(Long serverId) {
		return serverRepository.findById(serverId).orElseThrow(() -> new ServerNotFoundException(serverId));
	}

}
