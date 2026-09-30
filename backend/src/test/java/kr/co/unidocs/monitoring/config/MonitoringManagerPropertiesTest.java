package kr.co.unidocs.monitoring.config;

import kr.co.unidocs.monitoring.TestProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Docker 없이 실행되는 테스트입니다.
 * secret 값이 로그로 새어 나가지 않는지 확인합니다.
 */
class MonitoringManagerPropertiesTest {

	@Test
	void toStringHidesSecretValues() {
		MonitoringManagerProperties properties = new MonitoringManagerProperties("super-secret-key",
				new MonitoringManagerProperties.Endpoint("http://prometheus:9090"),
				new MonitoringManagerProperties.Endpoint("http://alertmanager:9093"),
				new MonitoringManagerProperties.Endpoint("http://grafana:3000"),
				new MonitoringManagerProperties.Exporters("http://blackbox-exporter:9115",
						"http://snmp-exporter:9116", "http://mysqld-exporter:9104"),
				new MonitoringManagerProperties.Telegram("123456:real-bot-token", "https://api.telegram.org",
						"real-webhook-secret", "https://monitoring.example.com", Duration.ofSeconds(5)),
				new MonitoringManagerProperties.Notification(Duration.ofMinutes(2), ZoneId.of("Asia/Seoul"),
						"real-webhook-token"),
				new MonitoringManagerProperties.Snmp("/runtime/snmp-auth.yml",
						"http://snmp-exporter:9116/-/reload", Duration.ofSeconds(5)));

		// 설정 내용을 로그로 남기더라도 secret은 보이면 안 됩니다. (CLAUDE.md 11번)
		assertThat(properties.toString())
				.doesNotContain("super-secret-key")
				.doesNotContain("real-bot-token")
				.doesNotContain("real-webhook-secret")
				.doesNotContain("real-webhook-token")
				.contains("http://grafana:3000");
	}

	@Test
	void managerSecretsTreatsBlankValueAsNotConfigured() {
		// 설정하지 않은 secret은 application.yml의 fallback 때문에 빈 문자열로 들어옵니다.
		ManagerSecrets blank = new ManagerSecrets(
				TestProperties.defaults("http://prometheus:9090", TestProperties.telegram("  ", "https://api.telegram.org")));

		assertThat(blank.telegramBotTokenConfigured()).isFalse();
		assertThat(blank.telegramBotToken()).isNull();
		assertThat(blank.alertmanagerWebhookToken()).isNull();
	}

	@Test
	void managerSecretsTrimsValues() {
		// Docker Secret 파일 끝에 줄바꿈이 들어가는 일이 흔합니다.
		ManagerSecrets secrets = new ManagerSecrets(TestProperties.defaults("http://prometheus:9090",
				TestProperties.telegram(" 123456:token \n", "https://api.telegram.org")));

		assertThat(secrets.telegramBotToken()).isEqualTo("123456:token");
		assertThat(secrets.telegramBotTokenConfigured()).isTrue();
	}

	@Test
	void webhookPublicUrlIsOptional() {
		MonitoringManagerProperties.Telegram withoutUrl = new MonitoringManagerProperties.Telegram("123456:token",
				"https://api.telegram.org", null, null, Duration.ofSeconds(5));
		MonitoringManagerProperties.Telegram withUrl = new MonitoringManagerProperties.Telegram("123456:token",
				"https://api.telegram.org", null, "https://monitoring.example.com", Duration.ofSeconds(5));

		assertThat(withoutUrl.webhookPublicUrlConfigured()).isFalse();
		assertThat(withUrl.webhookPublicUrlConfigured()).isTrue();
	}

}
