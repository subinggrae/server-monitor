package kr.co.unidocs.monitoring.resource;

/**
 * Threshold 기능이 아직 없습니다. 사용률이 높다는 이유로 WARNING이나 CRITICAL을 만들지 않습니다.
 * 지금은 값을 읽을 수 있는지만 구분합니다.
 */
public enum ResourceStatus {

	/** Prometheus에 값이 있습니다. */
	AVAILABLE,

	/** 고르긴 했지만 지금 값이 없습니다. 장비에서 사라졌거나 아직 수집되지 않았습니다. */
	UNAVAILABLE,

	DISABLED

}
