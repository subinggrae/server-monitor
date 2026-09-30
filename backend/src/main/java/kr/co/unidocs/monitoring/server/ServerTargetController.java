package kr.co.unidocs.monitoring.server;

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

/** Blackbox Check는 Server 소유가 아니므로 여기에서 다루지 않습니다. Check는 /api/checks에 있습니다. */
@RestController
@RequestMapping("/api/servers")
class ServerTargetController {

	private final ServerTargetService serverTargetService;

	ServerTargetController(ServerTargetService serverTargetService) {
		this.serverTargetService = serverTargetService;
	}

	@GetMapping
	List<ServerTargetResponse> findAll() {
		return serverTargetService.findAll();
	}

	@GetMapping("/{serverId}")
	ServerTargetResponse findById(@PathVariable Long serverId) {
		return serverTargetService.findById(serverId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ServerTargetResponse create(@Valid @RequestBody ServerTargetRequest request) {
		return serverTargetService.create(request);
	}

	@PutMapping("/{serverId}")
	ServerTargetResponse update(@PathVariable Long serverId, @Valid @RequestBody ServerTargetRequest request) {
		return serverTargetService.update(serverId, request);
	}

	@DeleteMapping("/{serverId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable Long serverId) {
		serverTargetService.delete(serverId);
	}

}
