package kr.co.unidocs.monitoring;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Manager Database를 PostgreSQL로 쓸 때의 검증입니다.
 *
 * AWS RDS PostgreSQL을 쓰는 경우를 대비해 MariaDB와 같은 내용을 실행합니다.
 */
@Import(PostgresManagerDatabaseTest.PostgresConfiguration.class)
class PostgresManagerDatabaseTest extends AbstractSnmpApiTest {

	@Override
	protected String successLiteral() {
		return "true";
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class PostgresConfiguration {

		@Bean
		@ServiceConnection
		PostgreSQLContainer managerDatabase() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
		}

	}

}
