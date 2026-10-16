package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.resource.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("서버 자원 값 조회")
class ResourceMetricQueryServiceTest {

	private static final Instant AT = Instant.parse("2026-10-16T01:00:00Z");

	private final PrometheusQueryService prometheus = mock(PrometheusQueryService.class);

	private final ResourceMetricQueryService service = new ResourceMetricQueryService(prometheus);

	@Test
	@DisplayName("공통 recording rule 세 이름을 서버 하나로 걸러 리소스마다 모은다")
	void readsCommonRecordingRules() {
		when(prometheus.query("manager:resource_usage_percent{server_id=\"5\"}")).thenReturn(result(
				sample("CPU", "cpu", 62.5), sample("STORAGE", "/data", 75)));
		when(prometheus.query("manager:resource_used_bytes{server_id=\"5\"}")).thenReturn(result(
				sample("STORAGE", "/data", 750)));
		when(prometheus.query("manager:resource_size_bytes{server_id=\"5\"}")).thenReturn(result(
				sample("STORAGE", "/data", 1000)));

		ResourceValues values = service.resourceValues(5L);

		assertThat(values.available()).isTrue();
		assertThat(values.of(ResourceType.CPU, "cpu")).isEqualTo(new ResourceValue(62.5, null, null, AT));
		assertThat(values.of(ResourceType.STORAGE, "/data")).isEqualTo(new ResourceValue(75.0, 750L, 1000L, AT));
		assertThat(values.of(ResourceType.MEMORY, "memory")).isNull();
	}

	@Test
	@DisplayName("Prometheus를 조회하지 못하면 사용할 수 없는 결과다")
	void unavailableWhenPrometheusDown() {
		when(prometheus.query(org.mockito.ArgumentMatchers.anyString())).thenReturn(PrometheusQueryResult.unavailable());

		assertThat(service.resourceValues(5L).available()).isFalse();
	}

	@Test
	@DisplayName("수집 상태는 manager-os job의 up을 서버마다 읽는다")
	void readsCollectionState() {
		when(prometheus.query("max by (server_id) (up{job=\"manager-os\"})")).thenReturn(result(
				new PrometheusQueryResult.Sample(Map.of("server_id", "5"), 1, AT),
				new PrometheusQueryResult.Sample(Map.of("server_id", "6"), 0, AT)));

		CollectionSnapshot snapshot = service.collectionSnapshot();

		assertThat(snapshot.isUp(5L)).isTrue();
		assertThat(snapshot.isUp(6L)).isFalse();
		assertThat(snapshot.isUp(7L)).isFalse();
	}

	private static PrometheusQueryResult.Sample sample(String type, String key, double value) {
		return new PrometheusQueryResult.Sample(
				Map.of("server_id", "5", "resource_type", type, "resource_key", key), value, AT);
	}

	private static PrometheusQueryResult result(PrometheusQueryResult.Sample... samples) {
		return new PrometheusQueryResult(true, List.of(samples));
	}

}
