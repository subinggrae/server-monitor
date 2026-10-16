package kr.co.unidocs.monitoring.notification;

import kr.co.unidocs.monitoring.alert.AlertStatus;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationBinding;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationBindingRepository;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationSettingRepository;
import kr.co.unidocs.monitoring.notification.telegram.TelegramClient;
import kr.co.unidocs.monitoring.notification.telegram.TelegramDestination;
import kr.co.unidocs.monitoring.notification.telegram.TelegramSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/**
 * 알림 대상은 Manager Database가 정합니다. Prometheus label로 정하지 않습니다. (CLAUDE.md 4번)
 *
 * 연결이 없는 Check는 그냥 넘어갑니다.
 * 기본 그룹을 몰래 정해 두면 운영자가 모르는 곳으로 장애 알림이 갑니다.
 */
@Service
public class AlertNotificationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(AlertNotificationDispatcher.class);

	private final CheckNotificationBindingRepository bindingRepository;

	private final CheckNotificationSettingRepository settingRepository;

	private final NotificationDeliveryRepository deliveryRepository;

	private final TelegramClient telegramClient;

	private final AlertMessageFormatter messageFormatter;

	private final Duration dedupeWindow;

	private final Clock clock;

	/** Alert마다 락을 만들면 계속 쌓이므로, 고정된 수의 락을 나눠 씁니다. 가상 스레드를 막지 않게 synchronized 대신 씁니다. */
	private final Lock[] deliveryLocks = IntStream.range(0, 64).mapToObj(i -> new ReentrantLock()).toArray(Lock[]::new);

	AlertNotificationDispatcher(CheckNotificationBindingRepository bindingRepository,
			CheckNotificationSettingRepository settingRepository, NotificationDeliveryRepository deliveryRepository,
			TelegramClient telegramClient, AlertMessageFormatter messageFormatter,
			MonitoringManagerProperties properties, Clock clock) {
		this.bindingRepository = bindingRepository;
		this.settingRepository = settingRepository;
		this.deliveryRepository = deliveryRepository;
		this.telegramClient = telegramClient;
		this.messageFormatter = messageFormatter;
		this.dedupeWindow = properties.notification().dedupeWindow();
		this.clock = clock;
	}

	public void dispatch(AlertNotification notification) {
		if (notification.checkId() == null) {
			log.warn("Alert에 check_id가 없어 알림을 보내지 않았습니다. alert_event_id={} (Prometheus 규칙의 label을 확인해 주세요)",
					notification.alertEventId());
			return;
		}

		List<Recipient> recipients = findRecipients(notification);
		if (recipients.isEmpty()) {
			log.info("알림 대상이 없어 Telegram으로 보내지 않았습니다. check_id={} alert_event_id={}",
					notification.checkId(), notification.alertEventId());
			return;
		}

		// 재발송 주기는 화면에서 바꾸면 다음 webhook부터 바로 쓰이도록 알림마다 다시 읽습니다.
		Duration repeatInterval = notification.resolved() ? null
				: Duration.ofMinutes(settingRepository.findRepeatIntervalMinutes(notification.checkId()));

		for (Recipient recipient : recipients) {
			deliver(notification, recipient, repeatInterval);
		}
	}

	/**
	 * Telegram 호출은 DB 작업이 끝난 뒤에 합니다. 외부 호출이 느릴 때 connection을 잡고 기다리면 안 됩니다.
	 * Repository query가 join fetch로 그룹까지 읽어서 transaction이 끝난 뒤에도 값을 읽을 수 있습니다.
	 */
	private List<Recipient> findRecipients(AlertNotification notification) {
		List<Recipient> recipients = new ArrayList<>();

		for (CheckNotificationBinding binding : bindingRepository.findAllByCheckId(notification.checkId())) {
			if (!binding.shouldNotify(notification.severity(), notification.resolved())) {
				continue;
			}

			TelegramDestination destination = binding.getDestination();
			recipients.add(new Recipient(destination.getId(), destination.getChatId(),
					destination.getMessageThreadId()));
		}

		if (recipients.isEmpty()) {
			// 연결은 있는데 설정 때문에 걸러진 경우입니다. 왜 안 왔는지 확인할 수 있어야 합니다.
			log.info("설정 조건에 맞지 않아 알림을 보내지 않았습니다. check_id={} severity={} resolved={}",
					notification.checkId(), notification.severity(), notification.resolved());
		}

		return recipients;
	}

	/**
	 * 같은 Alert의 webhook이 겹쳐 오면 둘 다 보낸 기록이 없다고 보고 두 번 보냅니다.
	 * 그래서 기록 확인부터 전송 기록까지를 Alert와 그룹마다 하나씩만 하게 묶습니다.
	 * manager-api는 인스턴스가 하나라 프로세스 안의 락으로 충분합니다.
	 */
	private void deliver(AlertNotification notification, Recipient recipient, Duration repeatInterval) {
		Lock lock = deliveryLocks[Math.floorMod(Objects.hash(notification.alertEventId(), recipient.destinationId()),
				deliveryLocks.length)];
		lock.lock();
		try {
			deliverExclusively(notification, recipient, repeatInterval);
		}
		finally {
			lock.unlock();
		}
	}

	/** @param repeatInterval FIRING일 때만 있습니다. 복구 알림은 한 번만 보내므로 재발송 주기를 보지 않습니다. */
	private void deliverExclusively(AlertNotification notification, Recipient recipient, Duration repeatInterval) {
		AlertStatus alertStatus = notification.resolved() ? AlertStatus.RESOLVED : AlertStatus.FIRING;

		if (alertStatus == AlertStatus.FIRING && !dueForRepeat(notification.alertEventId(), recipient.destinationId(),
				repeatInterval)) {
			// 이어지는 Alert는 5분마다 webhook으로 다시 오므로, 건너뛴 것까지 기록하면 전송 기록을 읽기 어렵습니다.
			log.debug("재발송 주기가 지나지 않아 보내지 않았습니다. alert_event_id={} destination_id={}",
					notification.alertEventId(), recipient.destinationId());
			return;
		}

		if (alertStatus == AlertStatus.RESOLVED
				&& recentlySent(notification.alertEventId(), recipient.destinationId(), alertStatus)) {
			log.info("짧은 시간 안에 이미 보낸 알림이라 건너뛰었습니다. alert_event_id={} destination_id={}",
					notification.alertEventId(), recipient.destinationId());
			record(notification, recipient, alertStatus, DeliveryStatus.SKIPPED, "짧은 시간 안에 이미 보낸 알림입니다.");
			return;
		}

		TelegramSendResult result = telegramClient.sendMessage(recipient.chatId(), recipient.messageThreadId(),
				messageFormatter.format(notification));

		if (result.success()) {
			log.info("Telegram 알림을 보냈습니다. alert_event_id={} destination_id={} status={}",
					notification.alertEventId(), recipient.destinationId(), alertStatus);
			record(notification, recipient, alertStatus, DeliveryStatus.SENT, null);
			return;
		}

		// 예외를 던지면 webhook 응답이 실패가 되고 Alertmanager가 같은 payload를 계속 다시 보냅니다.
		log.warn("Telegram 알림을 보내지 못했습니다. alert_event_id={} destination_id={} reason={}",
				notification.alertEventId(), recipient.destinationId(), result.errorMessage());
		record(notification, recipient, alertStatus, DeliveryStatus.FAILED, result.errorMessage());
	}

	/** 실패한 전송은 세지 않습니다. 마지막으로 성공한 전송을 기준으로 판단합니다. */
	private boolean dueForRepeat(Long alertEventId, Long destinationId, Duration repeatInterval) {
		Optional<Instant> lastSentAt = deliveryRepository
				.findFirstByAlertEventIdAndTelegramDestinationIdAndAlertStatusAndDeliveryStatusOrderByAttemptedAtDesc(
						alertEventId, destinationId, AlertStatus.FIRING, DeliveryStatus.SENT)
				.map(NotificationDelivery::getAttemptedAt);

		return RepeatNotificationPolicy.shouldSend(lastSentAt, repeatInterval, clock.instant());
	}

	/**
	 * Alertmanager는 webhook 응답이 늦으면 같은 payload를 곧바로 다시 보냅니다.
	 * 복구 알림은 재발송 주기를 보지 않으므로 그 재시도를 짧은 창으로 막습니다.
	 * FIRING은 재발송 주기의 하한(5분)이 이 창보다 길어 재발송 판단이 같은 역할을 합니다.
	 * 실패한 전송은 세지 않습니다. 다시 보낼 수 있어야 합니다.
	 */
	private boolean recentlySent(Long alertEventId, Long destinationId, AlertStatus alertStatus) {
		Instant since = clock.instant().minus(dedupeWindow);

		return deliveryRepository
				.existsByAlertEventIdAndTelegramDestinationIdAndAlertStatusAndDeliveryStatusAndAttemptedAtAfter(
						alertEventId, destinationId, alertStatus, DeliveryStatus.SENT, since);
	}

	private void record(AlertNotification notification, Recipient recipient, AlertStatus alertStatus,
			DeliveryStatus deliveryStatus, String errorMessage) {
		deliveryRepository.save(new NotificationDelivery(notification.alertEventId(), recipient.destinationId(),
				alertStatus, deliveryStatus, errorMessage));
	}

	private record Recipient(Long destinationId, long chatId, Integer messageThreadId) {
	}

}
