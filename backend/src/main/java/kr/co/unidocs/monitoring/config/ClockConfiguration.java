package kr.co.unidocs.monitoring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 시간에 따라 결과가 달라지는 판단은 이 Clock으로 현재 시각을 얻습니다. 테스트에서 시각을 고정할 수 있습니다. */
@Configuration
class ClockConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
