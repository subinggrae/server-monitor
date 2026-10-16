package kr.co.unidocs.monitoring.exporter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OS exporter의 /metrics 응답 해석")
class ExporterMetricsParserTest {

	private final ExporterMetricsParser parser = new ExporterMetricsParser(64 * 1024);

	@Test
	@DisplayName("node_exporter 표본에서 종류, 버전, CPU 수, Memory 크기, 파일 시스템을 읽는다")
	void readsNodeExporterSample() throws IOException {
		ExporterMetrics metrics = parser.parse(sample("node-exporter.metrics"));

		assertThat(metrics.exporterType()).isEqualTo(ExporterType.NODE_EXPORTER);
		assertThat(metrics.version()).isEqualTo("1.12.1");
		assertThat(metrics.cpuCount()).isEqualTo(2);
		assertThat(metrics.memoryTotalBytes()).isEqualTo(7_713_521_664L);
		assertThat(metrics.filesystems()).containsExactlyInAnyOrder(
				new ExporterFilesystem("/data", "ext4", 1_081_101_176_832L),
				new ExporterFilesystem("/run", "tmpfs", 812_646_400L),
				new ExporterFilesystem("/", "overlay", 1_081_101_176_832L));
	}

	@Test
	@DisplayName("windows_exporter 표본에서 종류, 버전, CPU 수, Memory 크기, volume을 읽는다")
	void readsWindowsExporterSample() throws IOException {
		ExporterMetrics metrics = parser.parse(sample("windows-exporter.metrics"));

		assertThat(metrics.exporterType()).isEqualTo(ExporterType.WINDOWS_EXPORTER);
		assertThat(metrics.version()).isEqualTo("0.31.9");
		assertThat(metrics.cpuCount()).isEqualTo(2);
		assertThat(metrics.memoryTotalBytes()).isEqualTo(16_000_000_000L);
		assertThat(metrics.filesystems()).extracting(ExporterFilesystem::key)
				.containsExactlyInAnyOrder("C:", "D:", "HarddiskVolume2");
	}

	@Test
	@DisplayName("label 값의 escape를 풀어 읽는다")
	void unescapesLabelValues() {
		String body = """
				node_exporter_build_info{version="1.12.1"} 1
				node_filesystem_size_bytes{fstype="ext4",mountpoint="/mnt/a\\"b\\\\c",} 100
				""";

		ExporterMetrics metrics = parser.parse(bytes(body));

		assertThat(metrics.filesystems()).containsExactly(new ExporterFilesystem("/mnt/a\"b\\c", "ext4", 100));
	}

	@Test
	@DisplayName("HTML 응답은 읽지 못한 응답으로 끝난다")
	void rejectsHtml() {
		assertOutcome("<html><body>login</body></html>\n", ExporterOutcome.UNREADABLE);
	}

	@Test
	@DisplayName("빈 응답은 읽지 못한 응답으로 끝난다")
	void rejectsEmptyBody() {
		assertOutcome("", ExporterOutcome.UNREADABLE);
	}

	@Test
	@DisplayName("읽는 metric의 값이 숫자가 아니면 읽지 못한 응답으로 끝난다")
	void rejectsBrokenValue() {
		assertOutcome("node_exporter_build_info{version=\"1.12.1\"} 1\nnode_memory_MemTotal_bytes abc\n",
				ExporterOutcome.UNREADABLE);
	}

	@Test
	@DisplayName("한 줄이 한도보다 길면 읽지 못한 응답으로 끝난다")
	void rejectsLongLine() {
		ExporterMetricsParser smallParser = new ExporterMetricsParser(100);
		String body = "node_exporter_build_info{version=\"1.12.1\"} 1\n# HELP x " + "a".repeat(200) + "\n";

		assertThatThrownBy(() -> smallParser.parse(bytes(body))).isInstanceOf(ExporterResponseException.class)
				.extracting(ex -> ((ExporterResponseException) ex).outcome())
				.isEqualTo(ExporterOutcome.UNREADABLE);
	}

	@Test
	@DisplayName("Prometheus 형식이지만 build_info가 없으면 OS exporter가 아니다")
	void rejectsOtherExporter() {
		assertOutcome("# TYPE up gauge\nup 1\nprocess_cpu_seconds_total 0.5\n", ExporterOutcome.NOT_OS_EXPORTER);
	}

	private void assertOutcome(String body, ExporterOutcome expected) {
		assertThatThrownBy(() -> parser.parse(bytes(body))).isInstanceOf(ExporterResponseException.class)
				.extracting(ex -> ((ExporterResponseException) ex).outcome())
				.isEqualTo(expected);
	}

	static byte[] sample(String name) throws IOException {
		try (InputStream in = ExporterMetricsParserTest.class.getResourceAsStream("/exporter/" + name)) {
			assertThat(in).as(name).isNotNull();
			return in.readAllBytes();
		}
	}

	private static byte[] bytes(String body) {
		return body.getBytes(StandardCharsets.UTF_8);
	}

}
