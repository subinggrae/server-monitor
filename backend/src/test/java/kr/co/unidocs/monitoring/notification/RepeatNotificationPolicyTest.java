package kr.co.unidocs.monitoring.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Alert가 이어지는 동안의 재발송 판단")
class RepeatNotificationPolicyTest {

	private static final Instant LAST_SENT = Instant.parse("2026-10-14T00:00:00Z");

	private static final Duration SIXTY_MINUTES = Duration.ofMinutes(60);

	@Test
	@DisplayName("보낸 기록이 없으면 보낸다")
	void sendsWithoutHistory() {
		assertThat(RepeatNotificationPolicy.shouldSend(Optional.empty(), SIXTY_MINUTES, LAST_SENT)).isTrue();
	}

	@Test
	@DisplayName("마지막 전송 뒤 주기에서 1분을 뺀 시간이 꼭 지났으면 보낸다")
	void sendsAtToleranceBoundary() {
		Instant now = LAST_SENT.plus(Duration.ofMinutes(59));

		assertThat(RepeatNotificationPolicy.shouldSend(Optional.of(LAST_SENT), SIXTY_MINUTES, now)).isTrue();
	}

	@Test
	@DisplayName("주기에서 1분을 뺀 시간보다 1초 모자라면 보내지 않는다")
	void skipsJustBeforeBoundary() {
		Instant now = LAST_SENT.plus(Duration.ofMinutes(59)).minusSeconds(1);

		assertThat(RepeatNotificationPolicy.shouldSend(Optional.of(LAST_SENT), SIXTY_MINUTES, now)).isFalse();
	}

	@Test
	@DisplayName("전송 기록이 webhook보다 조금 늦게 남아도 주기만큼 뒤에 온 webhook에서 보낸다")
	void sendsWhenDeliveryWasRecordedLate() {
		Instant webhookReceived = LAST_SENT;
		Instant recordedAfterTelegramCall = webhookReceived.plusMillis(800);
		Instant nextWebhook = webhookReceived.plus(SIXTY_MINUTES);

		assertThat(RepeatNotificationPolicy.shouldSend(Optional.of(recordedAfterTelegramCall), SIXTY_MINUTES,
				nextWebhook)).isTrue();
	}

	@Test
	@DisplayName("주기보다 한 webhook 간격(5분) 일찍 온 webhook에서는 보내지 않는다")
	void skipsOneWebhookIntervalEarly() {
		Instant now = LAST_SENT.plus(Duration.ofMinutes(55));

		assertThat(RepeatNotificationPolicy.shouldSend(Optional.of(LAST_SENT), SIXTY_MINUTES, now)).isFalse();
	}

}
