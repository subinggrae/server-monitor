package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PromQL은 이 package 안에서만 만듭니다. (CLAUDE.md 12번)
 *
 * 연결에 실패해도 예외를 밖으로 던지지 않습니다.
 * Prometheus가 잠시 내려가도 Server와 Check 설정 조회는 계속 동작해야 합니다.
 */
@Service
public class PrometheusQueryService {

	private static final Logger log = LoggerFactory.getLogger(PrometheusQueryService.class);

	private static final Duration FAILURE_LOG_INTERVAL = Duration.ofMinutes(5);

	private final RestClient restClient;

	private final String baseUrl;

	private final AtomicReference<Instant> lastFailureLoggedAt = new AtomicReference<>();

	PrometheusQueryService(RestClient.Builder restClientBuilder, MonitoringManagerProperties properties) {
		this.baseUrl = properties.prometheus().baseUrl();
		this.restClient = restClientBuilder.baseUrl(this.baseUrl).build();
	}

	public PrometheusQueryResult query(String promql) {
		try {
			// PromQL의 중괄호를 그대로 넘기면 RestClient가 URI template 변수로 해석해 요청이 만들어지지 않습니다.
			QueryApiResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder.path("/api/v1/query").queryParam("query", "{promql}").build(promql))
					.retrieve()
					.body(QueryApiResponse.class);

			if (response == null || !"success".equals(response.status()) || response.data() == null) {
				logFailure(null);
				return PrometheusQueryResult.unavailable();
			}

			noteRecovered();
			return PrometheusQueryResult.of(response.data().result());
		}
		catch (Exception ex) {
			logFailure(ex);
			return PrometheusQueryResult.unavailable();
		}
	}

	/** 사용자 요청마다 stacktrace를 남기면 로그를 읽을 수 없게 됩니다. */
	private void logFailure(Exception ex) {
		Instant now = Instant.now();
		Instant lastLogged = lastFailureLoggedAt.get();

		boolean shouldLog = lastLogged == null || lastLogged.plus(FAILURE_LOG_INTERVAL).isBefore(now);
		if (!shouldLog || !lastFailureLoggedAt.compareAndSet(lastLogged, now)) {
			return;
		}

		String detail = (ex != null) ? ex.getClass().getSimpleName() + ": " + ex.getMessage() : "응답 형식이 올바르지 않습니다.";
		log.warn("Prometheus 상태를 조회하지 못했습니다. url={} reason={} (같은 오류는 {}분 동안 다시 남기지 않습니다.)", baseUrl, detail,
				FAILURE_LOG_INTERVAL.toMinutes());
	}

	private void noteRecovered() {
		if (lastFailureLoggedAt.getAndSet(null) != null) {
			log.info("Prometheus 연결이 복구되었습니다. url={}", baseUrl);
		}
	}

	private record QueryApiResponse(String status, QueryData data) {
	}

	private record QueryData(String resultType, List<InstantVectorSample> result) {
	}

	/** {@code value}는 [timestamp, "값"] 형태라 타입이 섞여 있어 List&lt;Object&gt;로 받습니다. */
	record InstantVectorSample(Map<String, String> metric, List<Object> value) {
	}

}
