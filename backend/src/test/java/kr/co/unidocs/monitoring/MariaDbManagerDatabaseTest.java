package kr.co.unidocs.monitoring;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Manager Database를 MariaDB로 쓸 때의 검증입니다.
 *
 * Docker가 필요합니다. Docker를 쓸 수 없는 환경에서는 이 테스트가 실패합니다.
 */
@Import(MariaDbManagerDatabaseTest.MariaDbConfiguration.class)
class MariaDbManagerDatabaseTest extends AbstractSnmpApiTest {

	@Override
	protected String successLiteral() {
		return "1";
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MariaDbConfiguration {

		@Bean
		@ServiceConnection
		MariaDBContainer managerDatabase() {
			// 재현 가능한 테스트를 위해 image tag를 고정합니다. latest는 쓰지 않습니다.
			return new MariaDBContainer(DockerImageName.parse("mariadb:11.4"));
		}

	}

}
