package kr.co.unidocs.monitoring.notification.telegram;

import kr.co.unidocs.monitoring.TestProperties;
import kr.co.unidocs.monitoring.config.ManagerSecrets;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Telegram Bot API를 부르는 부분을 직접 확인합니다.
 *
 * 다른 테스트에서는 이 class를 Mock으로 바꿉니다. 그래서 요청을 만드는 코드는 검증되지 않습니다.
 * 실제 Telegram 서버는 부르지 않습니다. Mock HTTP 서버를 씁니다.
 *
 * <h2>Bot Token이 새어 나가는지도 함께 봅니다</h2>
 *
 * Token은 URL 경로에 들어갑니다.
 * 요청이 실패하면 예외 메시지에 URL이 통째로 들어가고, 그 메시지는 로그와 DB로 흘러갑니다.
 */
class TelegramClientTest {

	private static final String BASE_URL = "https://api.telegram.test";

	private static final String BOT_TOKEN = "123456:AA-secret-bot-token";

	private MockRestServiceServer server;

	private TelegramClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.client = new TelegramClient(builder.build(), properties(), secrets(BOT_TOKEN));
	}

	@Test
	void sendsMessageToChat() {
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/sendMessage"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.chat_id").value(-1001234567890L))
				.andExpect(jsonPath("$.text").value("테스트 메시지"))
				// 일반 그룹에는 topic 정보를 넣지 않습니다.
				.andExpect(jsonPath("$.message_thread_id").doesNotExist())
				.andRespond(withSuccess("""
						{"ok":true,"result":{"message_id":10}}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.sendMessage(-1001234567890L, null, "테스트 메시지").success()).isTrue();
		server.verify();
	}

	@Test
	void sendsMessageThreadIdForForumTopic() {
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/sendMessage"))
				.andExpect(jsonPath("$.message_thread_id").value(42))
				.andRespond(withSuccess("""
						{"ok":true}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.sendMessage(-1001234567890L, 42, "테스트 메시지").success()).isTrue();
		server.verify();
	}

	@Test
	void sendsPlainTextWithoutParseMode() {
		// parse_mode를 넣으면 주소에 들어 있는 기호 때문에 Telegram이 메시지를 거부할 수 있습니다.
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/sendMessage"))
				.andExpect(jsonPath("$.parse_mode").doesNotExist())
				.andRespond(withSuccess("""
						{"ok":true}
						""", MediaType.APPLICATION_JSON));

		client.sendMessage(-1001L, null, "http://10.0.0.11:8080/actuator/health 확인 실패");
		server.verify();
	}

	@Test
	void reportsFailureWhenTelegramRejectsRequest() {
		// Telegram은 거부 이유를 4xx 응답 본문에 담아 보냅니다. 기본 예외 처리로 넘기면 이유를 잃습니다.
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/sendMessage"))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
						{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}
						"""));

		TelegramSendResult result = client.sendMessage(-1001L, null, "테스트 메시지");

		assertThat(result.success()).isFalse();
		assertThat(result.errorMessage()).isEqualTo("Bad Request: chat not found");
	}

	@Test
	void reportsFailureWhenTelegramReturnsServerError() {
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/sendMessage")).andRespond(withServerError());

		assertThat(client.sendMessage(-1001L, null, "테스트 메시지").success()).isFalse();
	}

	@Test
	void reportsFailureWhenTelegramDoesNotRespondInTime() throws IOException {
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/sendMessage"))
				.andRespond(withException(new SocketTimeoutException("Read timed out")));

		TelegramSendResult result = client.sendMessage(-1001L, null, "테스트 메시지");

		assertThat(result.success()).isFalse();
		assertThat(result.errorMessage()).contains("Read timed out");
	}

	@Test
	void neverLeaksBotTokenInErrorMessage() throws IOException {
		// 예외 메시지에는 요청 URL이 들어갑니다. URL에는 Token이 들어 있습니다.
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/sendMessage"))
				.andRespond(withException(new IOException("I/O error on POST request for \"" + BASE_URL + "/bot"
						+ BOT_TOKEN + "/sendMessage\": connection refused")));

		TelegramSendResult result = client.sendMessage(-1001L, null, "테스트 메시지");

		assertThat(result.errorMessage()).doesNotContain(BOT_TOKEN).contains("***");
	}

	@Test
	void reportsFailureWhenBotTokenIsMissing() {
		TelegramClient withoutToken = new TelegramClient(RestClient.builder().baseUrl(BASE_URL).build(), properties(),
				secrets(null));

		TelegramSendResult result = withoutToken.sendMessage(-1001L, null, "테스트 메시지");

		assertThat(withoutToken.configured()).isFalse();
		assertThat(result.success()).isFalse();
		// 무엇을 확인해야 하는지 알려 줍니다.
		assertThat(result.errorMessage()).contains("monitoring-manager.telegram.bot-token");
	}

	@Test
	void readsWebhookRegistrationState() {
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/getWebhookInfo")).andRespond(withSuccess("""
				{"ok":true,"result":{"url":"https://monitoring.example.com/api/integrations/telegram/webhook"}}
				""", MediaType.APPLICATION_JSON));

		TelegramClient.WebhookInfo info = client.webhookInfo();

		assertThat(info.available()).isTrue();
		assertThat(info.registered()).isTrue();
	}

	@Test
	void reportsWebhookIsNotRegisteredWhenUrlIsEmpty() {
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/getWebhookInfo")).andRespond(withSuccess("""
				{"ok":true,"result":{"url":""}}
				""", MediaType.APPLICATION_JSON));

		TelegramClient.WebhookInfo info = client.webhookInfo();

		assertThat(info.available()).isTrue();
		assertThat(info.registered()).isFalse();
	}

	@Test
	void registersWebhookWithSecretToken() {
		server.expect(requestTo(BASE_URL + "/bot" + BOT_TOKEN + "/setWebhook"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.url").value("https://monitoring.example.com/api/integrations/telegram/webhook"))
				.andExpect(jsonPath("$.secret_token").value("telegram-secret"))
				.andRespond(withSuccess("""
						{"ok":true,"result":true}
						""", MediaType.APPLICATION_JSON));

		TelegramSendResult result = client
				.setWebhook("https://monitoring.example.com/api/integrations/telegram/webhook", "telegram-secret");

		assertThat(result.success()).isTrue();
		server.verify();
	}

	private ManagerSecrets secrets(String botToken) {
		return new ManagerSecrets(TestProperties.defaults(BASE_URL, TestProperties.telegram(botToken, BASE_URL)));
	}

	private MonitoringManagerProperties properties() {
		return TestProperties.defaults(BASE_URL, TestProperties.telegram(null, BASE_URL));
	}

}
