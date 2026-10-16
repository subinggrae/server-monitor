package kr.co.unidocs.monitoring.resource;

import kr.co.unidocs.monitoring.exporter.ExporterType;

import java.util.List;

/**
 * {@code success}가 false면 수집이 꺼져 있거나 exporter를 읽지 못한 경우입니다.
 * 찾은 것이 없는 것과 다릅니다. 읽었지만 고를 디스크가 없어도 success는 true입니다.
 */
public record ResourceDiscoveryResult(boolean success, String message, ExporterType exporterType, String version,
		List<DiscoveredResource> resources) {

	public static ResourceDiscoveryResult failed(String message) {
		return new ResourceDiscoveryResult(false, message, null, null, List.of());
	}

}
