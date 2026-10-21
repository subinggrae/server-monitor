package kr.co.unidocs.monitoring;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 다른 앱이 참고하는 리소스 임계값 DDL(docs/reference-schema/)이 기준 migration 위에 적용되는지 확인합니다.
 * 이 앱은 그 테이블을 쓰지 않으므로 앱을 띄우지 않고 Flyway와 SQL 실행만 합니다.
 *
 * 컨테이너를 매개변수로 받는 테스트는 autoCloseArguments를 끕니다. 켜 두면 JUnit이 테스트마다 컨테이너를 닫습니다.
 */
@Tag("integration")
@DisplayName("참고용 리소스 임계값 DDL")
class ReferenceSchemaIntegrationTest {

	private static final Path REFERENCE_SCHEMA = Path.of("../docs/reference-schema");

	private static final Set<String> TABLES = Set.of("resource_thresholds", "server_resource_alert_settings",
			"resource_alert_states", "resource_alert_events");

	private static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4");

	private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:17");

	@BeforeAll
	static void applySchemas() {
		Stream.of(MARIADB, POSTGRESQL).parallel().forEach(JdbcDatabaseContainer::start);
		Stream.of(MARIADB, POSTGRESQL).forEach(database -> {
			String vendor = (database == MARIADB) ? "mariadb" : "postgresql";
			Flyway.configure()
				.dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
				.locations("classpath:db/migration/" + vendor)
				.load()
				.migrate();
			new ResourceDatabasePopulator(
					new FileSystemResource(REFERENCE_SCHEMA.resolve(vendor).resolve("resource-thresholds.sql")))
				.execute(dataSource(database));
		});
	}

	@AfterAll
	static void stopDatabases() {
		Stream.of(MARIADB, POSTGRESQL).forEach(JdbcDatabaseContainer::stop);
	}

	static Stream<Named<JdbcDatabaseContainer<?>>> databases() {
		return Stream.of(Named.of("MariaDB", MARIADB), Named.of("PostgreSQL", POSTGRESQL));
	}

	@ParameterizedTest(name = "{0}", autoCloseArguments = false)
	@MethodSource("databases")
	@DisplayName("기준 migration 위에 적용하면 네 테이블이 생긴다")
	void createsTables(JdbcDatabaseContainer<?> database) {
		Set<String> tables = columns(database).stream()
			.map(column -> column.substring(0, column.indexOf('.')))
			.collect(Collectors.toSet());

		assertThat(tables).isEqualTo(TABLES);
	}

	@Test
	@DisplayName("두 DB의 테이블, 열 이름, NULL 허용이 같다")
	void vendorsShareLogicalSchema() {
		assertThat(columns(MARIADB)).isNotEmpty().isEqualTo(columns(POSTGRESQL));
	}

	@ParameterizedTest(name = "{0}", autoCloseArguments = false)
	@MethodSource("databases")
	@DisplayName("Resource를 지우면 임계값과 판단 상태가 지워지고 발생 기록은 남는다")
	void deletingResourceKeepsEvents(JdbcDatabaseContainer<?> database) {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource(database));
		long serverId = insertServer(jdbc, "resource-delete");
		long resourceId = insertResourceWithAlert(jdbc, serverId);

		jdbc.update("DELETE FROM monitored_resources WHERE id = ?", resourceId);

