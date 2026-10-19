package kr.co.unidocs.monitoring.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Manager 설정 바인딩")
class MonitoringManagerPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
			.withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("monitoring-manager.prometheus.base-url=http://prometheus:9090",
					"monitoring-manager.grafana.base-url=http://grafana:3000",
					"monitoring-manager.telegram.api-base-url=https://api.telegram.org",
					"monitoring-manager.telegram.timeout=5s",
					"monitoring-manager.notification.dedupe-window=2m",
					"monitoring-manager.notification.display-time-zone=Asia/Seoul");

	@Test
	@DisplayName("Alertmanager 주소와 exporter 주소가 없어도 바인딩된다")
	void bindsWithoutRemovedKeys() {
		runner.run(context -> assertThat(context).hasNotFailed()
				.hasSingleBean(MonitoringManagerProperties.class));
	}

	@Test
	@DisplayName("지운 설정 키가 남아 있어도 바인딩된다")
	void bindsWithLeftoverRemovedKeys() {
		runner.withPropertyValues("monitoring-manager.alertmanager.base-url=http://alertmanager:9093",
				"monitoring-manager.exporters.blackbox-url=http://blackbox-exporter:9115",
				"monitoring-manager.exporters.mysqld-url=http://mysqld-exporter:9104")
				.run(context -> assertThat(context).hasNotFailed()
						.hasSingleBean(MonitoringManagerProperties.class));
	}

	@EnableConfigurationProperties(MonitoringManagerProperties.class)
	static class PropertiesConfiguration {
	}

}
