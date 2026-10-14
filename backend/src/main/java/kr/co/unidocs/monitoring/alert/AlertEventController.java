package kr.co.unidocs.monitoring.alert;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
class AlertEventController {

	private static final int DEFAULT_LIMIT = 50;

	private static final int MAX_LIMIT = 200;

	private final AlertEventService alertEventService;

	AlertEventController(AlertEventService alertEventService) {
		this.alertEventService = alertEventService;
	}

	@GetMapping
	List<AlertEventResponse> findRecent(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
		return alertEventService.findRecent(Math.clamp(limit, 1, MAX_LIMIT));
	}

}
