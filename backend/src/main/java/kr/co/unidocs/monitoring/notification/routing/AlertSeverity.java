package kr.co.unidocs.monitoring.notification.routing;

public enum AlertSeverity {

	WARNING,

	CRITICAL,

	/** 모르는 값입니다. critical로 바꾸지 않습니다. 설정하지 않은 알림을 보내게 됩니다. */
	UNKNOWN;

	public static AlertSeverity from(String label) {
		if (label == null) {
			return UNKNOWN;
		}

		return switch (label.trim().toLowerCase()) {
			case "warning" -> WARNING;
			case "critical" -> CRITICAL;
			default -> UNKNOWN;
		};
	}

}
