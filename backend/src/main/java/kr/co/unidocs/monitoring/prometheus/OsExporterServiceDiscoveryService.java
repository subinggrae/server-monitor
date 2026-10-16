package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.exporter.ServerExporterConfig;
import kr.co.unidocs.monitoring.exporter.ServerExporterConfigRepository;
import kr.co.unidocs.monitoring.server.ServerTarget;
import kr.co.unidocs.monitoring.server.ServerTargetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus가 OS exporter를 직접 수집할 서버 목록입니다. 기준은 Manager Database입니다.
 *
 * 고른 리소스가 없어도 넣습니다. 수집 상태(up)는 리소스를 고르기 전에도 보여야 합니다.
 * label을 더하거나 이름을 바꾸면 Prometheus time series가 끊깁니다.
 */
@Service
public class OsExporterServiceDiscoveryService {

	private final ServerTargetRepository serverRepository;

	private final ServerExporterConfigRepository configRepository;

	OsExporterServiceDiscoveryService(ServerTargetRepository serverRepository,
			ServerExporterConfigRepository configRepository) {
		this.serverRepository = serverRepository;
		this.configRepository = configRepository;
	}

	@Transactional(readOnly = true)
	public List<ServiceDiscoveryTarget> osExporterTargets() {
		List<ServiceDiscoveryTarget> discovered = new ArrayList<>();
		for (ServerExporterConfig config : configRepository.findByEnabledTrue()) {
			ServerTarget server = serverRepository.findById(config.getServerId()).orElse(null);
			if (server != null && server.isEnabled()) {
				discovered.add(toTarget(server, config));
			}
		}
		return discovered;
	}

	/**
	 * __scrape_interval__은 Prometheus가 이 서버의 수집 주기로 씁니다.
	 * __로 시작하는 label은 relabel 뒤에 지워져 time series에 남지 않습니다.
	 */
	private ServiceDiscoveryTarget toTarget(ServerTarget server, ServerExporterConfig config) {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("server_id", String.valueOf(server.getId()));
		labels.put("server_name", server.getName());
		labels.put("environment", server.getEnvironment());
		labels.put("os", server.getOs().name());
		labels.put("__scrape_interval__", config.getScrapeIntervalSeconds() + "s");

		return new ServiceDiscoveryTarget(List.of(server.getHost() + ":" + config.getPort()), labels);
	}

}
