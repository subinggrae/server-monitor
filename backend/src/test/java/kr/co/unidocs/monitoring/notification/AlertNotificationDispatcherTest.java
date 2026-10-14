package kr.co.unidocs.monitoring.notification;

import kr.co.unidocs.monitoring.alert.AlertStatus;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties.Notification;
import kr.co.unidocs.monitoring.notification.routing.AlertSeverity;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationBinding;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationBindingRepository;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationSettingRepository;
import kr.co.unidocs.monitoring.notification.telegram.TelegramClient;
import kr.co.unidocs.monitoring.notification.telegram.TelegramDestination;
import kr.co.unidocs.monitoring.notification.telegram.TelegramSendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Alert 알림 전송의 재발송 주기")
class AlertNotificationDispatcherTest {

	private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");

	private static final long CHECK_ID = 1L;

	private static final long ALERT_EVENT_ID = 10L;

	private static final long DESTINATION_ID = 20L;

	private final CheckNotificationBindingRepository bindingRepository = mock(CheckNotificationBindingRepository.class);

	private final CheckNotificationSettingRepository settingRepository = mock(CheckNotificationSettingRepository.class);

	private final NotificationDeliveryRepository deliveryRepository = mock(NotificationDeliveryRepository.class);

	private final TelegramClient telegramClient = mock(TelegramClient.class);

	/** 전송 기록 저장소를 흉내 냅니다. 조회 조건(상태 포함)을 실제 query처럼 적용합니다. */
	private final List<NotificationDelivery> deliveries = new ArrayList<>();

	private AlertNotificationDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		MonitoringManagerProperties properties = new MonitoringManagerProperties("secret", null, null, null, null,
				null, new Notification(Duration.ofMinutes(2), ZoneId.of("Asia/Seoul"), null), null);
		dispatcher = new AlertNotificationDispatcher(bindingRepository, settingRepository, deliveryRepository,
				telegramClient, new AlertMessageFormatter(properties), properties, Clock.fixed(NOW, ZoneOffset.UTC));

