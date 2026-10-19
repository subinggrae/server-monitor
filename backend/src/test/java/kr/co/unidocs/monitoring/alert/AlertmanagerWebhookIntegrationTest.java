package kr.co.unidocs.monitoring.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Alertmanager가 보내는 것과 같은 HTTP 요청을 띄운 Manager에 보내고, DB에 남은 기록을 봅니다. */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "monitoring-manager.notification.alertmanager-webhook-token=" + AlertmanagerWebhookIntegrationTest.TOKEN)
@DisplayName("Alertmanager webhook")
class AlertmanagerWebhookIntegrationTest {

	static final String TOKEN = "integration-test-token";

	// check_id가 없는 Alert는 기록만 하고 Telegram으로 보내지 않습니다.
	private static final String PAYLOAD = """
			{"version":"4","groupKey":"g","status":"firing","alerts":[{"status":"firing",
			"labels":{"alertname":"MonitoringCheckDown"},"annotations":{"summary":"down"},
			"startsAt":"2026-10-19T00:00:00Z","endsAt":"0001-01-01T00:00:00Z","fingerprint":"integration-test"}]}
			""";

	@Container
	@ServiceConnection
	static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4");

	@LocalServerPort
	private int port;

	@Autowired
	private AlertEventRepository alertEventRepository;

	@BeforeEach
	void clearAlertEvents() {
		alertEventRepository.deleteAll();
	}

	@Test
	@DisplayName("token이 다르면 401을 돌려주고 Alert를 기록하지 않는다")
	void rejectsWrongToken() throws Exception {
		HttpResponse<Void> response = post("Bearer wrong-token");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(alertEventRepository.count()).isZero();
	}

	@Test
	@DisplayName("token이 맞으면 Alert를 기록한다")
	void recordsAlertWithMatchingToken() throws Exception {
		HttpResponse<Void> response = post("Bearer " + TOKEN);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(alertEventRepository.count()).isEqualTo(1);
	}

	private HttpResponse<Void> post(String authorization) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/integrations/alertmanager/webhook"))
				.header("Content-Type", "application/json")
				.header("Authorization", authorization)
				.POST(HttpRequest.BodyPublishers.ofString(PAYLOAD))
				.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.discarding());
		}
	}

}