		assertThat(count(jdbc, "resource_thresholds", "monitored_resource_id", resourceId)).isZero();
		assertThat(count(jdbc, "resource_alert_states", "monitored_resource_id", resourceId)).isZero();
		assertThat(count(jdbc, "resource_alert_events", "monitored_resource_id", resourceId)).isOne();
		assertThat(count(jdbc, "server_resource_alert_settings", "server_id", serverId)).isOne();
	}

	@ParameterizedTest(name = "{0}", autoCloseArguments = false)
	@MethodSource("databases")
	@DisplayName("서버를 지우면 재발송 주기와 Resource의 설정이 지워지고 발생 기록은 서버 이름과 함께 남는다")
	void deletingServerKeepsEvents(JdbcDatabaseContainer<?> database) {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource(database));
		long serverId = insertServer(jdbc, "server-delete");
		long resourceId = insertResourceWithAlert(jdbc, serverId);

		jdbc.update("DELETE FROM server_targets WHERE id = ?", serverId);

		assertThat(count(jdbc, "server_resource_alert_settings", "server_id", serverId)).isZero();
		assertThat(count(jdbc, "resource_thresholds", "monitored_resource_id", resourceId)).isZero();
		assertThat(count(jdbc, "resource_alert_states", "monitored_resource_id", resourceId)).isZero();
		assertThat(jdbc.queryForList("SELECT server_name FROM resource_alert_events WHERE server_id = ?", String.class,
				serverId)).containsExactly("server-delete");
	}

	@ParameterizedTest(name = "{0}", autoCloseArguments = false)
	@MethodSource("databases")
	@DisplayName("한 단계를 비운 임계값과 아직 발생하지 않은 대기 상태를 저장한다")
	void storesBlankStageAndPendingState(JdbcDatabaseContainer<?> database) {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource(database));
		long resourceId = insertResource(jdbc, insertServer(jdbc, "pending-state"));

		jdbc.update("""
				INSERT INTO resource_thresholds
				    (monitored_resource_id, warning_percent, critical_percent, duration_minutes, created_at, updated_at)
				VALUES (?, NULL, 90, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", resourceId);
		jdbc.update("""
				INSERT INTO resource_alert_states
				    (monitored_resource_id, severity, exceeded_since, resource_alert_event_id, last_notified_at, updated_at)
				VALUES (?, 'CRITICAL', CURRENT_TIMESTAMP, NULL, NULL, CURRENT_TIMESTAMP)
				""", resourceId);

		assertThat(count(jdbc, "resource_thresholds", "monitored_resource_id", resourceId)).isOne();
		assertThat(count(jdbc, "resource_alert_states", "monitored_resource_id", resourceId)).isOne();
	}

	@ParameterizedTest(name = "{0}", autoCloseArguments = false)
	@MethodSource("databases")
	@DisplayName("한 Resource에 임계값 행이나 같은 단계의 판단 상태를 두 번 넣지 못한다")
	void rejectsDuplicateThresholdAndStage(JdbcDatabaseContainer<?> database) {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource(database));
		long resourceId = insertResourceWithAlert(jdbc, insertServer(jdbc, "duplicate"));

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO resource_thresholds
				    (monitored_resource_id, warning_percent, critical_percent, duration_minutes, created_at, updated_at)
				VALUES (?, 70, 95, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", resourceId)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO resource_alert_states (monitored_resource_id, severity, exceeded_since, updated_at)
				VALUES (?, 'CRITICAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", resourceId)).isInstanceOf(DataIntegrityViolationException.class);
	}

	private static long insertServer(JdbcTemplate jdbc, String name) {
		jdbc.update("""
				INSERT INTO server_targets (name, host, environment, created_at, updated_at)
				VALUES (?, '10.0.0.1', 'Production', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", name);
		jdbc.update("""
				INSERT INTO server_resource_alert_settings (server_id, repeat_interval_minutes, created_at, updated_at)
				SELECT id, 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM server_targets WHERE name = ?
				""", name);
		return jdbc.queryForObject("SELECT id FROM server_targets WHERE name = ?", Long.class, name);
	}

	private static long insertResource(JdbcTemplate jdbc, long serverId) {
		jdbc.update("""
				INSERT INTO monitored_resources (server_id, resource_type, resource_key, display_name, created_at, updated_at)
				VALUES (?, 'STORAGE', '/', '/', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", serverId);
		return jdbc.queryForObject("SELECT id FROM monitored_resources WHERE server_id = ?", Long.class, serverId);
	}

	/** 임계값, 발생 중인 판단 상태, 그 발생 기록을 함께 만듭니다. */
	private static long insertResourceWithAlert(JdbcTemplate jdbc, long serverId) {
		long resourceId = insertResource(jdbc, serverId);
		jdbc.update("""
				INSERT INTO resource_thresholds
				    (monitored_resource_id, warning_percent, critical_percent, duration_minutes, created_at, updated_at)
				VALUES (?, 80, 90, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", resourceId);
		jdbc.update("""
				INSERT INTO resource_alert_events
				    (server_id, server_name, monitored_resource_id, resource_type, resource_key, display_name, severity,
				     threshold_percent, usage_percent, fired_at, created_at)
				SELECT id, name, ?, 'STORAGE', '/', '/', 'CRITICAL', 90, 95.5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
				FROM server_targets WHERE id = ?
				""", resourceId, serverId);
		long eventId = jdbc.queryForObject("SELECT id FROM resource_alert_events WHERE monitored_resource_id = ?",
				Long.class, resourceId);
		jdbc.update("""
				INSERT INTO resource_alert_states
				    (monitored_resource_id, severity, exceeded_since, resource_alert_event_id, last_notified_at, updated_at)
				VALUES (?, 'CRITICAL', CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", resourceId, eventId);
		return resourceId;
	}

	private static int count(JdbcTemplate jdbc, String table, String column, long id) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
	}

	/** 참고용 DDL이 만든 테이블의 열을 "테이블.열:NULL 허용" 형식으로 돌려줍니다. */
	private static Set<String> columns(JdbcDatabaseContainer<?> database) {
		String currentSchema = (database == MARIADB) ? "DATABASE()" : "current_schema()";
		List<String> rows = new JdbcTemplate(dataSource(database)).query("""
				SELECT table_name, column_name, is_nullable FROM information_schema.columns
				WHERE table_schema = %s
				""".formatted(currentSchema),
				(rs, rowNum) -> rs.getString(1).toLowerCase() + "." + rs.getString(2).toLowerCase() + ":"
						+ rs.getString(3));
		return rows.stream()
			.filter(row -> TABLES.contains(row.substring(0, row.indexOf('.'))))
			.collect(Collectors.toSet());
	}

	private static DriverManagerDataSource dataSource(JdbcDatabaseContainer<?> database) {
		return new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
	}

}
