package kr.co.unidocs.monitoring.exporter;

/** 설정이 없으면 configured가 false이고, 나머지 값은 서버 OS의 기본값입니다. */
public record ServerExporterResponse(boolean configured, boolean enabled, int port, int scrapeIntervalSeconds,
		int defaultPort) {
}
