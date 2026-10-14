package kr.co.unidocs.monitoring.notification;

import kr.co.unidocs.monitoring.notification.routing.AlertSeverity;

import java.time.Instant;

/** 보내는 쪽이 Alert의 저장 구조를 알 필요 없게 필요한 값만 담습니다. */
public record AlertNotification(
		Long alertEventId,
		Long checkId,
		String checkName,
		String environment,
		String instance,
		AlertSeverity severity,
		String severityLabel,
		boolean resolved,
		String summary,
		Instant startsAt,
		Instant resolvedAt) {
}
