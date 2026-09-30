package kr.co.unidocs.monitoring.alert;

public enum AlertStatus {

	FIRING,

	RESOLVED;

	/** 모르는 값은 발생 중으로 봅니다. 알림을 덜 보내는 쪽보다 한 번 더 보내는 쪽이 안전합니다. */
	public static AlertStatus from(String value) {
		return "resolved".equalsIgnoreCase(value) ? RESOLVED : FIRING;
	}

	public boolean resolved() {
		return this == RESOLVED;
	}

}
