package kr.co.unidocs.monitoring.alert;

import java.time.Instant;

public record AlertEventResponse(
		Long id,
		String alertName,
		Long checkId,
		Long checkTargetId,
		String severity,
		AlertStatus status,
		String instance,
		String summary,
		Instant startsAt,
		Instant resolvedAt,
		Instant lastReceivedAt) {

	public static AlertEventResponse of(AlertEvent event) {
		return new AlertEventResponse(event.getId(), event.getAlertName(), event.getCheckId(),
				event.getCheckTargetId(), event.getSeverity(), event.getStatus(), event.getInstance(),
				event.getSummary(), event.getStartsAt(), event.getResolvedAt(), event.getLastReceivedAt());
	}

}
