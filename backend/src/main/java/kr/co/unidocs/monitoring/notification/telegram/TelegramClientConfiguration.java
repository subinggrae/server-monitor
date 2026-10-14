package kr.co.unidocs.monitoring.notification.telegram;

import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class TelegramClientConfiguration {

	/** Telegram이 응답하지 않을 때 요청 thread가 묶여 있지 않도록 timeout을 반드시 넣습니다. */
	@Bean
	RestClient telegramRestClient(RestClient.Builder restClientBuilder, MonitoringManagerProperties properties) {
		MonitoringManagerProperties.Telegram telegram = properties.telegram();

		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(telegram.timeout());
		requestFactory.setReadTimeout(telegram.timeout());

		return restClientBuilder.baseUrl(telegram.apiBaseUrl()).requestFactory(requestFactory).build();
	}

}
