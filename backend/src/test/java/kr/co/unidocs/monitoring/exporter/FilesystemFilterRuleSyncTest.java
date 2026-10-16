package kr.co.unidocs.monitoring.exporter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 제외 목록이 Prometheus 규칙 파일과 Manager 두 곳에 있으므로 어긋나지 않는지 확인합니다. */
@DisplayName("파일 시스템 제외 목록과 recording rule의 일치")
class FilesystemFilterRuleSyncTest {

	private static final Path RULE_FILE = Path.of("..", "infrastructure", "prometheus", "rules",
			"server-resources.yml");

	@Test
	@DisplayName("규칙 파일의 Linux fstype 제외 목록이 FilesystemFilter와 같다")
	void linuxExcludedFstypesMatchRuleFile() throws IOException {
		assertThat(matchers("fstype!~")).containsExactly(FilesystemFilter.LINUX_EXCLUDED_FSTYPES);
	}

	@Test
	@DisplayName("규칙 파일의 Windows volume 조건이 FilesystemFilter와 같다")
	void windowsVolumePatternMatchesRuleFile() throws IOException {
		assertThat(matchers("volume=~")).containsExactly(FilesystemFilter.WINDOWS_VOLUME_PATTERN);
	}

	/** 규칙 파일에서 같은 label 조건이 여러 번 나오므로 서로 다른 값만 모읍니다. PromQL 문자열의 \\는 \로 풉니다. */
	private Set<String> matchers(String prefix) throws IOException {
		String rules = Files.readString(RULE_FILE);
		Matcher matcher = Pattern.compile(Pattern.quote(prefix) + "\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(rules);
		Set<String> values = new LinkedHashSet<>();
		while (matcher.find()) {
			values.add(matcher.group(1).replace("\\\\", "\\"));
		}
		assertThat(values).as(prefix + " in " + RULE_FILE).isNotEmpty();
		return values;
	}

}
