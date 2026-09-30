package kr.co.unidocs.monitoring.snmp;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/servers/{serverId}/snmp")
class ServerSnmpController {

	private final ServerSnmpService serverSnmpService;

	ServerSnmpController(ServerSnmpService serverSnmpService) {
		this.serverSnmpService = serverSnmpService;
	}

	@GetMapping
	ServerSnmpResponse find(@PathVariable Long serverId) {
		return serverSnmpService.find(serverId);
	}

	@PutMapping
	ServerSnmpResponse save(@PathVariable Long serverId, @Valid @RequestBody ServerSnmpRequest request) {
		return serverSnmpService.save(serverId, request);
	}

	@DeleteMapping
	ResponseEntity<Void> delete(@PathVariable Long serverId) {
		serverSnmpService.delete(serverId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/test")
	SnmpTestResult test(@PathVariable Long serverId) {
		return serverSnmpService.test(serverId);
	}

}
