package kr.co.unidocs.monitoring.prometheus;

import java.time.Instant;

public record StorageSample(long totalBytes, long usedBytes, Instant sampledAt) {

	public Double usagePercent() {
		if (totalBytes <= 0) {
			return null;
		}
		return (usedBytes * 100.0) / totalBytes;
	}

}
