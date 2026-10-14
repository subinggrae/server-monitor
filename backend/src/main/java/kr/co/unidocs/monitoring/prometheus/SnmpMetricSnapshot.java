package kr.co.unidocs.monitoring.prometheus;

import java.util.Map;

/**
 * {@code available}이 false면 Prometheus에 연결하지 못한 경우입니다.
 * 수집된 Server가 없는 것과 다릅니다.
 */
public record SnmpMetricSnapshot(boolean available, Map<Long, ServerSnmpMetrics> byServer) {

	static SnmpMetricSnapshot unavailable() {
		return new SnmpMetricSnapshot(false, Map.of());
	}

	public ServerSnmpMetrics of(Long serverId) {
		return byServer.get(serverId);
	}

}
