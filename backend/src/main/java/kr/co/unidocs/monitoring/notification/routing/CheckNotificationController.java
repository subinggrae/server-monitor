package kr.co.unidocs.monitoring.notification.routing;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class CheckNotificationController {

	private final CheckNotificationService checkNotificationService;

	CheckNotificationController(CheckNotificationService checkNotificationService) {
		this.checkNotificationService = checkNotificationService;
	}

	@GetMapping("/api/checks/{checkId}/notifications")
	CheckNotificationResponse findByCheck(@PathVariable Long checkId) {
		return checkNotificationService.findByCheck(checkId);
	}

	@PutMapping("/api/checks/{checkId}/notifications")
	CheckNotificationResponse replace(@PathVariable Long checkId, @Valid @RequestBody CheckNotificationRequest request) {
		return checkNotificationService.replace(checkId, request);
	}

	@GetMapping("/api/notifications/bindings")
	List<CheckNotificationSummaryResponse> findAll() {
		return checkNotificationService.findAll();
	}

}
