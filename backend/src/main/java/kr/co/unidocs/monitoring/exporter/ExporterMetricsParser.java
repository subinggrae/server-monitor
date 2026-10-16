package kr.co.unidocs.monitoring.exporter;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prometheus text format(0.0.4)의 /metrics 응답에서 정한 family만 읽습니다.
 *
 * 형식에 맞지 않는 줄이 하나라도 있으면 Prometheus도 수집에 실패하므로 응답 전체를 읽지 못한 것으로 봅니다.
 * 읽지 않는 family도 형식은 확인합니다.
 */
class ExporterMetricsParser {

	/** 한 서버에서 이보다 많으면 화면에서 고를 수 있는 수준을 넘습니다. */
	private static final int MAX_FILESYSTEMS = 200;

	private final int maxLineBytes;

	ExporterMetricsParser(int maxLineBytes) {
		this.maxLineBytes = maxLineBytes;
	}

	ExporterMetrics parse(byte[] body) {
		Collector collector = new Collector();
		int sampleLines = 0;
		int start = 0;
		while (start < body.length) {
			int end = indexOf(body, (byte) '\n', start);
			int lineEnd = (end < 0) ? body.length : end;
			if (lineEnd - start > maxLineBytes) {
				throw new ExporterResponseException(ExporterOutcome.UNREADABLE);
			}
			String line = new String(body, start, lineEnd - start, StandardCharsets.UTF_8).strip();
			if (!line.isEmpty() && line.charAt(0) != '#') {
				collector.accept(new LineReader(line).readSample());
				sampleLines++;
			}
			start = lineEnd + 1;
		}
		if (sampleLines == 0) {
			throw new ExporterResponseException(ExporterOutcome.UNREADABLE);
		}
		return collector.result();
	}

	private static int indexOf(byte[] bytes, byte target, int from) {
		for (int i = from; i < bytes.length; i++) {
			if (bytes[i] == target) {
				return i;
			}
		}
		return -1;
	}

	private record Sample(String name, Map<String, String> labels, double value) {
	}

	/** 읽은 sample 가운데 필요한 것만 모읍니다. */
	private static final class Collector {

		private ExporterType type;

		private String version;

		private final Set<String> nodeCpus = new LinkedHashSet<>();

		private final Set<String> windowsCores = new LinkedHashSet<>();

		private Long nodeMemory;

		private Long windowsMemory;

		private final Map<String, ExporterFilesystem> nodeFilesystems = new LinkedHashMap<>();

		private final Map<String, ExporterFilesystem> windowsVolumes = new LinkedHashMap<>();

		void accept(Sample sample) {
			Map<String, String> labels = sample.labels();
			switch (sample.name()) {
				case "node_exporter_build_info" -> buildInfo(ExporterType.NODE_EXPORTER, labels);
				case "windows_exporter_build_info" -> buildInfo(ExporterType.WINDOWS_EXPORTER, labels);
				case "node_cpu_seconds_total" -> {
					if ("idle".equals(labels.get("mode")) && labels.containsKey("cpu")) {
						nodeCpus.add(labels.get("cpu"));
					}
				}
				case "windows_cpu_time_total" -> {
					if ("idle".equals(labels.get("mode")) && labels.containsKey("core")) {
						windowsCores.add(labels.get("core"));
					}
				}
				case "node_memory_MemTotal_bytes" -> nodeMemory = bytes(sample.value());
				case "windows_memory_physical_total_bytes" -> windowsMemory = bytes(sample.value());
				case "node_filesystem_size_bytes" ->
					addFilesystem(nodeFilesystems, labels.get("mountpoint"), labels.get("fstype"), sample.value());
				case "windows_logical_disk_size_bytes" ->
					addFilesystem(windowsVolumes, labels.get("volume"), null, sample.value());
				default -> {
				}
			}
		}

		private void buildInfo(ExporterType found, Map<String, String> labels) {
			if (type == null) {
				type = found;
				version = labels.get("version");
			}
		}

		private void addFilesystem(Map<String, ExporterFilesystem> target, String key, String fstype, double size) {
			Long sizeBytes = bytes(size);
			if (key == null || sizeBytes == null || target.size() >= MAX_FILESYSTEMS) {
				return;
			}
			target.putIfAbsent(key, new ExporterFilesystem(key, fstype, sizeBytes));
		}

