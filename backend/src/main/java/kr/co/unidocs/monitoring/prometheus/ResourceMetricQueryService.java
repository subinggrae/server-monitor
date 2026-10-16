package kr.co.unidocs.monitoring.prometheus;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * OS exporter 수집 상태와 서버 자원 값을 조회합니다.
 *
 * OS별 metric은 읽지 않습니다. 자원 값은 rules/server-resources.yml의 공통 이름만 읽습니다. (docs/decisions/0005)
 * 서버가 몇 대든 query 횟수는 그대로입니다.
 */
@Service
public class ResourceMetricQueryService {

	static final String JOB = "manager-os";

	private final PrometheusQueryService prometheusQueryService;

	ResourceMetricQueryService(PrometheusQueryService prometheusQueryService) {
		this.prometheusQueryService = prometheusQueryService;
	}

	public CollectionSnapshot collectionSnapshot() {
		PrometheusQueryResult up = prometheusQueryService.query("max by (server_id) (up{job=\"" + JOB + "\"})");
		if (!up.available()) {
			return CollectionSnapshot.unavailable();
		}

		Map<Long, Boolean> byServer = new HashMap<>();
		for (PrometheusQueryResult.Sample sample : up.samples()) {
			Long serverId = serverId(sample);
			if (serverId != null) {
				byServer.put(serverId, sample.value() == 1);
			}
		}
		return new CollectionSnapshot(true, Map.copyOf(byServer));
	}

	static Long serverId(PrometheusQueryResult.Sample sample) {
		String value = sample.label("server_id");
		if (value == null) {
			return null;
		}
		try {
			return Long.valueOf(value);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
