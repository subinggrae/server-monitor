package kr.co.unidocs.monitoring.notification;

public enum DeliveryStatus {

	SENT,

	FAILED,

	/** 같은 Alert를 짧은 시간 안에 다시 받아서 보내지 않았습니다. */
	SKIPPED

}
