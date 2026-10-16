package kr.co.unidocs.monitoring.exporter;

/** exporter를 한 번 읽은 결과의 분류입니다. 화면은 이 분류로 확인할 것을 안내합니다. */
public enum ExporterOutcome {

	OK(null),

	CONNECTION_REFUSED("exporter에 연결하지 못했습니다. exporter가 실행 중인지, 포트와 방화벽을 확인해 주세요."),

	TIMEOUT("exporter가 제시간에 응답하지 않았습니다. 네트워크와 방화벽을 확인해 주세요."),

	HTTP_ERROR("이 포트가 오류로 응답했습니다. OS exporter의 포트인지 확인해 주세요."),

	/** 크기 한도를 넘었거나 Prometheus 형식이 아닌 응답입니다. */
	UNREADABLE("exporter 응답을 읽지 못했습니다. OS exporter의 포트인지 확인해 주세요."),

	/** Prometheus 형식이지만 node_exporter나 windows_exporter가 아닙니다. */
	NOT_OS_EXPORTER("이 포트의 응답은 node_exporter나 windows_exporter가 아닙니다. 포트를 확인해 주세요."),

	UNKNOWN_HOST("서버의 호스트를 찾지 못했습니다. 서버 정보의 호스트를 확인해 주세요.");

	private final String failureMessage;

	ExporterOutcome(String failureMessage) {
		this.failureMessage = failureMessage;
	}

	/** 사용자에게 보이는 실패 문구입니다. 응답 본문이나 예외 메시지를 섞지 않습니다. OK면 null입니다. */
	public String failureMessage() {
		return failureMessage;
	}

}
