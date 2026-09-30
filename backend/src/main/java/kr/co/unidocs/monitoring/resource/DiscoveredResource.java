package kr.co.unidocs.monitoring.resource;

/** 지금 장비에서 보이는 Resource입니다. 저장한 선택과 다릅니다. */
public record DiscoveredResource(ResourceType resourceType, String resourceKey, String displayName, String description,
		Integer snmpIndex, Long totalBytes, boolean selected) {
}
