package kr.co.unidocs.monitoring.check;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/checks")
class MonitoringCheckController {

	private final MonitoringCheckService monitoringCheckService;

	MonitoringCheckController(MonitoringCheckService monitoringCheckService) {
		this.monitoringCheckService = monitoringCheckService;
	}

	@GetMapping
	List<MonitoringCheckResponse> findAll() {
		return monitoringCheckService.findAll();
	}

	@GetMapping("/{checkId}")
	MonitoringCheckResponse findById(@PathVariable Long checkId) {
		return monitoringCheckService.findById(checkId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	MonitoringCheckResponse create(@Valid @RequestBody MonitoringCheckRequest request) {
		return monitoringCheckService.create(request);
	}

	@PutMapping("/{checkId}")
	MonitoringCheckResponse update(@PathVariable Long checkId, @Valid @RequestBody MonitoringCheckRequest request) {
		return monitoringCheckService.update(checkId, request);
	}

	@DeleteMapping("/{checkId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable Long checkId) {
		monitoringCheckService.delete(checkId);
	}

	@GetMapping("/{checkId}/status")
	CheckStatusResponse status(@PathVariable Long checkId) {
		return monitoringCheckService.status(checkId);
	}

}
