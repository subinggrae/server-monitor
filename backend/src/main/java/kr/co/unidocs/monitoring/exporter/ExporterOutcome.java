package kr.co.unidocs.monitoring.exporter;

/** exporter를 한 번 읽은 결과의 분류입니다. 화면은 이 분류로 확인할 것을 안내합니다. */
public enum ExporterOutcome {

	OK,

	CONNECTION_REFUSED,

	TIMEOUT,

	HTTP_ERROR,

	/** 크기 한도를 넘었거나 Prometheus 형식이 아닌 응답입니다. */
	UNREADABLE,

	/** Prometheus 형식이지만 node_exporter나 windows_exporter가 아닙니다. */
	NOT_OS_EXPORTER,

	UNKNOWN_HOST

}
