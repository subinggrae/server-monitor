package kr.co.unidocs.monitoring.alert;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager는 여기 적힌 것보다 많은 field를 보냅니다.
 * 모르는 field가 들어와도 실패하지 않아야 Alertmanager 버전이 올라가도 알림이 끊기지 않습니다.
 */
public record AlertmanagerWebhookPayload(String version, String groupKey, String status, List<Alert> alerts) {

	public record Alert(
			String status,
			Map<String, String> labels,
			Map<String, String> annotations,
			Instant startsAt,
			/** 발생 중인 Alert에는 "값 없음"을 뜻하는 0001-01-01이 들어옵니다. 해소 시각이 아닙니다. */
			Instant endsAt,
			String fingerprint) {

		public String label(String name) {
			return (labels != null) ? labels.get(name) : null;
		}

		public String annotation(String name) {
			return (annotations != null) ? annotations.get(name) : null;
		}

		public AlertStatus alertStatus() {
			return AlertStatus.from(status);
		}

		public Instant resolvedAt() {
			if (!alertStatus().resolved() || endsAt == null) {
				return null;
			}
			return endsAt.isBefore(Instant.EPOCH) ? null : endsAt;
		}

	}

}
