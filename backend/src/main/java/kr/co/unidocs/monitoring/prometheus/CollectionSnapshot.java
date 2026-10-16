package kr.co.unidocs.monitoring.prometheus;

import java.util.Map;

/**
 * 서버별 manager-os job의 up입니다.
 * {@code available}이 false면 Prometheus에 연결하지 못한 경우입니다. 수집된 서버가 없는 것과 다릅니다.
 */
public record CollectionSnapshot(boolean available, Map<Long, Boolean> upByServer) {

	private static final CollectionSnapshot UNAVAILABLE = new CollectionSnapshot(false, Map.of());

	public static CollectionSnapshot unavailable() {
		return UNAVAILABLE;
	}

	/** series가 없으면 false입니다. 수집을 켰는데 아직 한 번도 읽지 못한 경우도 실패로 봅니다. */
	public boolean isUp(Long serverId) {
		return Boolean.TRUE.equals(upByServer.get(serverId));
	}

}
