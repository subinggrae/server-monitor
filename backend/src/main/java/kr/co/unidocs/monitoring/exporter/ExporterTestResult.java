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
					read.outcome().failureMessage(), read.responseTimeMs());
		}

		ExporterType type = read.metrics().exporterType();
		boolean osMatches = type.os() == serverOs;
		String message = osMatches ? "exporter를 읽었습니다."
				: "exporter는 응답했지만 서버에 고른 OS와 종류가 다릅니다. 서버 정보에서 OS를 확인해 주세요.";
		return new ExporterTestResult(true, read.outcome(), read.httpStatus(), type, read.metrics().version(),
				osMatches, message, read.responseTimeMs());
	}

}
