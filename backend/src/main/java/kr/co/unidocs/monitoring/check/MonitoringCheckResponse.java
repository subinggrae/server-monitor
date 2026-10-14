package kr.co.unidocs.monitoring.check;

import kr.co.unidocs.monitoring.check.CheckStatusService.ProbeSnapshot;

import java.time.Instant;
import java.util.List;

public record MonitoringCheckResponse(
		Long id,
		String name,
		CheckType type,
		String typeDisplayName,
		String environment,
		String description,
		boolean enabled,
		MonitoringStatus status,
		int targetCount,
		List<TargetResponse> targets,
		Instant createdAt,
		Instant updatedAt) {

	public static MonitoringCheckResponse of(MonitoringCheck check, ProbeSnapshot snapshot) {
		List<TargetResponse> targets = check.getTargets().stream()
				.map(target -> TargetResponse.of(target, snapshot))
				.toList();

		return new MonitoringCheckResponse(check.getId(), check.getName(), check.getType(),
				check.getType().displayName(), check.getEnvironment(), check.getDescription(), check.isEnabled(),
				snapshot.statusOf(check), targets.size(), targets, check.getCreatedAt(), check.getUpdatedAt());
	}

	public record TargetResponse(
			Long id,
			String target,
			Integer port,
			String blackboxTarget,
			boolean enabled,
			MonitoringStatus status,
			Instant checkedAt) {

		static TargetResponse of(MonitoringCheckTarget target, ProbeSnapshot snapshot) {
			CheckStatusService.ProbeResult probeResult = snapshot.probeResultOf(target.getId());

			return new TargetResponse(target.getId(), target.getTarget(), target.getPort(), target.blackboxTarget(),
					target.isEnabled(), snapshot.statusOf(target),
					(probeResult != null) ? probeResult.sampledAt() : null);
		}

	}

}