		private static Long bytes(double value) {
			return (Double.isFinite(value) && value >= 0) ? (long) value : null;
		}

		ExporterMetrics result() {
			if (type == null) {
				throw new ExporterResponseException(ExporterOutcome.NOT_OS_EXPORTER);
			}
			return switch (type) {
				case NODE_EXPORTER -> new ExporterMetrics(type, version, nodeCpus.size(), nodeMemory,
						List.copyOf(nodeFilesystems.values()));
				case WINDOWS_EXPORTER -> new ExporterMetrics(type, version, windowsCores.size(), windowsMemory,
						List.copyOf(windowsVolumes.values()));
			};
		}

	}

	/** sample 한 줄: name{label="value",...} value [timestamp] */
	private static final class LineReader {

		private final String line;

		private int pos;

		LineReader(String line) {
			this.line = line;
		}

		Sample readSample() {
			String name = readName(true);
			Map<String, String> labels = Map.of();
			if (peek() == '{') {
				pos++;
				labels = readLabels();
			}
			if (!skipSpaces()) {
				throw unreadable();
			}
			int valueStart = pos;
			while (pos < line.length() && !Character.isWhitespace(line.charAt(pos))) {
				pos++;
			}
			double value = parseValue(line.substring(valueStart, pos));
			skipSpaces();
			if (pos < line.length()) {
				// timestamp는 쓰지 않지만 정수여야 합니다.
				try {
					Long.parseLong(line.substring(pos));
				}
				catch (NumberFormatException ex) {
					throw unreadable();
				}
			}
			return new Sample(name, labels, value);
		}

		private Map<String, String> readLabels() {
			Map<String, String> labels = new HashMap<>();
			while (true) {
				skipSpaces();
				if (peek() == '}') {
					pos++;
					return labels;
				}
				String labelName = readName(false);
				skipSpaces();
				expect('=');
				skipSpaces();
				expect('"');
				labels.put(labelName, readQuoted());
				skipSpaces();
				if (peek() == ',') {
					pos++;
				}
				else if (peek() != '}') {
					throw unreadable();
				}
			}
		}

		private String readQuoted() {
			StringBuilder value = new StringBuilder();
			while (pos < line.length()) {
				char c = line.charAt(pos++);
				if (c == '"') {
					return value.toString();
				}
				if (c == '\\') {
					if (pos >= line.length()) {
						break;
					}
					char escaped = line.charAt(pos++);
					value.append(switch (escaped) {
						case 'n' -> '\n';
						case '\\', '"' -> escaped;
						default -> throw unreadable();
					});
				}
				else {
					value.append(c);
				}
			}
			throw unreadable();
		}

		private String readName(boolean allowColon) {
			int start = pos;
			while (pos < line.length() && isNameChar(line.charAt(pos), pos == start, allowColon)) {
				pos++;
			}
			if (pos == start) {
				throw unreadable();
			}
			return line.substring(start, pos);
		}

		private static boolean isNameChar(char c, boolean first, boolean allowColon) {
			return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || (allowColon && c == ':')
					|| (!first && c >= '0' && c <= '9');
		}

		private static double parseValue(String token) {
			return switch (token) {
				case "NaN" -> Double.NaN;
				case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
				case "-Inf" -> Double.NEGATIVE_INFINITY;
				default -> {
					try {
						yield Double.parseDouble(token);
					}
					catch (NumberFormatException ex) {
						throw unreadable();
					}
				}
			};
		}

		/** 공백을 하나 이상 건너뛰었으면 true입니다. */
		private boolean skipSpaces() {
			int start = pos;
			while (pos < line.length() && (line.charAt(pos) == ' ' || line.charAt(pos) == '\t')) {
				pos++;
			}
			return pos > start;
		}

		private char peek() {
			return (pos < line.length()) ? line.charAt(pos) : '\0';
		}

		private void expect(char expected) {
			if (peek() != expected) {
				throw unreadable();
			}
			pos++;
		}

		private static ExporterResponseException unreadable() {
			return new ExporterResponseException(ExporterOutcome.UNREADABLE);
		}

	}

}
