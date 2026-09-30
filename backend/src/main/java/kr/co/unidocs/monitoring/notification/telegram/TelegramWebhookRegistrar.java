package kr.co.unidocs.monitoring.notification.telegram;

import kr.co.unidocs.monitoring.config.ManagerSecrets;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Telegram은 Bot마다 webhook 주소를 하나만 기억합니다.
 * 손으로 등록하게 두면 배포한 뒤에 /register가 조용히 동작하지 않는 상태가 됩니다.
 *
 * 등록에 실패해도 애플리케이션은 계속 뜹니다. 모니터링은 Telegram과 관계없이 동작해야 합니다.
 */
@Component
class TelegramWebhookRegistrar implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TelegramWebhookRegistrar.class);

	/** {@link TelegramWebhookController}의 경로와 같아야 합니다. */
	private static final String WEBHOOK_PATH = "/api/integrations/telegram/webhook";

	private final MonitoringManagerProperties properties;

	private final TelegramClient telegramClient;

	private final ManagerSecrets secrets;

	TelegramWebhookRegistrar(MonitoringManagerProperties properties, TelegramClient telegramClient,
			ManagerSecrets secrets) {
		this.properties = properties;
		this.telegramClient = telegramClient;
		this.secrets = secrets;
	}

	@Override
	public void run(ApplicationArguments args) {
		MonitoringManagerProperties.Telegram telegram = properties.telegram();

		if (!telegramClient.configured()) {
			log.info("Telegram Bot Token이 없어 webhook을 등록하지 않았습니다. 알림 전송 기능은 동작하지 않습니다.");
			return;
		}
		if (!telegram.webhookPublicUrlConfigured()) {
			log.info("TELEGRAM_WEBHOOK_PUBLIC_URL이 없어 webhook을 등록하지 않았습니다. "
					+ "Telegram에서 /register를 쓸 수 없습니다. 화면에서 Chat ID를 직접 등록해 주세요.");
			return;
		}

		String url = withoutTrailingSlash(telegram.webhookPublicUrl()) + WEBHOOK_PATH;
		TelegramSendResult result = telegramClient.setWebhook(url, secrets.telegramWebhookSecret());

		if (result.success()) {
			log.info("Telegram webhook을 등록했습니다. url={}", url);
		}
		else {
			log.warn("Telegram webhook을 등록하지 못했습니다. url={} reason={} "
					+ "(주소가 외부에서 접근 가능한 HTTPS인지 확인해 주세요)", url, result.errorMessage());
		}
	}

	private String withoutTrailingSlash(String url) {
		String trimmed = url.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

}
