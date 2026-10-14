package kr.co.unidocs.monitoring.resource;

import java.time.Instant;

/**
 * 값이 없으면 null을 그대로 보냅니다. 화면을 채우려고 0이나 추정값을 만들지 않습니다.
 *
 * CPU에는 용량이 없어 totalBytes와 usedBytes가 비어 있습니다.
 */
public record ResourceUsageResponse(Long id, ResourceType resourceType, String resourceKey, String displayName,
		String description, ResourceStatus status, Double usagePercent, Long usedBytes, Long totalBytes,
		Instant sampledAt) {
}
