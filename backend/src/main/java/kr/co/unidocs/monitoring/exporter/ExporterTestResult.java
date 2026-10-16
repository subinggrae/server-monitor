package kr.co.unidocs.monitoring.exporter;

import kr.co.unidocs.monitoring.server.ServerOs;

/**
 * 연결 시험 결과입니다. exporter 응답 본문과 예외 메시지는 담지 않고, 분류마다 정한 문구만 보냅니다.
 *
 * @param osMatches 성공했을 때만 있습니다. false여도 저장은 막지 않습니다.
 */
public record ExporterTestResult(boolean success, ExporterOutcome outcome, Integer httpStatus,
		ExporterType exporterType, String version, Boolean osMatches, String message, Long responseTimeMs) {

	static ExporterTestResult of(ExporterReadResult read, ServerOs serverOs) {
		if (read.outcome() != ExporterOutcome.OK) {
			return new ExporterTestResult(false, read.outcome(), read.httpStatus(), null, null, null,
					failureMessage(read.outcome()), read.responseTimeMs());
		}

		ExporterType type = read.metrics().exporterType();
		boolean osMatches = type.os() == serverOs;
		String message = osMatches ? "exporter를 읽었습니다."
				: "exporter는 응답했지만 서버에 고른 OS와 종류가 다릅니다. 서버 정보에서 OS를 확인해 주세요.";
		return new ExporterTestResult(true, read.outcome(), read.httpStatus(), type, read.metrics().version(),
				osMatches, message, read.responseTimeMs());
	}

	private static String failureMessage(ExporterOutcome outcome) {
		return switch (outcome) {
			case CONNECTION_REFUSED -> "exporter에 연결하지 못했습니다. exporter가 실행 중인지, 포트와 방화벽을 확인해 주세요.";
			case TIMEOUT -> "exporter가 제시간에 응답하지 않았습니다. 네트워크와 방화벽을 확인해 주세요.";
			case HTTP_ERROR -> "이 포트가 오류로 응답했습니다. OS exporter의 포트인지 확인해 주세요.";
			case UNREADABLE -> "exporter 응답을 읽지 못했습니다. OS exporter의 포트인지 확인해 주세요.";
			case NOT_OS_EXPORTER -> "이 포트의 응답은 node_exporter나 windows_exporter가 아닙니다. 포트를 확인해 주세요.";
			case UNKNOWN_HOST -> "서버의 호스트를 찾지 못했습니다. 서버 정보의 호스트를 확인해 주세요.";
			case OK -> throw new IllegalArgumentException("성공 결과에는 실패 문구가 없습니다.");
		};
	}

}
