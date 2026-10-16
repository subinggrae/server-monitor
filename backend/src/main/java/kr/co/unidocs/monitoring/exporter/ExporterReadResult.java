package kr.co.unidocs.monitoring.exporter;

/**
 * exporter를 한 번 읽은 결과입니다. 응답 본문과 헤더는 담지 않습니다.
 *
 * @param httpStatus 응답을 받지 못했으면 null입니다.
 * @param metrics outcome이 OK일 때만 있습니다.
 */
public record ExporterReadResult(ExporterOutcome outcome, Integer httpStatus, ExporterMetrics metrics,
		Long responseTimeMs) {

	static ExporterReadResult failed(ExporterOutcome outcome, Integer httpStatus, Long responseTimeMs) {
		return new ExporterReadResult(outcome, httpStatus, null, responseTimeMs);
	}

}
