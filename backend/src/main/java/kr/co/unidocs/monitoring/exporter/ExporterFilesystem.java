package kr.co.unidocs.monitoring.exporter;

/**
 * exporter가 내보낸 디스크 하나입니다.
 *
 * @param key Linux는 mountpoint, Windows는 volume 이름입니다. recording rule의 resource_key와 같습니다.
 * @param fstype Linux만 있습니다.
 */
public record ExporterFilesystem(String key, String fstype, long sizeBytes) {
}
