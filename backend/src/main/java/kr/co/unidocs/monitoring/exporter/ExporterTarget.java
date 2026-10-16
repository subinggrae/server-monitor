package kr.co.unidocs.monitoring.exporter;

import kr.co.unidocs.monitoring.server.ServerOs;

/** 리소스 찾기에서 읽을 exporter 주소입니다. 저장하고 켠 수집 설정에서만 만듭니다. */
public record ExporterTarget(String host, int port, ServerOs os) {
}
