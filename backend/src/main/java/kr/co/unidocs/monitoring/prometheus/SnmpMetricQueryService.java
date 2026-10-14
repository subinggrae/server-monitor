package kr.co.unidocs.monitoring.prometheus;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server가 몇 대든 Prometheus query 횟수는 그대로입니다.
 * 목록 화면에서 서버마다 query를 던지면 서버가 늘어날수록 화면이 느려집니다.
 *
 * Metric 이름과 label은 snmp_exporter가 실제로 내보내는 값 그대로입니다.
 */
@Service
public class SnmpMetricQueryService {

	private static final String JOB = "manager-snmp";

	/** hrStorageType이 hrStorageRam인 행이 physical memory입니다. */
	private static final String STORAGE_TYPE_RAM = "1.3.6.1.2.1.25.2.1.2";

	private final PrometheusQueryService prometheusQueryService;

	SnmpMetricQueryService(PrometheusQueryService prometheusQueryService) {
		this.prometheusQueryService = prometheusQueryService;
	}

	public SnmpMetricSnapshot snapshot() {
		PrometheusQueryResult up = prometheusQueryService.query("up{job=\"" + JOB + "\"}");
		if (!up.available()) {
			return SnmpMetricSnapshot.unavailable();
		}

		// hrProcessorLoad는 processor마다 한 행입니다. Server 하나의 CPU 사용률로 보려면 평균을 냅니다.
		PrometheusQueryResult cpu = prometheusQueryService
				.query("avg by (server_id) (hrProcessorLoad{job=\"" + JOB + "\"})");
		PrometheusQueryResult size = prometheusQueryService.query("hrStorageSize{job=\"" + JOB + "\"}");
		PrometheusQueryResult used = prometheusQueryService.query("hrStorageUsed{job=\"" + JOB + "\"}");
		PrometheusQueryResult units = prometheusQueryService
				.query("hrStorageAllocationUnits{job=\"" + JOB + "\"}");

		Map<Long, ServerSnmpMetrics> byServer = new LinkedHashMap<>();
		for (PrometheusQueryResult.Sample sample : up.samples()) {
			Long serverId = serverId(sample);
			if (serverId != null) {
				byServer.put(serverId, ServerSnmpMetrics.of(sample.value() == 1, sample.sampledAt()));
			}
		}

		applyCpu(byServer, cpu);
		applyStorage(byServer, size, used, units);

		return new SnmpMetricSnapshot(true, Map.copyOf(byServer));
	}

	private void applyCpu(Map<Long, ServerSnmpMetrics> byServer, PrometheusQueryResult cpu) {
		for (PrometheusQueryResult.Sample sample : cpu.samples()) {
			Long serverId = serverId(sample);
			ServerSnmpMetrics metrics = (serverId != null) ? byServer.get(serverId) : null;
			if (metrics != null) {
				metrics.setCpuPercent(sample.value(), sample.sampledAt());
			}
		}
	}

	/**
	 * hrStorage의 크기는 byte가 아니라 hrStorageAllocationUnits 단위입니다.
	 * 세 metric을 (server_id, hrStorageIndex)로 맞춰야 byte를 구할 수 있습니다.
	 */
	private void applyStorage(Map<Long, ServerSnmpMetrics> byServer, PrometheusQueryResult size,
			PrometheusQueryResult used, PrometheusQueryResult units) {
		Map<String, StorageRow> rows = new HashMap<>();

		for (PrometheusQueryResult.Sample sample : size.samples()) {
			StorageRow row = rows.computeIfAbsent(storageKey(sample), key -> StorageRow.from(sample));
			row.size = sample.value();
			row.sampledAt = sample.sampledAt();
		}
		for (PrometheusQueryResult.Sample sample : used.samples()) {
			StorageRow row = rows.get(storageKey(sample));
			if (row != null) {
				row.used = sample.value();
			}
		}
		for (PrometheusQueryResult.Sample sample : units.samples()) {
			StorageRow row = rows.get(storageKey(sample));
			if (row != null) {
				row.allocationUnits = sample.value();
			}
		}

		for (StorageRow row : rows.values()) {
			ServerSnmpMetrics metrics = (row.serverId != null) ? byServer.get(row.serverId) : null;
			if (metrics == null || row.allocationUnits <= 0 || row.size <= 0 || row.description == null) {
				continue;
			}

			StorageSample sample = new StorageSample(Math.round(row.size * row.allocationUnits),
					Math.round(row.used * row.allocationUnits), row.sampledAt);
			if (STORAGE_TYPE_RAM.equals(row.type)) {
				metrics.setMemory(sample);
			}
			else {
				metrics.putDisk(row.description, sample);
			}
		}
	}

	private String storageKey(PrometheusQueryResult.Sample sample) {
		return sample.label("server_id") + '\u0000' + sample.label("hrStorageIndex");
	}

	private Long serverId(PrometheusQueryResult.Sample sample) {
		String value = sample.label("server_id");
		if (value == null) {
			return null;
		}
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/** 세 metric을 합치는 동안만 쓰는 임시 값입니다. */
	private static final class StorageRow {

		private Long serverId;

		private String description;

		private String type;

		private double size;

		private double used;

		private double allocationUnits;

		private Instant sampledAt;

		static StorageRow from(PrometheusQueryResult.Sample sample) {
			StorageRow row = new StorageRow();
			String serverId = sample.label("server_id");
			if (serverId != null) {
				try {
					row.serverId = Long.parseLong(serverId);
				}
				catch (NumberFormatException ex) {
					row.serverId = null;
				}
			}
			row.description = sample.label("hrStorageDescr");
			row.type = sample.label("hrStorageType");
			return row;
		}

	}

}
