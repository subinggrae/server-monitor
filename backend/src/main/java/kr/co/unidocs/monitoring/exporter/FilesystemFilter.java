package kr.co.unidocs.monitoring.exporter;

import java.util.regex.Pattern;

/**
 * 리소스 찾기에서 고를 수 있는 디스크를 정합니다.
 *
 * 같은 조건이 infrastructure/prometheus/rules/server-resources.yml에도 있습니다.
 * 한쪽만 바꾸면 고른 디스크의 사용률이 비므로 FilesystemFilterRuleSyncTest가 둘을 비교합니다.
 */
public final class FilesystemFilter {

	/** 메모리나 다른 파일 시스템 위에 올라간 것이라 디스크 사용률로 볼 수 없는 fstype입니다. */
	static final String LINUX_EXCLUDED_FSTYPES = "tmpfs|ramfs|devtmpfs|overlay|squashfs|nsfs|autofs|fuse\\..*";

	/** 드라이브 문자가 없는 volume(복구 파티션 등)은 뺍니다. */
	static final String WINDOWS_VOLUME_PATTERN = "[A-Z]:";

	/** {@code MonitoredResource.resourceKey}에 저장할 수 있는 길이입니다. */
	private static final int MAX_KEY_LENGTH = 255;

	private static final Pattern LINUX_EXCLUDED = Pattern.compile(LINUX_EXCLUDED_FSTYPES);

	private static final Pattern WINDOWS_VOLUME = Pattern.compile(WINDOWS_VOLUME_PATTERN);

	private FilesystemFilter() {
	}

	public static boolean includes(ExporterType type, ExporterFilesystem filesystem) {
		if (filesystem.key() == null || filesystem.key().isEmpty() || filesystem.key().length() > MAX_KEY_LENGTH) {
			return false;
		}
		return switch (type) {
			case NODE_EXPORTER -> filesystem.fstype() == null
					|| !LINUX_EXCLUDED.matcher(filesystem.fstype()).matches();
			case WINDOWS_EXPORTER -> WINDOWS_VOLUME.matcher(filesystem.key()).matches();
		};
	}

}
