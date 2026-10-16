package kr.co.unidocs.monitoring.prometheus;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Prometheus의 http_sd_configs가 refresh_interval마다 호출합니다. 화면용 API가 아닙니다. */
@RestController
@RequestMapping("/api/prometheus/sd")
class PrometheusServiceDiscoveryController {

	private final BlackboxServiceDiscoveryService blackboxServiceDiscoveryService;

	private final OsExporterServiceDiscoveryService osExporterServiceDiscoveryService;

	PrometheusServiceDiscoveryController(BlackboxServiceDiscoveryService blackboxServiceDiscoveryService,
			OsExporterServiceDiscoveryService osExporterServiceDiscoveryService) {
		this.blackboxServiceDiscoveryService = blackboxServiceDiscoveryService;
		this.osExporterServiceDiscoveryService = osExporterServiceDiscoveryService;
	}

	@GetMapping("/blackbox")
	List<ServiceDiscoveryTarget> blackbox() {
		return blackboxServiceDiscoveryService.blackboxTargets();
	}

	@GetMapping("/os-exporter")
	List<ServiceDiscoveryTarget> osExporter() {
		return osExporterServiceDiscoveryService.osExporterTargets();
	}

}
