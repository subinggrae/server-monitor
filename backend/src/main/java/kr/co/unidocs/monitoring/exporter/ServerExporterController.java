package kr.co.unidocs.monitoring.exporter;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/servers/{serverId}/exporter")
class ServerExporterController {

	private final ServerExporterService serverExporterService;

	ServerExporterController(ServerExporterService serverExporterService) {
		this.serverExporterService = serverExporterService;
	}

	@GetMapping
	ServerExporterResponse find(@PathVariable Long serverId) {
		return serverExporterService.find(serverId);
	}

	@PutMapping
	ServerExporterResponse save(@PathVariable Long serverId, @Valid @RequestBody ServerExporterRequest request) {
		return serverExporterService.save(serverId, request);
	}

	@PostMapping("/test")
	ExporterTestResult test(@PathVariable Long serverId, @Valid @RequestBody ExporterTestRequest request) {
		return serverExporterService.test(serverId, request);
	}

}
