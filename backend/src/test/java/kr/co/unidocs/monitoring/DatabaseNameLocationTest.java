package kr.co.unidocs.monitoring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 테이블과 열 이름은 JPA Entity와 기준 migration에만 둡니다(docs/INTEGRATION.md의 이름 바꾸기).
 * 네이티브 SQL이 없으면 열 이름은 Entity의 어노테이션 밖에 나올 곳이 없으므로, 네이티브 SQL을 쓰지 않는지 봅니다.
 */
@DisplayName("DB 이름의 위치")
class DatabaseNameLocationTest {

	private static final Path MAIN_JAVA = Path.of("src/main/java");

	private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

	private static final Path MIGRATIONS = MAIN_RESOURCES.resolve("db/migration");

	private static final Pattern TABLE_ANNOTATION = Pattern.compile("@Table\\(name = \"([a-z0-9_]+)\"\\)");

	private static final List<String> NATIVE_SQL_APIS = List.of("nativeQuery", "createNativeQuery", "JdbcTemplate",
			"JdbcClient", "NamedParameterJdbcTemplate");

	private static Map<Path, String> mainJavaSources;

	@BeforeAll
	static void readSources() {
		mainJavaSources = readAll(MAIN_JAVA);
	}

	@Test
	@DisplayName("Entity가 테이블 이름을 정한다")
	void entitiesDeclareTableNames() {
		assertThat(tableNamesByEntity()).isNotEmpty();
	}

	@Test
	@DisplayName("테이블 이름은 그 Entity와 migration 밖의 소스에 나오지 않는다")
	void tableNamesAppearOnlyInEntityAndMigrations() {
		Map<Path, String> sources = new HashMap<>(mainJavaSources);
		readAll(MAIN_RESOURCES).forEach((path, text) -> {
			if (!path.startsWith(MIGRATIONS)) {
				sources.put(path, text);
			}
		});

		tableNamesByEntity().forEach((tableName, entity) -> sources.forEach((path, text) -> {
			if (!path.equals(entity)) {
				assertThat(text).as("%s에 테이블 이름 %s", path, tableName).doesNotContain(tableName);
			}
		}));
	}

	@Test
	@DisplayName("네이티브 SQL을 쓰지 않는다")
	void doesNotUseNativeSql() {
		mainJavaSources.forEach((path, text) -> NATIVE_SQL_APIS
				.forEach(api -> assertThat(text).as("%s의 %s", path, api).doesNotContain(api)));
	}

	private static Map<String, Path> tableNamesByEntity() {
		Map<String, Path> tableNames = new HashMap<>();
		mainJavaSources.forEach((path, text) -> {
			Matcher matcher = TABLE_ANNOTATION.matcher(text);
			while (matcher.find()) {
				tableNames.put(matcher.group(1), path);
			}
		});
		return tableNames;
	}

	private static Map<Path, String> readAll(Path root) {
		Map<Path, String> texts = new HashMap<>();
		try (Stream<Path> paths = Files.walk(root)) {
			paths.filter(Files::isRegularFile).forEach(path -> texts.put(path, read(path)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return texts;
	}

	private static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
