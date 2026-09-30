package kr.co.unidocs.monitoring.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 시스템 secret을 한 곳에서 꺼내 씁니다.
 *
 * 값을 읽는 일은 Spring Boot가 합니다.
 * {@code spring.config.import: optional:configtree:/run/secrets/}가 Docker Secret 파일을 property로 만듭니다.
 * Config tree는 application.yml보다 우선하므로 "파일이 있으면 파일, 없으면 환경변수"가 됩니다.
 *
 * 설정하지 않은 secret은 application.yml의 fallback 때문에 빈 문자열로 들어옵니다.
 * 쓰는 쪽마다 isBlank()를 확인하게 두면 빠뜨리기 쉬워서 여기에서 null로 맞춥니다.
 *
 * secret 값 자체는 로그에 남기지 않습니다. 설정 여부만 남깁니다. (CLAUDE.md 11번)
 */
@Component
public class ManagerSecrets {

	private static final Logger log = LoggerFactory.getLogger(ManagerSecrets.class);

	private final String telegramBotToken;

	private final String telegramWebhookSecret;

	private final String alertmanagerWebhookToken;

	public ManagerSecrets(MonitoringManagerProperties properties) {
		this.telegramBotToken = trimToNull(properties.telegram().botToken());
		this.telegramWebhookSecret = trimToNull(properties.telegram().webhookSecret());
		this.alertmanagerWebhookToken = trimToNull(properties.notification().alertmanagerWebhookToken());

		log.info("secret 설정 상태입니다. telegram_bot_token={} telegram_webhook_secret={} alertmanager_webhook_token={}",
				state(telegramBotToken), state(telegramWebhookSecret), state(alertmanagerWebhookToken));

		if (alertmanagerWebhookToken == null) {
			log.warn("Alertmanager webhook token이 없어 Alert를 받지 않습니다. "
					+ "Docker Secret(monitoring-manager.notification.alertmanager-webhook-token) 또는 "
					+ "ALERTMANAGER_WEBHOOK_TOKEN을 설정해 주세요.");
		}
	}

	public String telegramBotToken() {
		return telegramBotToken;
	}

	public boolean telegramBotTokenConfigured() {
		return telegramBotToken != null;
	}

	public String telegramWebhookSecret() {
		return telegramWebhookSecret;
	}

	public String alertmanagerWebhookToken() {
		return alertmanagerWebhookToken;
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private String state(String secret) {
		return (secret != null) ? "설정됨" : "없음";
	}

}
