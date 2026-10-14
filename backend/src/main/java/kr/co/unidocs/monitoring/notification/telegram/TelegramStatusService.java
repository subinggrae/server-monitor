package kr.co.unidocs.monitoring.notification.telegram;

import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.springframework.stereotype.Service;

/** 화면에서 "왜 /register가 안 되는지" 설명하려면 이 값이 필요합니다. Bot Token은 내보내지 않습니다. */
@Service
public class TelegramStatusService {

	private final MonitoringManagerProperties properties;

	private final TelegramClient telegramClient;

	TelegramStatusService(MonitoringManagerProperties properties, TelegramClient telegramClient) {
		this.properties = properties;
		this.telegramClient = telegramClient;
	}

	public TelegramStatus currentStatus() {
		boolean configured = telegramClient.configured();
		boolean publicUrlConfigured = properties.telegram().webhookPublicUrlConfigured();

		// 공개 주소가 없으면 Telegram이 Manager를 부를 수 없어 물어볼 필요가 없습니다.
		if (!configured || !publicUrlConfigured) {
			return new TelegramStatus(configured, false, publicUrlConfigured);
		}

		TelegramClient.WebhookInfo info = telegramClient.webhookInfo();
		return new TelegramStatus(true, info.available() && info.registered(), true);
	}

	public record TelegramStatus(boolean configured, boolean webhookConfigured, boolean publicUrlConfigured) {
	}

}
