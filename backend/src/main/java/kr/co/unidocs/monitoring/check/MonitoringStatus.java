package kr.co.unidocs.monitoring.check;

import java.util.Collection;

public enum MonitoringStatus {

	OK,

	CRITICAL,

	/** 수집된 Metric이 없거나 Prometheus에 연결할 수 없습니다. 두 경우를 구분하지 않습니다. */
	UNKNOWN,

	DISABLED;

	/**
	 * 하위 상태를 하나로 합칩니다. 합칠 대상이 없으면 UNKNOWN입니다.
	 *
	 * @param statuses DISABLED는 호출하는 쪽에서 먼저 걸러서 넘깁니다.
	 */
	public static MonitoringStatus aggregate(Collection<MonitoringStatus> statuses) {
		if (statuses.isEmpty()) {
			return UNKNOWN;
		}

		boolean hasUnknown = false;
		for (MonitoringStatus status : statuses) {
			if (status == CRITICAL) {
				return CRITICAL;
			}
			if (status != OK) {
				hasUnknown = true;
			}
		}

		return hasUnknown ? UNKNOWN : OK;
	}

}
