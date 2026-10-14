package kr.co.unidocs.monitoring.resource;

import java.util.List;

/**
 * {@code success}가 false면 장비가 응답하지 않은 경우입니다.
 * 발견한 것이 없는 것과 다릅니다. 응답은 받았지만 지원하는 MIB가 없으면 success는 true입니다.
 */
public record ResourceDiscoveryResult(boolean success, String message, String systemName, String systemDescription,
		List<DiscoveredResource> resources) {

	public static ResourceDiscoveryResult failed(String message) {
		return new ResourceDiscoveryResult(false, message, null, null, List.of());
	}

}
