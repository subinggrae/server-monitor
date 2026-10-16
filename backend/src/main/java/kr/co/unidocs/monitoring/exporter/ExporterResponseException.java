package kr.co.unidocs.monitoring.exporter;

/** 응답 본문을 메시지에 담지 않습니다. 분류만 밖으로 나갑니다. */
class ExporterResponseException extends RuntimeException {

	private final ExporterOutcome outcome;

	ExporterResponseException(ExporterOutcome outcome) {
		super(outcome.name(), null, false, false);
		this.outcome = outcome;
	}

	ExporterOutcome outcome() {
		return outcome;
	}

}
