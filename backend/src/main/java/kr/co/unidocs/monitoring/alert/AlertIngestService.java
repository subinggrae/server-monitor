package kr.co.unidocs.monitoring.alert;

import kr.co.unidocs.monitoring.notification.AlertNotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AlertIngestService {

	private static final Logger log = LoggerFactory.getLogger(AlertIngestService.class);

	private final AlertEventService alertEventService;

	private final AlertNotificationDispatcher dispatcher;

	AlertIngestService(AlertEventService alertEventService, AlertNotificationDispatcher dispatcher) {
		this.alertEventService = alertEventService;
		this.dispatcher = dispatcher;
	}

	public void handle(AlertmanagerWebhookPayload payload) {
		if (payload == null || payload.alerts() == null || payload.alerts().isEmpty()) {
			log.info("Alert가 들어 있지 않은 webhook 요청을 받았습니다.");
			return;
		}

		log.info("Alertmanager webhook을 받았습니다. group_key={} status={} count={}", payload.groupKey(),
				payload.status(), payload.alerts().size());

		for (AlertmanagerWebhookPayload.Alert alert : payload.alerts()) {
			try {
				alertEventService.record(alert).ifPresent(dispatcher::dispatch);
			}
			catch (Exception ex) {
				// 한 건 때문에 요청 전체를 실패시키면 Alertmanager가 같은 payload를 계속 다시 보냅니다.
				log.error("Alert 한 건을 처리하지 못했습니다. fingerprint={} reason={}", alert.fingerprint(),
						ex.getClass().getSimpleName() + ": " + ex.getMessage());
			}
		}
	}

}
