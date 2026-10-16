package kr.co.unidocs.monitoring.exporter;

import kr.co.unidocs.monitoring.server.ServerOs;

/** build_info metric으로 알아낸 exporter 종류입니다. */
public enum ExporterType {

	NODE_EXPORTER(ServerOs.LINUX),

	WINDOWS_EXPORTER(ServerOs.WINDOWS);

	private final ServerOs os;

	ExporterType(ServerOs os) {
		this.os = os;
	}

	public ServerOs os() {
		return os;
	}

}
