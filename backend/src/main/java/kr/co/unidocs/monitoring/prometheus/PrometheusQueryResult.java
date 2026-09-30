package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.prometheus.PrometheusQueryService.InstantVectorSample;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code available}이 false면 연결하지 못했거나 응답을 해석하지 못한 경우입니다.
 * 결과가 0건인 것과 다릅니다. 연결은 됐지만 수집된 Metric이 없으면 available은 true입니다.
 */
public record PrometheusQueryResult(boolean available, List<Sample> samples) {

	private static final PrometheusQueryResult UNAVAILABLE = new PrometheusQueryResult(false, List.of());

	public static PrometheusQueryResult unavailable() {
		return UNAVAILABLE;
	}

	static PrometheusQueryResult of(List<InstantVectorSample> rawSamples) {
		if (rawSamples == null) {
			return new PrometheusQueryResult(true, List.of());
		}

		List<Sample> parsed = new ArrayList<>(rawSamples.size());
		for (InstantVectorSample raw : rawSamples) {
			Sample sample = toSample(raw);
			if (sample != null) {
				parsed.add(sample);
			}
		}
		return new PrometheusQueryResult(true, List.copyOf(parsed));
	}

	private static Sample toSample(InstantVectorSample raw) {
		if (raw == null || raw.value() == null || raw.value().size() < 2) {
			return null;
		}

		try {
			// value는 [1758512345.123, "1"] 형태입니다. 초 단위 epoch와 문자열로 된 값입니다.
			double epochSeconds = new BigDecimal(String.valueOf(raw.value().get(0))).doubleValue();
			double value = Double.parseDouble(String.valueOf(raw.value().get(1)));
			Instant sampledAt = Instant.ofEpochMilli(Math.round(epochSeconds * 1000));
			Map<String, String> labels = (raw.metric() != null) ? raw.metric() : Map.of();
			return new Sample(labels, value, sampledAt);
		}
		catch (NumberFormatException ex) {
			// 값 하나가 깨져도 나머지 결과는 쓸 수 있게 넘어갑니다.
			return null;
		}
	}

	public record Sample(Map<String, String> labels, double value, Instant sampledAt) {

		public String label(String name) {
			return labels.get(name);
		}

	}

}
