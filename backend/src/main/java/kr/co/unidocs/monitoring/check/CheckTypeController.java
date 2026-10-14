package kr.co.unidocs.monitoring.check;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/check-types")
class CheckTypeController {

	@GetMapping
	List<CheckTypeResponse> findAll() {
		return Arrays.stream(CheckType.values())
				.map(type -> new CheckTypeResponse(type.name(), type.displayName(), type.requiresPort(),
						type.requiresUrl()))
				.toList();
	}

	record CheckTypeResponse(String type, String displayName, boolean requiresPort, boolean requiresUrl) {
	}

}
