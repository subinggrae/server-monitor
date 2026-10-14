package kr.co.unidocs.monitoring.notification.telegram;

import kr.co.unidocs.monitoring.config.ManagerSecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Telegram 서버가 직접 부르는 endpoint입니다. 외부에 열리므로 secret 검증이 중요합니다.
 * secret이 설정되어 있으면 반드시 검증하고, secret 값 자체는 로그에 남기지 않습니다.
 */
@RestController
@RequestMapping("/api/integrations/telegram")
class TelegramWebhookController {

	private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

	private static final String REGISTER_COMMAND = "/register";

	private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

	private final TelegramDestinationService destinationService;

	private final TelegramClient telegramClient;

	private final ManagerSecrets secrets;

	TelegramWebhookController(TelegramDestinationService destinationService, TelegramClient telegramClient,
			ManagerSecrets secrets) {
		this.destinationService = destinationService;
		this.telegramClient = telegramClient;
		this.secrets = secrets;
	}

	@PostMapping("/webhook")
	ResponseEntity<Void> receive(@RequestBody(required = false) TelegramUpdate update,
			@RequestHeader(name = SECRET_HEADER, required = false) String secretHeader) {

		if (!secretMatches(secretHeader)) {
			log.warn("Telegram webhook 요청의 secret이 맞지 않아 거부했습니다.");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}

		// 처리하지 않는 Update도 정상 응답으로 끝냅니다. 실패를 돌려주면 Telegram이 다시 보냅니다.
		handle(update);
		return ResponseEntity.ok().build();
	}

	private void handle(TelegramUpdate update) {
		if (update == null || update.message() == null) {
			return;
		}

		TelegramUpdate.Message message = update.message();
		TelegramUpdate.Chat chat = message.chat();
		if (chat == null || chat.id() == null || !isRegisterCommand(message.text())) {
			return;
		}

		if (!chat.isGroup()) {
			log.info("그룹이 아닌 곳에서 /register를 받아 무시했습니다. chat_type={}", chat.type());
			return;
		}

		Integer messageThreadId = chat.forum() ? message.messageThreadId() : null;
		String name = groupName(chat);

		boolean created = destinationService.registerFromTelegram(chat.id(), messageThreadId, name);

		String reply = created ? "Monitoring Manager에 알림 그룹으로 등록했습니다."
				: "이미 등록된 알림 그룹입니다.";
		telegramClient.sendMessage(chat.id(), messageThreadId, reply);
	}

	/** 그룹에 Bot이 여러 개면 사용자는 /register@botname 형태로 보냅니다. */
	private boolean isRegisterCommand(String text) {
		if (text == null) {
			return false;
		}

		String firstToken = text.trim().split("\\s+")[0];
		int botNameAt = firstToken.indexOf('@');
		String command = (botNameAt >= 0) ? firstToken.substring(0, botNameAt) : firstToken;

		return REGISTER_COMMAND.equals(command);
	}

	private String groupName(TelegramUpdate.Chat chat) {
		if (chat.title() != null && !chat.title().isBlank()) {
			return chat.title().trim();
		}
		// 이름을 몰라도 등록은 되어야 합니다. 나중에 화면에서 바꿀 수 있습니다.
		return "Telegram " + chat.id();
	}

	private boolean secretMatches(String secretHeader) {
		String expected = secrets.telegramWebhookSecret();
		if (expected == null) {
			return true;
		}
		return expected.equals(secretHeader);
	}

}
