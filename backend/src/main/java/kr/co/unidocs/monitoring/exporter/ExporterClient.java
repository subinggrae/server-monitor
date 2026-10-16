package kr.co.unidocs.monitoring.exporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 연결 시험과 리소스 찾기에서 exporter의 /metrics를 한 번 읽습니다.
 *
 * 사용자가 입력한 주소로 Manager가 요청을 보내므로 다음을 지킵니다.
 * 경로는 /metrics로 고정하고 GET만 보냅니다. 리다이렉트를 따르지 않습니다.
 * 본문 크기와 전체 시간에 한도를 둡니다. 본문, 헤더, 예외 메시지는 결과와 로그에 넣지 않습니다.
 */
@Component
public class ExporterClient {

	private static final Logger log = LoggerFactory.getLogger(ExporterClient.class);

	private static final String PATH = "/metrics";

	private final HttpClient httpClient;

	private final Duration headerTimeout;

	private final Duration deadline;

	private final int maxBodyBytes;

	private final ExporterMetricsParser parser;

	ExporterClient() {
		this(Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(8), 16 * 1024 * 1024,
				new ExporterMetricsParser(64 * 1024));
	}

	ExporterClient(Duration connectTimeout, Duration headerTimeout, Duration deadline, int maxBodyBytes,
			ExporterMetricsParser parser) {
		this.httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(connectTimeout)
			.build();
		this.headerTimeout = headerTimeout;
		this.deadline = deadline;
		this.maxBodyBytes = maxBodyBytes;
		this.parser = parser;
	}

	public ExporterReadResult read(String host, int port) {
		URI uri;
		try {
			uri = new URI("http", null, host, port, PATH, null, null);
		}
		catch (URISyntaxException ex) {
			return ExporterReadResult.failed(ExporterOutcome.UNKNOWN_HOST, null, null);
		}
		if (uri.getHost() == null) {
			return ExporterReadResult.failed(ExporterOutcome.UNKNOWN_HOST, null, null);
		}

		HttpRequest request = HttpRequest.newBuilder(uri)
			.GET()
			.timeout(headerTimeout)
			.header("Accept", "text/plain;version=0.0.4")
			.header("Accept-Encoding", "identity")
			.build();

		long startedAt = System.nanoTime();
		CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(request,
				info -> new LimitedBodySubscriber(maxBodyBytes));
		ExporterReadResult result;
		try {
			HttpResponse<byte[]> response = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
			result = toResult(response, elapsedMillis(startedAt));
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			result = ExporterReadResult.failed(ExporterOutcome.TIMEOUT, null, null);
		}
		catch (ExecutionException ex) {
			result = ExporterReadResult.failed(classify(ex.getCause()), null, null);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			result = ExporterReadResult.failed(ExporterOutcome.TIMEOUT, null, null);
		}

		if (result.outcome() != ExporterOutcome.OK) {
			log.info("exporter를 읽지 못했습니다. host={} port={} outcome={}", host, port, result.outcome());
		}
		return result;
	}

	private ExporterReadResult toResult(HttpResponse<byte[]> response, long elapsedMillis) {
		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			return ExporterReadResult.failed(ExporterOutcome.HTTP_ERROR, status, elapsedMillis);
		}
		try {
			return new ExporterReadResult(ExporterOutcome.OK, status, parser.parse(response.body()), elapsedMillis);
		}
		catch (ExporterResponseException ex) {
			return ExporterReadResult.failed(ex.outcome(), status, elapsedMillis);
		}
	}

	private static ExporterOutcome classify(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ExporterResponseException responseException) {
				return responseException.outcome();
			}
			if (cause instanceof UnresolvedAddressException || cause instanceof UnknownHostException) {
				return ExporterOutcome.UNKNOWN_HOST;
			}
			if (cause instanceof HttpTimeoutException) {
				return ExporterOutcome.TIMEOUT;
			}
		}
		if (failure instanceof ConnectException) {
			return ExporterOutcome.CONNECTION_REFUSED;
		}
		return ExporterOutcome.UNREADABLE;
	}

	private static long elapsedMillis(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
	}

	/** 본문이 한도를 넘으면 더 받지 않고 끊습니다. */
	private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

		private final int maxBytes;

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		private final CompletableFuture<byte[]> body = new CompletableFuture<>();

		private Flow.Subscription subscription;

		LimitedBodySubscriber(int maxBytes) {
			this.maxBytes = maxBytes;
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return body;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			if (body.isDone()) {
				return;
			}
			for (ByteBuffer item : items) {
				if (buffer.size() + item.remaining() > maxBytes) {
					subscription.cancel();
					body.completeExceptionally(new ExporterResponseException(ExporterOutcome.UNREADABLE));
					return;
				}
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				buffer.write(chunk, 0, chunk.length);
			}
		}

		@Override
		public void onError(Throwable throwable) {
			body.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			body.complete(buffer.toByteArray());
		}

	}

}
