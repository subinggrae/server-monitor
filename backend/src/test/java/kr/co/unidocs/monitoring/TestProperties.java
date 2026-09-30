package kr.co.unidocs.monitoring;

import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties.Endpoint;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties.Exporters;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties.Notification;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties.Snmp;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties.Telegram;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 테스트에서 쓰는 설정값을 만듭니다.
 *
 * 설정 항목이 늘어날 때마다 테스트 여러 곳을 고치지 않도록 한 곳에 모아 둡니다.
 */
public final class TestProperties {

	/** 암호화에 쓰는 키라서 길이 조건을 만족해야 합니다. 운영 값과는 상관없습니다. */
	public static final String SECRET_KEY = "test-secret-key-for-unit-tests-only-0123456789";

	private TestProperties() {
	}

	/** 필요한 값만 바꿔서 쓰는 기본 설정입니다. */
	public static MonitoringManagerProperties defaults(String prometheusBaseUrl, Telegram telegram) {
		Endpoint endpoint = new Endpoint(prometheusBaseUrl);

		return new MonitoringManagerProperties(SECRET_KEY, endpoint, endpoint, endpoint,
				new Exporters("http://blackbox-exporter:9115", "http://snmp-exporter:9116",
						"http://mysqld-exporter:9104"),
				telegram, new Notification(Duration.ofMinutes(2), ZoneId.of("Asia/Seoul"), null),
				new Snmp("/runtime/snmp-auth.yml", "http://snmp-exporter:9116/-/reload", Duration.ofSeconds(5)));
	}

	public static Telegram telegram(String botToken, String apiBaseUrl) {
		return new Telegram(botToken, apiBaseUrl, null, null, Duration.ofSeconds(5));
	}

}
