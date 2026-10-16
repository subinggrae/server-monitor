package kr.co.unidocs.monitoring.exporter;

/** 서버 자원을 수집하고 있는지입니다. 서버가 살아 있는지와 다릅니다. 생존은 감시 항목이 판단합니다. */
public enum CollectionState {

	/** 수집 설정이 없습니다. */
	NOT_CONFIGURED,

	/** 수집 설정이나 서버 감시가 꺼져 있습니다. */
	DISABLED,

	/** Prometheus가 exporter를 읽고 있습니다(up = 1). */
	COLLECTING,

	/** 켜져 있지만 exporter를 읽지 못합니다(up = 0이거나 series가 없음). */
	FAILED,

	/** Prometheus를 조회하지 못했습니다. */
	UNKNOWN

}
