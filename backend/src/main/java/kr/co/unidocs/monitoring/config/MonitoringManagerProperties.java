package kr.co.unidocs.monitoring.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 검증 메시지에는 property 이름이 아니라 운영자가 설정할 환경변수나 Docker Secret 이름을 적습니다.
 *
 * secret 값은 toString()에서 가립니다. 설정 내용을 로그로 남기더라도 보이면 안 됩니다. (CLAUDE.md 11번)
 */
@ConfigurationProperties(prefix = "monitoring-manager")
@Validated
public record MonitoringManagerProperties(

		@NotBlank(message = "값이 비어 있습니다. Docker Secret(monitoring-manager.secret-key) 또는 APP_SECRET_KEY를 설정해 주세요.")
		String secretKey,

		@NotNull @Valid Endpoint prometheus,
		@NotNull @Valid Endpoint alertmanager,
		@NotNull @Valid Endpoint grafana,
		@NotNull @Valid Exporters exporters,
		@NotNull @Valid Telegram telegram,
		@NotNull @Valid Notification notification,
		@NotNull @Valid Snmp snmp) {

	public record Endpoint(
			@NotBlank(message = "값이 비어 있습니다. PROMETHEUS_URL, ALERTMANAGER_URL, GRAFANA_URL 환경변수를 확인해 주세요.")
			String baseUrl) {
	}

	public record Exporters(
			@NotBlank(message = "값이 비어 있습니다. BLACKBOX_EXPORTER_URL 환경변수를 설정해 주세요.")
			String blackboxUrl,

			@NotBlank(message = "값이 비어 있습니다. SNMP_EXPORTER_URL 환경변수를 설정해 주세요.")
			String snmpUrl,

			@NotBlank(message = "값이 비어 있습니다. MYSQLD_EXPORTER_URL 환경변수를 설정해 주세요.")
			String mysqldUrl) {
	}

	/** secret 값은 {@link ManagerSecrets}를 통해 씁니다. 여기에서 바로 꺼내 쓰지 않습니다. */
	public record Telegram(
			String botToken,

			@NotBlank(message = "값이 비어 있습니다. TELEGRAM_API_BASE_URL 환경변수를 확인해 주세요.")
			String apiBaseUrl,

			String webhookSecret,

			/** Telegram webhook은 외부에서 들어옵니다. 내부 주소로는 받을 수 없습니다. */
			String webhookPublicUrl,

			@NotNull Duration timeout) {

		public boolean webhookPublicUrlConfigured() {
			return webhookPublicUrl != null && !webhookPublicUrl.isBlank();
		}

		@Override
		public String toString() {
			return "Telegram[botToken=" + mask(botToken)
					+ ", apiBaseUrl=" + apiBaseUrl
					+ ", webhookSecret=" + mask(webhookSecret)
					+ ", webhookPublicUrl=" + webhookPublicUrl
					+ ", timeout=" + timeout + "]";
		}
	}

	public record Notification(

			/**
			 * 복구 알림을 Alertmanager가 곧바로 다시 보낼 때 한 번만 보내도록 막는 창입니다.
			 * 이어지는 Alert의 재발송은 Check별 재발송 주기가 정합니다.
			 */
			@NotNull Duration dedupeWindow,

			/** DB에는 UTC로 저장합니다. 이 값은 Backend가 직접 만드는 Telegram 메시지에만 씁니다. */
			@NotNull ZoneId displayTimeZone,

			String alertmanagerWebhookToken) {

		@Override
		public String toString() {
			return "Notification[dedupeWindow=" + dedupeWindow
					+ ", displayTimeZone=" + displayTimeZone
					+ ", alertmanagerWebhookToken=" + mask(alertmanagerWebhookToken) + "]";
		}
	}

	/**
	 * snmp_exporter는 scrape 요청으로 community를 받지 않습니다.
	 * 그래서 Manager가 auth config 파일을 만들어 주고 exporter에 다시 읽으라고 알립니다.
	 */
	public record Snmp(
			@NotBlank(message = "값이 비어 있습니다. SNMP_AUTH_CONFIG_PATH 환경변수를 확인해 주세요.")
			String authConfigPath,

			@NotBlank(message = "값이 비어 있습니다. SNMP_EXPORTER_RELOAD_URL 환경변수를 확인해 주세요.")
			String reloadUrl,

			@NotNull Duration reloadTimeout) {
	}

	private static String mask(String value) {
		return (value != null && !value.isBlank()) ? "***" : "<unset>";
	}

	@Override
	public String toString() {
		return "MonitoringManagerProperties[secretKey=***"
				+ ", prometheus=" + prometheus
				+ ", alertmanager=" + alertmanager
				+ ", grafana=" + grafana
				+ ", exporters=" + exporters
				+ ", telegram=" + telegram
				+ ", notification=" + notification
				+ ", snmp=" + snmp + "]";
	}

}
