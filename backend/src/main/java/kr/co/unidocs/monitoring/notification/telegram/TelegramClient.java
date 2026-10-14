package kr.co.unidocs.monitoring.notification.telegram;

import kr.co.unidocs.monitoring.config.ManagerSecrets;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bot Token이 URL 경로에 들어갑니다.
 * 밖으로 나가는 문자열에는 요청 URL이 섞일 수 있으므로 {@link #withoutToken}을 거칩니다. (CLAUDE.md 11번)
 */
@Component
public class TelegramClient {

	private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

	/** error_message column 크기 안에서만 남깁니다. */
	private static final int MAX_ERROR_LENGTH = 400;

	private final RestClient restClient;

	private final String apiBaseUrl;

	private final ManagerSecrets secrets;

	TelegramClient(RestClient restClient, MonitoringManagerProperties properties, ManagerSecrets secrets) {
		this.restClient = restClient;
		this.apiBaseUrl = withoutTrailingSlash(properties.telegram().apiBaseUrl());
		this.secrets = secrets;
	}

	public boolean configured() {
		return secrets.telegramBotTokenConfigured();
	}

	public TelegramSendResult sendMessage(long chatId, Integer messageThreadId, String text) {
		String token = secrets.telegramBotToken();
		if (token == null) {
			return TelegramSendResult.failure("Telegram Bot Token이 설정되지 않았습니다. "
					+ "Docker Secret(monitoring-manager.telegram.bot-token) 또는 TELEGRAM_BOT_TOKEN을 확인해 주세요.");
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("chat_id", chatId);
		if (messageThreadId != null) {
			payload.put("message_thread_id", messageThreadId);
		}
		payload.put("text", text);

		return call(token, "sendMessage", payload);
	}

	/** {@code available}은 상태를 확인했는지 여부입니다. "등록되지 않았다"와 "확인하지 못했다"는 다릅니다. */
	public WebhookInfo webhookInfo() {
		String token = secrets.telegramBotToken();
		if (token == null) {
			return WebhookInfo.unavailable();
		}

		try {
			ResponseEntity<GetWebhookInfoResponse> response = restClient.get()
					.uri(methodUri(token, "getWebhookInfo"))
					.retrieve()
					.onStatus(status -> true, (request, clientResponse) -> {
					})
					.toEntity(GetWebhookInfoResponse.class);

			GetWebhookInfoResponse body = response.getBody();
			if (body == null || !body.ok() || body.result() == null) {
				return WebhookInfo.unavailable();
			}

			String url = body.result().url();
			return new WebhookInfo(true, url != null && !url.isBlank());
		}
		catch (Exception ex) {
			log.warn("Telegram webhook 상태를 확인하지 못했습니다. reason={}", withoutToken(describe(ex), token));
			return WebhookInfo.unavailable();
		}
	}

	public TelegramSendResult setWebhook(String url, String secretToken) {
		String token = secrets.telegramBotToken();
		if (token == null) {
			return TelegramSendResult.failure("Telegram Bot Token이 설정되지 않았습니다.");
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("url", url);
		if (secretToken != null) {
			payload.put("secret_token", secretToken);
		}
		payload.put("allowed_updates", new String[] { "message" });

		return call(token, "setWebhook", payload);
	}

	private TelegramSendResult call(String token, String method, Map<String, Object> payload) {
		try {
			ResponseEntity<TelegramApiResponse> response = restClient.post()
					.uri(methodUri(token, method))
					.contentType(MediaType.APPLICATION_JSON)
					.body(payload)
					.retrieve()
					// Telegram은 실패해도 이유를 본문에 담아 주므로 기본 예외 처리를 끄고 직접 읽습니다.
					.onStatus(status -> true, (request, clientResponse) -> {
					})
					.toEntity(TelegramApiResponse.class);

			TelegramApiResponse body = response.getBody();
			if (body != null && body.ok()) {
				return TelegramSendResult.sent();
			}

			String reason = (body != null && body.description() != null) ? body.description()
					: "HTTP " + response.getStatusCode().value();
			return TelegramSendResult.failure(shorten(withoutToken(reason, token)));
		}
		catch (Exception ex) {
			return TelegramSendResult.failure(shorten(withoutToken(describe(ex), token)));
		}
	}

	private String describe(Exception ex) {
		return ex.getClass().getSimpleName() + ": " + ex.getMessage();
	}

	/**
	 * URI template을 쓰지 않습니다.
	 * Bot Token에 들어 있는 콜론이 %3A로 바뀌는데, Telegram이 그 형태를 받아 줄지 보장할 수 없습니다.
	 */
	private URI methodUri(String token, String method) {
		return URI.create(apiBaseUrl + "/bot" + token + "/" + method);
	}

	private String withoutTrailingSlash(String url) {
		String trimmed = url.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

	static String withoutToken(String message, String token) {
		if (message == null) {
			return null;
		}
		if (token == null || token.isBlank()) {
			return message;
		}
		return message.replace(token, "***");
	}

	private String shorten(String message) {
		if (message == null) {
			return null;
		}
		return (message.length() <= MAX_ERROR_LENGTH) ? message : message.substring(0, MAX_ERROR_LENGTH) + "…";
	}

	public record WebhookInfo(boolean available, boolean registered) {

		private static final WebhookInfo UNAVAILABLE = new WebhookInfo(false, false);

		public static WebhookInfo unavailable() {
			return UNAVAILABLE;
		}
	}

	private record TelegramApiResponse(boolean ok, String description) {
	}

	private record GetWebhookInfoResponse(boolean ok, WebhookInfoResult result) {
	}

	private record WebhookInfoResult(String url) {
	}

}