		TelegramDestination destination = new TelegramDestination("운영팀", -100L, null, true);
		ReflectionTestUtils.setField(destination, "id", DESTINATION_ID);
		when(bindingRepository.findAllByCheckId(CHECK_ID))
				.thenReturn(List.of(new CheckNotificationBinding(CHECK_ID, destination, false, true, true, true)));
		when(telegramClient.sendMessage(anyLong(), any(), anyString())).thenReturn(TelegramSendResult.sent());
		stubDeliveryRepository();
	}

	@Test
	@DisplayName("마지막 전송 뒤 재발송 주기가 지나지 않았으면 보내지 않고 전송 기록도 남기지 않는다")
	void skipsBeforeRepeatInterval() {
		givenRepeatInterval(60);
		givenDelivery(AlertStatus.FIRING, DeliveryStatus.SENT, NOW.minus(Duration.ofMinutes(30)));

		dispatcher.dispatch(firing());

		verify(telegramClient, never()).sendMessage(anyLong(), any(), anyString());
		assertThat(deliveries).hasSize(1);
	}

	@Test
	@DisplayName("마지막 전송 뒤 재발송 주기가 지났으면 다시 보내고 SENT로 기록한다")
	void resendsAfterRepeatInterval() {
		givenRepeatInterval(60);
		givenDelivery(AlertStatus.FIRING, DeliveryStatus.SENT, NOW.minus(Duration.ofMinutes(60)));

		dispatcher.dispatch(firing());

		verify(telegramClient, times(1)).sendMessage(anyLong(), any(), anyString());
		assertThat(deliveries).extracting(NotificationDelivery::getDeliveryStatus)
				.containsExactly(DeliveryStatus.SENT, DeliveryStatus.SENT);
	}

	@Test
	@DisplayName("같은 마지막 전송 시각이라도 재발송 주기를 줄이면 다음 webhook에서 보낸다")
	void usesChangedRepeatInterval() {
		givenDelivery(AlertStatus.FIRING, DeliveryStatus.SENT, NOW.minus(Duration.ofMinutes(30)));
		givenRepeatInterval(60);
		dispatcher.dispatch(firing());

		givenRepeatInterval(15);
		dispatcher.dispatch(firing());

		verify(telegramClient, times(1)).sendMessage(anyLong(), any(), anyString());
	}

	@Test
	@DisplayName("보낸 기록이 없는 첫 FIRING 알림은 바로 보낸다")
	void sendsFirstFiring() {
		givenRepeatInterval(1440);

		dispatcher.dispatch(firing());

		verify(telegramClient, times(1)).sendMessage(anyLong(), any(), anyString());
	}

	@Test
	@DisplayName("복구 알림은 방금 FIRING을 보냈어도 재발송 주기와 관계없이 보낸다")
	void sendsResolvedRegardlessOfRepeatInterval() {
		givenRepeatInterval(1440);
		givenDelivery(AlertStatus.FIRING, DeliveryStatus.SENT, NOW.minus(Duration.ofMinutes(1)));

		dispatcher.dispatch(resolved());

		verify(telegramClient, times(1)).sendMessage(anyLong(), any(), anyString());
	}

	@Test
	@DisplayName("실패한 전송만 있으면 보낸 것으로 세지 않고 다시 보낸다")
	void ignoresFailedDeliveries() {
		givenRepeatInterval(60);
		givenDelivery(AlertStatus.FIRING, DeliveryStatus.FAILED, NOW.minus(Duration.ofMinutes(5)));

		dispatcher.dispatch(firing());

		verify(telegramClient, times(1)).sendMessage(anyLong(), any(), anyString());
	}

	private void givenRepeatInterval(int minutes) {
		when(settingRepository.findRepeatIntervalMinutes(CHECK_ID)).thenReturn(minutes);
	}

	private void givenDelivery(AlertStatus alertStatus, DeliveryStatus deliveryStatus, Instant attemptedAt) {
		NotificationDelivery delivery = new NotificationDelivery(ALERT_EVENT_ID, DESTINATION_ID, alertStatus,
				deliveryStatus, null);
		ReflectionTestUtils.setField(delivery, "attemptedAt", attemptedAt);
		deliveries.add(delivery);
	}

	private void stubDeliveryRepository() {
		when(deliveryRepository.save(any(NotificationDelivery.class))).then(invocation -> {
			NotificationDelivery delivery = invocation.getArgument(0);
			ReflectionTestUtils.setField(delivery, "attemptedAt", NOW);
			deliveries.add(delivery);
			return delivery;
		});
		when(deliveryRepository
				.findFirstByAlertEventIdAndTelegramDestinationIdAndAlertStatusAndDeliveryStatusOrderByAttemptedAtDesc(
						any(), any(), any(), any()))
				.then(invocation -> deliveries.stream()
						.filter(delivery -> delivery.getAlertEventId().equals(invocation.getArgument(0))
								&& delivery.getTelegramDestinationId().equals(invocation.getArgument(1))
								&& delivery.getAlertStatus() == invocation.getArgument(2)
								&& delivery.getDeliveryStatus() == invocation.getArgument(3))
						.max(Comparator.comparing(NotificationDelivery::getAttemptedAt)));
		when(deliveryRepository
				.existsByAlertEventIdAndTelegramDestinationIdAndAlertStatusAndDeliveryStatusAndAttemptedAtAfter(
						any(), any(), any(), any(), any()))
				.then(invocation -> deliveries.stream()
						.anyMatch(delivery -> delivery.getAlertEventId().equals(invocation.getArgument(0))
								&& delivery.getTelegramDestinationId().equals(invocation.getArgument(1))
								&& delivery.getAlertStatus() == invocation.getArgument(2)
								&& delivery.getDeliveryStatus() == invocation.getArgument(3)
								&& delivery.getAttemptedAt().isAfter(invocation.getArgument(4))));
	}

	private AlertNotification firing() {
		return notification(false);
	}

	private AlertNotification resolved() {
		return notification(true);
	}

	private AlertNotification notification(boolean resolved) {
		return new AlertNotification(ALERT_EVENT_ID, CHECK_ID, "전자결재 Health", "Production", "10.0.0.1",
				AlertSeverity.CRITICAL, "critical", resolved, "응답하지 않습니다.", NOW.minus(Duration.ofHours(2)),
				resolved ? NOW : null);
	}

}
