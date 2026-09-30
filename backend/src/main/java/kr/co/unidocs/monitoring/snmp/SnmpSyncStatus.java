package kr.co.unidocs.monitoring.snmp;

import java.time.Instant;

/**
 * DB 저장은 됐는데 exporter 반영이 실패할 수 있습니다. 그 상태를 숨기지 않고 그대로 보여 줍니다.
 *
 * message에는 community나 파일 내용을 넣지 않습니다. 화면에 그대로 나갑니다.
 */
public record SnmpSyncStatus(boolean succeeded, String message, int authCount, Instant syncedAt) {

	static SnmpSyncStatus notSyncedYet() {
		return new SnmpSyncStatus(true, "아직 반영할 SNMP 설정이 없습니다.", 0, null);
	}

	static SnmpSyncStatus succeeded(int authCount) {
		return new SnmpSyncStatus(true, "snmp_exporter에 반영했습니다.", authCount, Instant.now());
	}

	static SnmpSyncStatus failed(String message) {
		return new SnmpSyncStatus(false, message, 0, Instant.now());
	}

}
