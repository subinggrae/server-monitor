package kr.co.unidocs.monitoring.exporter;

import java.util.List;

/** 연결 시험과 리소스 찾기에 필요한 값만 /metrics에서 읽은 결과입니다. 사용률은 Prometheus가 계산합니다. */
public record ExporterMetrics(ExporterType exporterType, String version, int cpuCount, Long memoryTotalBytes,
		List<ExporterFilesystem> filesystems) {
}
