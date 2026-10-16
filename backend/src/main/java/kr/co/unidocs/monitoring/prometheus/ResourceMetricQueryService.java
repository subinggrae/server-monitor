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

	/** serverId는 Long이라 PromQL에 그대로 넣어도 식이 바뀌지 않습니다. */
	public ResourceValues resourceValues(Long serverId) {
		String selector = "{server_id=\"" + serverId + "\"}";
		PrometheusQueryResult percent = prometheusQueryService.query("manager:resource_usage_percent" + selector);
		if (!percent.available()) {
			return ResourceValues.unavailable();
		}
		PrometheusQueryResult used = prometheusQueryService.query("manager:resource_used_bytes" + selector);
		PrometheusQueryResult size = prometheusQueryService.query("manager:resource_size_bytes" + selector);

		Map<String, Long> usedByKey = bytesByKey(used);
		Map<String, Long> sizeByKey = bytesByKey(size);

		Map<String, ResourceValue> byKey = new HashMap<>();
		for (PrometheusQueryResult.Sample sample : percent.samples()) {
			String key = resourceKey(sample);
			if (key != null) {
				byKey.put(key, new ResourceValue(sample.value(), usedByKey.get(key), sizeByKey.get(key),
						sample.sampledAt()));
			}
		}
		return new ResourceValues(true, Map.copyOf(byKey));
	}

	private static Map<String, Long> bytesByKey(PrometheusQueryResult result) {
		Map<String, Long> values = new HashMap<>();
		for (PrometheusQueryResult.Sample sample : result.samples()) {
			String key = resourceKey(sample);
			if (key != null && Double.isFinite(sample.value())) {
				values.put(key, Math.round(sample.value()));
			}
		}
		return values;
	}

	private static String resourceKey(PrometheusQueryResult.Sample sample) {
		String type = sample.label("resource_type");
		String key = sample.label("resource_key");
		return (type != null && key != null) ? ResourceValues.key(type, key) : null;
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
