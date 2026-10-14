package kr.co.unidocs.monitoring.prometheus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server 한 대에서 수집한 SNMP metric입니다.
 *
 * {@code up}이 false면 snmp_exporter가 장비에서 값을 가져오지 못한 것입니다.
 * Prometheus 자체 장애와 구분하려면 {@link SnmpMetricSnapshot#available()}을 함께 봅니다.
 */
public final class ServerSnmpMetrics {

	private final boolean up;

	private final Instant scrapedAt;

	private Double cpuPercent;

	private Instant cpuSampledAt;

	private StorageSample memory;

	/** key는 hrStorageDescr입니다. 사용자가 고른 resourceKey와 같은 값입니다. */
	private final Map<String, StorageSample> disks = new LinkedHashMap<>();

	private ServerSnmpMetrics(boolean up, Instant scrapedAt) {
		this.up = up;
		this.scrapedAt = scrapedAt;
	}

	static ServerSnmpMetrics of(boolean up, Instant scrapedAt) {
		return new ServerSnmpMetrics(up, scrapedAt);
	}

	void setCpuPercent(double value, Instant sampledAt) {
		this.cpuPercent = value;
		this.cpuSampledAt = sampledAt;
	}

	void setMemory(StorageSample sample) {
		this.memory = sample;
	}

	void putDisk(String description, StorageSample sample) {
		this.disks.put(description, sample);
	}

	public boolean isUp() {
		return up;
	}

	public Instant getScrapedAt() {
		return scrapedAt;
	}

	public Double getCpuPercent() {
		return cpuPercent;
	}

	public Instant getCpuSampledAt() {
		return cpuSampledAt;
	}

	public StorageSample getMemory() {
		return memory;
	}

	public StorageSample disk(String description) {
		return disks.get(description);
	}

	public boolean hasAnyValue() {
		return cpuPercent != null || memory != null || !disks.isEmpty();
	}

}
