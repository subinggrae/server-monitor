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

	private final SnmpServiceDiscoveryService snmpServiceDiscoveryService;

	PrometheusServiceDiscoveryController(BlackboxServiceDiscoveryService blackboxServiceDiscoveryService,
			SnmpServiceDiscoveryService snmpServiceDiscoveryService) {
		this.blackboxServiceDiscoveryService = blackboxServiceDiscoveryService;
		this.snmpServiceDiscoveryService = snmpServiceDiscoveryService;
	}

	@GetMapping("/blackbox")
	List<ServiceDiscoveryTarget> blackbox() {
		return blackboxServiceDiscoveryService.blackboxTargets();
	}

	@GetMapping("/snmp")
	List<ServiceDiscoveryTarget> snmp() {
		return snmpServiceDiscoveryService.snmpTargets();
	}

}
