package kr.co.unidocs.monitoring.check;

import java.time.Instant;
import java.util.List;

public record CheckStatusResponse(
		Long checkId,
		MonitoringStatus status,
		/** 사용자에게 그대로 보입니다. PromQL이나 내부 오류 내용을 넣지 않습니다. */
		String message,
		List<TargetStatus> targets) {

	public record TargetStatus(
			Long targetId,
			String blackboxTarget,
			MonitoringStatus status,
			Boolean success,
			Instant checkedAt) {
	}

}
