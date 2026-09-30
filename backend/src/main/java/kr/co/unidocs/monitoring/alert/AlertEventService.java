package kr.co.unidocs.monitoring.alert;

import kr.co.unidocs.monitoring.check.MonitoringCheck;
import kr.co.unidocs.monitoring.check.MonitoringCheckRepository;
import kr.co.unidocs.monitoring.check.MonitoringCheckTarget;
import kr.co.unidocs.monitoring.notification.AlertNotification;
import kr.co.unidocs.monitoring.notification.routing.AlertSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class AlertEventService {

	private static final String CHECK_ID_LABEL = "check_id";

	private static final String CHECK_TARGET_ID_LABEL = "check_target_id";

	private static final String CHECK_NAME_LABEL = "check_name";

	private static final String ENVIRONMENT_LABEL = "environment";

	private static final String INSTANCE_LABEL = "instance";

	private static final String SEVERITY_LABEL = "severity";

	private static final String ALERT_NAME_LABEL = "alertname";

	private static final Logger log = LoggerFactory.getLogger(AlertEventService.class);

	private final AlertEventRepository alertEventRepository;

	private final MonitoringCheckRepository checkRepository;

	AlertEventService(AlertEventRepository alertEventRepository, MonitoringCheckRepository checkRepository) {
		this.alertEventRepository = alertEventRepository;
		this.checkRepository = checkRepository;
	}

	@Transactional(readOnly = true)
	public List<AlertEventResponse> findRecent(int limit) {
		return alertEventRepository.findAllByOrderByLastReceivedAtDescIdDesc(Limit.of(limit)).stream()
				.map(AlertEventResponse::of)
				.toList();
	}

	/**
	 * @return 어떤 Check인지 찾지 못하면 비어 있습니다.
	 *   이때도 Alert 기록은 남깁니다. 어디로 보낼지 모를 뿐이지 받은 것은 사실입니다.
	 */
	@Transactional
	public Optional<AlertNotification> record(AlertmanagerWebhookPayload.Alert alert) {
		Long checkId = parseId(alert.label(CHECK_ID_LABEL));
		Long checkTargetId = parseId(alert.label(CHECK_TARGET_ID_LABEL));
		AlertStatus status = alert.alertStatus();
		Instant startsAt = (alert.startsAt() != null) ? alert.startsAt() : Instant.now();

		Optional<MonitoringCheck> check = (checkId != null) ? checkRepository.findByIdWithTargets(checkId)
				: Optional.empty();

		String instance = resolveInstance(alert, check, checkTargetId);
		String summary = truncate(alert.annotation("summary"), 500);

		AlertEvent event = alertEventRepository.findByFingerprintAndStartsAt(fingerprintOf(alert), startsAt)
				.orElse(null);

		if (event != null) {
			event.receiveAgain(status, summary, truncate(alert.annotation("description"), 1000), alert.resolvedAt());
		}
		else {
			event = alertEventRepository.save(new AlertEvent(fingerprintOf(alert),
					truncate(alertNameOf(alert), 200), checkId, checkTargetId,
					truncate(alert.label(SEVERITY_LABEL), 20), status, truncate(instance, 500), summary,
					truncate(alert.annotation("description"), 1000), startsAt, alert.resolvedAt()));
		}
		alertEventRepository.flush();

		if (checkId == null) {
			log.warn("Alert에 check_id label이 없습니다. alert_name={} (Prometheus 규칙의 label을 확인해 주세요)",
					event.getAlertName());
			return Optional.empty();
		}
		if (check.isEmpty()) {
			log.warn("Alert의 check_id에 해당하는 Check가 없습니다. check_id={} alert_event_id={} (이미 지운 Check일 수 있습니다)",
					checkId, event.getId());
			return Optional.empty();
		}
		if (checkTargetId != null && findTarget(check.get(), checkTargetId).isEmpty()) {
			log.info("Alert의 check_target_id에 해당하는 대상이 없습니다. check_id={} check_target_id={}", checkId,
					checkTargetId);
		}

		return Optional.of(new AlertNotification(event.getId(), checkId, check.get().getName(),
				environmentOf(alert, check.get()), instance, AlertSeverity.from(alert.label(SEVERITY_LABEL)),
				alert.label(SEVERITY_LABEL), status.resolved(), summary, startsAt, event.getResolvedAt()));
	}

	/** Manager가 알고 있는 주소를 먼저 씁니다. 찾지 못하면 Prometheus가 보낸 instance label을 씁니다. */
	private String resolveInstance(AlertmanagerWebhookPayload.Alert alert, Optional<MonitoringCheck> check,
			Long checkTargetId) {
		if (check.isPresent() && checkTargetId != null) {
			Optional<MonitoringCheckTarget> target = findTarget(check.get(), checkTargetId);
			if (target.isPresent()) {
				return target.get().blackboxTarget();
			}
		}
		return alert.label(INSTANCE_LABEL);
	}

	private Optional<MonitoringCheckTarget> findTarget(MonitoringCheck check, Long checkTargetId) {
		return check.getTargets().stream().filter(target -> checkTargetId.equals(target.getId())).findFirst();
	}

	private String environmentOf(AlertmanagerWebhookPayload.Alert alert, MonitoringCheck check) {
		String label = alert.label(ENVIRONMENT_LABEL);
		return (label != null && !label.isBlank()) ? label : check.getEnvironment();
	}

	private String alertNameOf(AlertmanagerWebhookPayload.Alert alert) {
		String alertName = alert.label(ALERT_NAME_LABEL);
		if (alertName != null && !alertName.isBlank()) {
			return alertName;
		}

		String checkName = alert.label(CHECK_NAME_LABEL);
		return (checkName != null) ? checkName : "UnknownAlert";
	}

	/** Alertmanager는 항상 fingerprint를 보냅니다. 직접 만든 요청처럼 값이 없을 때만 label로 대신 만듭니다. */
	private String fingerprintOf(AlertmanagerWebhookPayload.Alert alert) {
		if (alert.fingerprint() != null && !alert.fingerprint().isBlank()) {
			return truncate(alert.fingerprint(), 100);
		}
		return "no-fingerprint-" + Integer.toHexString(Objects.hashCode(alert.labels()));
	}

	private Long parseId(String rawId) {
		if (rawId == null || rawId.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(rawId.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String truncate(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		return (value.length() <= maxLength) ? value : value.substring(0, maxLength);
	}

}
