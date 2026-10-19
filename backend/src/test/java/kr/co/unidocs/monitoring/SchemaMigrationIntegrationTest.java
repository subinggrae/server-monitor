package kr.co.unidocs.monitoring;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지원하는 두 DB의 빈 schema에 migration을 적용하고 Entity와 맞는지 확인합니다.
 * 맞지 않으면 Hibernate의 schema 검증(ddl-auto: validate)이 기동을 막습니다.
 */
@Tag("integration")
@DisplayName("빈 Manager Database에 설치")
class SchemaMigrationIntegrationTest {

	private static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4");

	private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:17");

	@BeforeAll
	static void startDatabases() {
		Stream.of(MARIADB, POSTGRESQL).parallel().forEach(JdbcDatabaseContainer::start);
	}

	@AfterAll
	static void stopDatabases() {
		Stream.of(MARIADB, POSTGRESQL).forEach(JdbcDatabaseContainer::stop);
	}

	static Stream<JdbcDatabaseContainer<?>> databases() {
		return Stream.of(MARIADB, POSTGRESQL);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("databases")
	@DisplayName("빈 DB에 migration을 적용하면 Entity와 맞아 기동한다")
	void startsOnEmptyDatabase(JdbcDatabaseContainer<?> database) {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MonitoringManagerApplication.class)
				.web(WebApplicationType.NONE)
				.properties("spring.datasource.url=" + database.getJdbcUrl(),
						"spring.datasource.username=" + database.getUsername(),
						"spring.datasource.password=" + database.getPassword())
				.run()) {
			assertThat(context.isActive()).isTrue();
		}
	}

}
