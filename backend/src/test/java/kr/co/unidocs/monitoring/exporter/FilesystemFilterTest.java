package kr.co.unidocs.monitoring.exporter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("리소스 찾기에서 고를 수 있는 파일 시스템")
class FilesystemFilterTest {

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "tmpfs", "ramfs", "devtmpfs", "overlay", "squashfs", "nsfs", "autofs", "fuse.sshfs" })
	@DisplayName("Linux의 임시·가상 파일 시스템은 뺀다")
	void excludesVirtualLinuxFilesystems(String fstype) {
		assertThat(FilesystemFilter.includes(ExporterType.NODE_EXPORTER, new ExporterFilesystem("/x", fstype, 1)))
				.isFalse();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "ext4", "xfs", "btrfs", "fuseblk" })
	@DisplayName("Linux의 디스크 파일 시스템은 남긴다")
	void includesLinuxDisks(String fstype) {
		assertThat(FilesystemFilter.includes(ExporterType.NODE_EXPORTER, new ExporterFilesystem("/x", fstype, 1)))
				.isTrue();
	}

	@Test
	@DisplayName("255자를 넘는 mountpoint는 resource_key에 담을 수 없어 뺀다")
	void excludesTooLongMountpoint() {
		String atLimit = "/" + "a".repeat(254);
		String overLimit = "/" + "a".repeat(255);

		assertThat(FilesystemFilter.includes(ExporterType.NODE_EXPORTER, new ExporterFilesystem(atLimit, "ext4", 1)))
				.isTrue();
		assertThat(FilesystemFilter.includes(ExporterType.NODE_EXPORTER, new ExporterFilesystem(overLimit, "ext4", 1)))
				.isFalse();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "C:", "Z:" })
	@DisplayName("Windows는 드라이브 이름이 있는 volume을 남긴다")
	void includesWindowsDrives(String volume) {
		assertThat(FilesystemFilter.includes(ExporterType.WINDOWS_EXPORTER, new ExporterFilesystem(volume, null, 1)))
				.isTrue();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "HarddiskVolume2", "c:", "C:\\mnt", "CD:" })
	@DisplayName("Windows의 드라이브 이름이 없는 volume은 뺀다")
	void excludesWindowsVolumesWithoutDrive(String volume) {
		assertThat(FilesystemFilter.includes(ExporterType.WINDOWS_EXPORTER, new ExporterFilesystem(volume, null, 1)))
				.isFalse();
	}

}
