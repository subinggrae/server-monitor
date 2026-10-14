package kr.co.unidocs.monitoring.snmp;

/**
 * hrStorageTable의 한 행입니다. type을 보고 memory인지 disk인지 가릅니다.
 *
 * 크기는 byte가 아니라 allocationUnits 단위입니다. byte로 바꾸려면 곱해야 합니다.
 */
public record SnmpStorageEntry(int index, String type, String description, long allocationUnits, long size,
		long used) {

	public long totalBytes() {
		return size * allocationUnits;
	}

	public long usedBytes() {
		return used * allocationUnits;
	}

	public boolean isPhysicalMemory() {
		return SnmpOids.STORAGE_TYPE_RAM.equals(type);
	}

	public boolean isDisk() {
		return SnmpOids.STORAGE_TYPE_FIXED_DISK.equals(type) || SnmpOids.STORAGE_TYPE_NETWORK_DISK.equals(type);
	}

}
