package kr.co.unidocs.monitoring.server;

import kr.co.unidocs.monitoring.check.MonitoringStatus;
import kr.co.unidocs.monitoring.snmp.SnmpState;

import java.time.Instant;

public record ServerTargetResponse(
		Long id,
		String name,
		String host,
		String environment,
		String description,
		boolean enabled,
		/** Threshold가 아직 없어 사용률로 CRITICAL을 만들지 않습니다. OK / UNKNOWN / DISABLED만 나옵니다. */
		MonitoringStatus status,
		SnmpState snmpState,
		int monitoredResourceCount,
		Instant createdAt,
		Instant updatedAt) {

	public static ServerTargetResponse of(ServerTarget server, MonitoringStatus status, SnmpState snmpState,
			int monitoredResourceCount) {
		return new ServerTargetResponse(server.getId(), server.getName(), server.getHost(), server.getEnvironment(),
				server.getDescription(), server.isEnabled(), status, snmpState, monitoredResourceCount,
				server.getCreatedAt(), server.getUpdatedAt());
	}

}
