package kr.co.unidocs.monitoring.server;

/** 서버의 OS입니다. OS exporter의 기본 포트와 리소스 찾기 방식이 OS마다 다릅니다. */
public enum ServerOs {

	LINUX(9100),

	WINDOWS(9182);

	private final int defaultExporterPort;

	ServerOs(int defaultExporterPort) {
		this.defaultExporterPort = defaultExporterPort;
	}

	public int defaultExporterPort() {
		return defaultExporterPort;
	}

}
