package kr.co.unidocs.monitoring.exporter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 프로세스에 띄운 JDK HttpServer를 exporter 대신 씁니다. 바깥 네트워크에 닿지 않습니다. */
@DisplayName("OS exporter /metrics 읽기")
class ExporterClientTest {

	private static final String HOST = "127.0.0.1";

	private HttpServer server;

	private final List<String> requests = new CopyOnWriteArrayList<>();

	/** 느린 응답 처리기가 테스트가 끝난 뒤에도 남지 않도록 풀어 줍니다. */
	private final CountDownLatch release = new CountDownLatch(1);

	private final ExporterClient client = new ExporterClient(Duration.ofSeconds(2), Duration.ofSeconds(2),
			Duration.ofMillis(800), 64 * 1024, new ExporterMetricsParser(4 * 1024));

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(HOST), 0), 0);
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
	}

	@AfterEach
	void stopServer() {
		release.countDown();
		server.stop(0);
	}

	@Test
	@DisplayName("/metrics를 GET으로 읽어 exporter 종류와 버전을 돌려준다")
	void readsMetrics() throws IOException {
		byte[] body = ExporterMetricsParserTest.sample("node-exporter.metrics");
		respond("/", exchange -> send(exchange, 200, body));

		ExporterReadResult result = client.read(HOST, port());

		assertThat(result.outcome()).isEqualTo(ExporterOutcome.OK);
		assertThat(result.httpStatus()).isEqualTo(200);
		assertThat(result.metrics().exporterType()).isEqualTo(ExporterType.NODE_EXPORTER);
		assertThat(result.metrics().version()).isEqualTo("1.12.1");
		assertThat(result.responseTimeMs()).isNotNull().isNotNegative();
		assertThat(requests).containsExactly("GET /metrics");
	}

	@Test
	@DisplayName("리다이렉트를 따르지 않고 HTTP 오류로 끝낸다")
	void doesNotFollowRedirect() {
		respond("/", exchange -> {
			exchange.getResponseHeaders().add("Location", "/elsewhere");
			send(exchange, 302, new byte[0]);
		});

		ExporterReadResult result = client.read(HOST, port());

		assertThat(result.outcome()).isEqualTo(ExporterOutcome.HTTP_ERROR);
		assertThat(result.httpStatus()).isEqualTo(302);
		assertThat(result.metrics()).isNull();
		assertThat(requests).containsExactly("GET /metrics");
	}

	@Test
	@DisplayName("2xx가 아닌 응답은 HTTP 오류로 끝낸다")
	void reportsHttpError() {
		respond("/", exchange -> send(exchange, 404, "not found".getBytes()));

		ExporterReadResult result = client.read(HOST, port());

		assertThat(result.outcome()).isEqualTo(ExporterOutcome.HTTP_ERROR);
		assertThat(result.httpStatus()).isEqualTo(404);
	}

	@Test
	@DisplayName("본문이 한도보다 크면 읽기를 멈추고 읽지 못한 응답으로 끝낸다")
	void stopsAtBodyLimit() {
		// 한도가 없다면 OK로 읽히는 정상 응답입니다.
		byte[] large = ("node_exporter_build_info{version=\"1.12.1\"} 1\n" + "node_load1 0.5\n".repeat(10_000))
			.getBytes();
		respond("/", exchange -> send(exchange, 200, large));

		ExporterReadResult result = client.read(HOST, port());

		assertThat(result.outcome()).isEqualTo(ExporterOutcome.UNREADABLE);
		assertThat(result.metrics()).isNull();
	}

	@Test
	@DisplayName("본문이 마감 시간 안에 끝나지 않으면 시간 초과로 끝낸다")
	void stopsAtDeadline() {
		respond("/", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write("node_exporter_build_info{version=\"1\"} 1\n".getBytes());
				out.flush();
				release.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});

		long startedAt = System.nanoTime();
		ExporterReadResult result = client.read(HOST, port());

		assertThat(result.outcome()).isEqualTo(ExporterOutcome.TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	@DisplayName("닫힌 포트는 연결 거부로 끝낸다")
	void reportsConnectionRefused() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
			closedPort = socket.getLocalPort();
		}

		ExporterReadResult result = client.read(HOST, closedPort);

		assertThat(result.outcome()).isEqualTo(ExporterOutcome.CONNECTION_REFUSED);
		assertThat(result.httpStatus()).isNull();
	}

	@Test
	@DisplayName("호스트 이름으로 URI를 만들 수 없으면 알 수 없는 호스트로 끝낸다")
	void reportsInvalidHost() {
		ExporterReadResult result = client.read("bad host/name", port());

		assertThat(result.outcome()).isEqualTo(ExporterOutcome.UNKNOWN_HOST);
		assertThat(requests).isEmpty();
	}

	@Test
	@DisplayName("OS exporter가 아닌 응답은 그 이유로 끝낸다")
	void reportsNotOsExporter() {
		respond("/", exchange -> send(exchange, 200, "up 1\n".getBytes()));

		ExporterReadResult result = client.read(HOST, port());

		assertThat(result.outcome()).isEqualTo(ExporterOutcome.NOT_OS_EXPORTER);
		assertThat(result.httpStatus()).isEqualTo(200);
	}

	private int port() {
		return server.getAddress().getPort();
	}

	private void respond(String context, Handler handler) {
		server.createContext(context, exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			try {
				handler.handle(exchange);
			}
			finally {
				exchange.close();
			}
		});
	}

	private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
		exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
		if (body.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		}
	}

	@FunctionalInterface
	private interface Handler {

		void handle(HttpExchange exchange) throws IOException;

	}

}
