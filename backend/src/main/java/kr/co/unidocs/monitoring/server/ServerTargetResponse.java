package kr.co.unidocs.monitoring.server;

import kr.co.unidocs.monitoring.exporter.CollectionState;

import java.time.Instant;

/** 서버가 살아 있는지는 담지 않습니다. 생존은 감시 항목(Check)이 판단하고, 여기에는 자원 수집 상태만 있습니다. */
public record ServerTargetResponse(
		Long id,
		String name,
		String host,
		String environment,
		ServerOs os,
		String description,
		boolean enabled,
		CollectionState collectionState,
		int monitoredResourceCount,
		Instant createdAt,
		Instant updatedAt) {

	public static ServerTargetResponse of(ServerTarget server, CollectionState collectionState,
			int monitoredResourceCount) {
		return new ServerTargetResponse(server.getId(), server.getName(), server.getHost(), server.getEnvironment(),
				server.getOs(), server.getDescription(), server.isEnabled(), collectionState, monitoredResourceCount,
				server.getCreatedAt(), server.getUpdatedAt());
	}

}
