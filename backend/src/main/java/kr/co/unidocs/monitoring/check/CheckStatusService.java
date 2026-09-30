package kr.co.unidocs.monitoring.check;

import kr.co.unidocs.monitoring.prometheus.PrometheusQueryResult;
import kr.co.unidocs.monitoring.prometheus.PrometheusQueryService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CheckStatusService {

	private static final String PROBE_SUCCESS_QUERY = "probe_success{job=\"manager-blackbox\"}";

	private static final String CHECK_TARGET_ID_LABEL = "check_target_id";

	private final PrometheusQueryService prometheusQueryService;

	CheckStatusService(PrometheusQueryService prometheusQueryService) {
		this.prometheusQueryService = prometheusQueryService;
	}

	/** 화면에 대상이 몇 개든 Prometheus query는 한 번만 실행합니다. */
	public ProbeSnapshot loadProbeSnapshot() {
		PrometheusQueryResult result = prometheusQueryService.query(PROBE_SUCCESS_QUERY);
		if (!result.available()) {
			return ProbeSnapshot.unavailable();
		}

		Map<Long, ProbeResult> byTargetId = new HashMap<>();
		for (PrometheusQueryResult.Sample sample : result.samples()) {
			Long targetId = parseId(sample.label(CHECK_TARGET_ID_LABEL));
			if (targetId == null) {
				continue;
			}

			// Label 구성이 바뀌면 같은 Target에 여러 series가 생깁니다. 더 최근 것을 씁니다.
			ProbeResult candidate = new ProbeResult(sample.value() == 1.0d, sample.sampledAt());
			byTargetId.merge(targetId, candidate,
					(existing, replacement) -> replacement.sampledAt().isAfter(existing.sampledAt())
							? replacement : existing);
		}

		return new ProbeSnapshot(true, Map.copyOf(byTargetId));
	}

	private Long parseId(String rawId) {
		if (rawId == null || rawId.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(rawId);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	public record ProbeSnapshot(boolean prometheusAvailable, Map<Long, ProbeResult> resultsByTargetId) {

		private static final ProbeSnapshot UNAVAILABLE = new ProbeSnapshot(false, Map.of());

		public static ProbeSnapshot unavailable() {
			return UNAVAILABLE;
		}

		public MonitoringStatus statusOf(MonitoringCheckTarget target) {
			if (!target.isActive()) {
				return MonitoringStatus.DISABLED;
			}
			if (!prometheusAvailable) {
				return MonitoringStatus.UNKNOWN;
			}

			ProbeResult probeResult = resultsByTargetId.get(target.getId());
			if (probeResult == null) {
				return MonitoringStatus.UNKNOWN;
			}
			return probeResult.success() ? MonitoringStatus.OK : MonitoringStatus.CRITICAL;
		}

		public MonitoringStatus statusOf(MonitoringCheck check) {
			if (!check.isEnabled()) {
				return MonitoringStatus.DISABLED;
			}

			List<MonitoringStatus> targetStatuses = new ArrayList<>();
			for (MonitoringCheckTarget target : check.activeTargets()) {
				targetStatuses.add(statusOf(target));
			}
			return MonitoringStatus.aggregate(targetStatuses);
		}

		public ProbeResult probeResultOf(Long targetId) {
			return resultsByTargetId.get(targetId);
		}

	}

	public record ProbeResult(boolean success, Instant sampledAt) {
	}

}
