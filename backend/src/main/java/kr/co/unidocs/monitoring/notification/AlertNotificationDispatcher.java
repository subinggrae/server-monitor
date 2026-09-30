package kr.co.unidocs.monitoring.notification;

import kr.co.unidocs.monitoring.alert.AlertStatus;
import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationBinding;
import kr.co.unidocs.monitoring.notification.routing.CheckNotificationBindingRepository;
import kr.co.unidocs.monitoring.notification.telegram.TelegramClient;
import kr.co.unidocs.monitoring.notification.telegram.TelegramDestination;
import kr.co.unidocs.monitoring.notification.telegram.TelegramSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

	private final NotificationDeliveryRepository deliveryRepository;

	private final TelegramClient telegramClient;

	private final AlertMessageFormatter messageFormatter;

	private final Duration dedupeWindow;

	AlertNotificationDispatcher(CheckNotificationBindingRepository bindingRepository,
			NotificationDeliveryRepository deliveryRepository, TelegramClient telegramClient,
			AlertMessageFormatter messageFormatter, MonitoringManagerProperties properties) {
		this.bindingRepository = bindingRepository;
		this.deliveryRepository = deliveryRepository;
		this.telegramClient = telegramClient;
		this.messageFormatter = messageFormatter;
		this.dedupeWindow = properties.notification().dedupeWindow();
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

		for (Recipient recipient : recipients) {
			deliver(notification, recipient);
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

	private void deliver(AlertNotification notification, Recipient recipient) {
		AlertStatus alertStatus = notification.resolved() ? AlertStatus.RESOLVED : AlertStatus.FIRING;

		if (recentlySent(notification.alertEventId(), recipient.destinationId(), alertStatus)) {
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

	/**
	 * Alertmanager는 webhook 응답이 늦으면 같은 payload를 곧바로 다시 보냅니다.
	 * 그 재시도만 막고 repeat_interval에 따른 정상 반복 알림은 통과시켜야 해서 짧은 창으로만 확인합니다.
	 * 실패한 전송은 세지 않습니다. 다시 보낼 수 있어야 합니다.
	 */
	private boolean recentlySent(Long alertEventId, Long destinationId, AlertStatus alertStatus) {
		Instant since = Instant.now().minus(dedupeWindow);

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
