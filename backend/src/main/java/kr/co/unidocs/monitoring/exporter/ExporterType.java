package kr.co.unidocs.monitoring.exporter;

import kr.co.unidocs.monitoring.server.ServerOs;

/** build_info metric으로 알아낸 exporter 종류입니다. */
public enum ExporterType {

	NODE_EXPORTER(ServerOs.LINUX, "node_exporter_build_info"),

	WINDOWS_EXPORTER(ServerOs.WINDOWS, "windows_exporter_build_info");

	private final ServerOs os;

	private final String buildInfoMetric;

	ExporterType(ServerOs os, String buildInfoMetric) {
		this.os = os;
		this.buildInfoMetric = buildInfoMetric;
	}

	public ServerOs os() {
		return os;
	}

	String buildInfoMetric() {
		return buildInfoMetric;
	}

}
