package kr.co.unidocs.monitoring.prometheus;

import kr.co.unidocs.monitoring.resource.ResourceType;

import java.util.Map;

/**
 * 서버 한 대의 리소스 값입니다. key는 resource_type과 resource_key입니다.
 * {@code available}이 false면 Prometheus에 연결하지 못한 경우입니다. 값이 없는 것과 다릅니다.
 */
public record ResourceValues(boolean available, Map<String, ResourceValue> byKey) {

	private static final ResourceValues UNAVAILABLE = new ResourceValues(false, Map.of());

	static ResourceValues unavailable() {
		return UNAVAILABLE;
	}

	public ResourceValue of(ResourceType type, String resourceKey) {
		return byKey.get(key(type.name(), resourceKey));
	}

	static String key(String type, String resourceKey) {
		return type + '\u0000' + resourceKey;
	}

}
