package kr.co.unidocs.monitoring.notification;

import kr.co.unidocs.monitoring.config.MonitoringManagerProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * plain text로 만듭니다.
 * 메시지에 들어가는 URL과 IP의 밑줄이나 괄호를 Telegram이 서식으로 읽으면 메시지 전체가 거부됩니다.
 */
@Component
public class AlertMessageFormatter {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final ZoneId displayTimeZone;

	AlertMessageFormatter(MonitoringManagerProperties properties) {
		this.displayTimeZone = properties.notification().displayTimeZone();
	}

	public String format(AlertNotification notification) {
		StringBuilder message = new StringBuilder();

		if (notification.resolved()) {
			message.append("✅ [RESOLVED] ");
		}
		else {
			message.append("🚨 [").append(headline(notification)).append("] ");
		}
		message.append(notification.checkName()).append("\n\n");

		if (notification.environment() != null) {
			message.append("환경: ").append(notification.environment()).append('\n');
		}
		if (notification.instance() != null) {
			message.append("대상: ").append(notification.instance()).append('\n');
		}

		message.append('\n');
		if (notification.resolved()) {
			message.append("상태가 정상으로 돌아왔습니다.");
		}
		else {
			message.append((notification.summary() != null) ? notification.summary()
					: notification.checkName() + " 확인에 실패했습니다.");
		}
		message.append("\n\n");

		message.append("발생: ").append(format(notification.startsAt()));
		if (notification.resolved() && notification.resolvedAt() != null) {
			message.append('\n').append("복구: ").append(format(notification.resolvedAt()));
		}

		return message.toString();
	}

	private String headline(AlertNotification notification) {
		return (notification.severityLabel() != null) ? notification.severityLabel().toUpperCase() : "ALERT";
	}

	private String format(Instant instant) {
		if (instant == null) {
			return "-";
		}
		return TIME_FORMAT.format(instant.atZone(displayTimeZone));
	}

}
