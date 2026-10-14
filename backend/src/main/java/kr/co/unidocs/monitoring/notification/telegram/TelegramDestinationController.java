package kr.co.unidocs.monitoring.notification.telegram;

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
@RequestMapping("/api/telegram")
class TelegramDestinationController {

	private final TelegramDestinationService destinationService;

	private final TelegramStatusService statusService;

	TelegramDestinationController(TelegramDestinationService destinationService, TelegramStatusService statusService) {
		this.destinationService = destinationService;
		this.statusService = statusService;
	}

	@GetMapping("/destinations")
	List<TelegramDestinationResponse> findAll() {
		return destinationService.findAll();
	}

	@GetMapping("/destinations/{destinationId}")
	TelegramDestinationResponse findById(@PathVariable Long destinationId) {
		return destinationService.findById(destinationId);
	}

	@PostMapping("/destinations")
	@ResponseStatus(HttpStatus.CREATED)
	TelegramDestinationResponse create(@Valid @RequestBody TelegramDestinationRequest request) {
		return destinationService.create(request);
	}

	@PutMapping("/destinations/{destinationId}")
	TelegramDestinationResponse update(@PathVariable Long destinationId,
			@Valid @RequestBody TelegramDestinationRequest request) {
		return destinationService.update(destinationId, request);
	}

	@DeleteMapping("/destinations/{destinationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable Long destinationId) {
		destinationService.delete(destinationId);
	}

	/**
	 * 전송에 실패해도 HTTP 오류로 돌려주지 않습니다.
	 * "연결을 확인한 결과"를 알려 주는 API이고 그 결과에는 실패도 포함됩니다.
	 *
	 * 실패 이유에 예외 이름이나 Bot Token이 들어가지 않게 고정 문구를 씁니다.
	 */
	@PostMapping("/destinations/{destinationId}/test")
	TestMessageResponse sendTestMessage(@PathVariable Long destinationId) {
		TelegramSendResult result = destinationService.sendTestMessage(destinationId);

		if (result.success()) {
			return new TestMessageResponse(true, "테스트 메시지를 보냈습니다.");
		}
		return new TestMessageResponse(false, "Telegram 메시지를 보내지 못했습니다. Bot 설정과 그룹 권한을 확인해 주세요.");
	}

	@GetMapping("/status")
	TelegramStatusService.TelegramStatus status() {
		return statusService.currentStatus();
	}

	record TestMessageResponse(boolean success, String message) {
	}

}
